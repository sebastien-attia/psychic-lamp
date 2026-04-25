---
paths: ["bff/**/*.java", "bff/pom.xml"]
---
# BFF Rules — Thin Proxy with OAuth2 Session

## Architecture
- BFF is a thin HTTP proxy. It has NO domain logic and NO JPA.
- adapter.in.web → Spring @RestController, depends on infrastructure.service only
- adapter.out.client → BusinessServiceClient (Spring HTTP Interface), calls Business Service
- infrastructure.service → BoatBffService, thin orchestrator, NO @Transactional
- infrastructure.security → SecurityConfig (OAuth2 session, CSRF), BffSecurityHelper

## ArchUnit rules enforced
- No JPA imports (jakarta.persistence.*) anywhere in BFF
- @Transactional is forbidden on BoatBffService and all BFF classes
- BusinessServiceClient must be an interface
- Controllers depend on infrastructure.service only (not on adapter.out.client directly)
- GlobalExceptionHandler must declare SLF4J Logger

## Token forwarding
- BFF attaches Bearer access_token to all Business Service calls via RestClient interceptor
- DefaultOAuth2AuthorizedClientManager handles token refresh automatically (refresh_token)
- Tokens stored in OAuth2AuthorizedClientRepository backed by Spring Session JDBC

## Profiles
- dev: not used in dev mode (Business Service started directly). DevSecurityConfig: permitAll.
- local-intg/staging/prod: full oauth2Login, session cookie, CSRF, token forwarding

## Jackson
- Spring Boot 4.0.6 auto-configures Jackson 3, NOT Jackson 2. The `ObjectMapper`
  bean is `tools.jackson.databind.ObjectMapper`. Wiring `com.fasterxml.jackson.databind.ObjectMapper`
  fails at startup with `NoSuchBeanDefinitionException`.
- Use Jackson 3 imports: `tools.jackson.databind.ObjectMapper`, `tools.jackson.core.JacksonException`.
- `JacksonException` is unchecked (extends `RuntimeException`) and exposes
  `getOriginalMessage()` — use it in place of Jackson 2's `JsonProcessingException`.
