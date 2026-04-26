---
paths: ["bff/**/*.java", "bff/pom.xml", "bff/src/main/resources/application*.yml"]
---
# BFF Rules — Spring Cloud Gateway Server Web MVC

## Architecture
- BFF is a Spring Cloud Gateway (servlet flavor — `spring-cloud-starter-gateway-server-webmvc` 5.0.1, Spring Cloud 2025.1.1).
- Routes are DECLARATIVE in `bff/src/main/resources/application-routes.yml`. The `boats-api` route uses the `TokenRelay=keycloak` filter to forward the user's access token to the Business Service. There is no Java controller, no `*BffService`, no outbound HTTP-Interface client for the proxied paths.
- BFF-LOCAL Java endpoints (NOT routed via SCG):
  - `AuthController` — `/api/me` (projects the OIDC user → `UserInfoResponse`).
  - `JwksController` — `/.well-known/jwks.json` (publishes the BFF's signing key).
  - Spring Security's `/login/**`, `/oauth2/**`, `/logout`, `/api/logout`, `/actuator/health`.
- Infrastructure:
  - `infrastructure.config.BffConfig` — wires `bffSigningJwk`, `codeTokenResponseClient`, `refreshTokenResponseClient`, `authorizedClientManager` (consumed by SCG's `TokenRelayFilterFunctions` per request via `getBean`).
  - `infrastructure.security.SecurityConfig` — oauth2Login, session-cookie, CSRF, Keycloak server-side logout.
  - `infrastructure.web.ScgUpstreamFailureFilter` — rewrites upstream 5xx without an RFC 9457 body to a 502 `upstream-failure` envelope. Upstream 4xx and upstream 5xx WITH RFC 9457 bodies are passed through byte-identical.

## ArchUnit rules enforced (`BffArchitectureTest`)
- No JPA imports (`jakarta.persistence.*`) anywhere in BFF.
- `@Transactional` forbidden on every BFF class and method.
- No class may be (re-)introduced in `..bff.adapter.out.client..` — proxying is owned by SCG config, not Java code.
- No BFF class may depend on `org.springframework.web.client.RestTemplate` (the historical path to a hand-rolled outbound client). `KeycloakServerSideLogoutHandler` uses `RestClient` for the back-channel logout call and is exempted by name.
- `application-routes.yml` must be on the classpath.
- `@RestControllerAdvice` must declare an SLF4J `Logger` field; every `@ExceptionHandler` must emit a log record.

## Token forwarding
- SCG `TokenRelay` filter resolves `OAuth2AuthorizedClientManager` per request via `getApplicationContext(request).getBean(...)` and attaches `Authorization: Bearer <access_token>` to the outbound call.
- `DefaultOAuth2AuthorizedClientManager` handles refresh transparently via the `refresh_token` provider (signed with `private_key_jwt`).
- `OAuth2AuthorizedClient`s are stored per principal in `OAuth2AuthorizedClientRepository`, backed by Spring Session JDBC against the `bff_session` PostgreSQL DB.

## Routes file
- The route table lives in `application-routes.yml`. It is imported by each non-dev profile YAML (`application-local-intg.yml`, `application-staging.yml`, `application-prod.yml`) via `spring.config.import`. The `dev` profile does NOT import it because `BffConfig` is `@Profile("!dev")` and TokenRelay would have no manager bean to resolve.
- Path predicates use the `{*subpath}` capture form to match the collection root (`/api/v1/boats`) AND sub-paths (`/api/v1/boats/{id}`) with one declaration. The PathPattern Javadoc only formally guarantees zero-segment matching for the named-capture form.

## Profiles
- `dev`: BFF NOT started in dev mode. `DevSecurityConfig` (`@Profile("dev")`) provides a permitAll filter chain so ad-hoc `SPRING_PROFILES_ACTIVE=dev` BFF runs do not crash on the missing Keycloak registration. SCG routes are not loaded.
- `local-intg` / `staging` / `prod`: full `oauth2Login`, session cookie, CSRF, SCG routes loaded with TokenRelay.

## SPA hosting (NOT in the BFF jar)
- Dev / `local-intg`: served by Vite at `:5173` (`cd frontend && npm run dev:intg`); Vite proxies `/api`, `/oauth2`, `/login`, `/logout` to the BFF on `:8080`.
- Staging / prod: hosted on Azure Static Web Apps with Bring-Your-Own-Backend pointing at the BFF Container App.
- The BFF MUST NOT serve `/`, `/index.html`, or `/assets/**`. `BffStaticContentRegressionTest` enforces this.

## Jackson
- Spring Boot 4.0.6 auto-configures Jackson 3, NOT Jackson 2. The `ObjectMapper`
  bean is `tools.jackson.databind.ObjectMapper`. Wiring `com.fasterxml.jackson.databind.ObjectMapper`
  fails at startup with `NoSuchBeanDefinitionException`.
- Use Jackson 3 imports: `tools.jackson.databind.ObjectMapper`, `tools.jackson.core.JacksonException`.
- `JacksonException` is unchecked (extends `RuntimeException`) and exposes
  `getOriginalMessage()` — use it in place of Jackson 2's `JsonProcessingException`.
