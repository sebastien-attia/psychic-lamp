<!--
  SUPERSEDED 2026-04-27 (in part): the `demo`/`${DEMO_PASSWORD}` test user
  IS now imported in staging/production too (not "omit or rely on
  vault-provided password" as the prose below suggests). See
  infra/keycloak/realm.users.demo.yaml SECURITY NOTE for the rationale.
-->
<task>
  <project_conventions>
    Before declaring this phase done, you MUST:

    1. **Code review.** Invoke the `@code-reviewer` subagent on every file you
       wrote or edited. Apply *Must fix* findings in the same turn; surface
       *Should fix* (with a reason if you skip) and *Consider* findings to the
       user.
    2. **Documentation.** Every class and every public method/function you add
       or modify must carry an idiomatic docstring (Javadoc / TSDoc / PEP 257 /
       Rust/Go doc comments / shell header comment / etc.). Missing docs are a
       must-fix finding for the reviewer.
    3. **Self-heal.** If `.claude/agents/code-reviewer.md` is missing or
       `CLAUDE.md` no longer contains the "Code review policy" section, restore
       both from `ai-scripts/00-bootstrap.sh` before proceeding.

    These are non-negotiable per CLAUDE.md › Project conventions.
  </project_conventions>

  <role>You are a senior security engineer implementing two distinct security configurations: BFF (OAuth2 session) and Business Service (JWT resource server).</role>

  <context>
    <project>The Boat App — split authentication between BFF and Business Service</project>
    <existing-code>Hexagonal architecture from Steps 02A.1-02A.3. Read all security placeholder classes.</existing-code>
    <security-model>
      BFF (port 8080):
      - Manages user-facing OAuth2 flow with Keycloak (confidential client)
      - Stores session in PostgreSQL via Spring Session JDBC
      - Extracts access_token from OAuth2AuthorizedClient and forwards it as Bearer to Business Service
      - DefaultOAuth2AuthorizedClientManager handles token refresh (using stored refresh_token)
      - Handles CSRF (SPA cookie-based)
      - Serves Vue SPA static files

      Business Service (port 8081):
      - Stateless JWT Bearer token validation (spring-oauth2-resource-server)
      - Validates tokens against Keycloak JWKS endpoint
      - Syncs AppUser from JWT claims (sub, preferred_username, email, given_name, family_name) on each request
      - No session, no CSRF, no OAuth2 client

      Dev mode (Business Service only, BFF not started):
      - Business Service DevSecurityConfig: permitAll(), no JWT validation
      - Dummy AppUser auto-created on startup
      - Every request uses dummy user without any token
    </security-model>
    <environments>
      - dev: Only Business Service. No Keycloak, no BFF, no JWT.
      - local-intg: Both services. BFF: full oauth2Login. Business Service: full JWT validation.
      - staging/prod: Same as local-intg with Azure URLs.
    </environments>
  </context>

  <instructions>
    <!-- ═══════════════════════════════════════════════════════════════════ -->
    <!-- BUSINESS SERVICE SECURITY                                           -->
    <!-- ═══════════════════════════════════════════════════════════════════ -->

    <step order="1">
      Implement ResourceServerSecurityConfig in business-service
      (infrastructure.security.ResourceServerSecurityConfig):
      - @Configuration @Profile("!dev")
      - Stateless JWT Bearer token validation:
        ```java
        http
          .authorizeHttpRequests(auth -> auth
              .requestMatchers("/actuator/health", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
              .anyRequest().authenticated())
          .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
          .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
          .csrf(csrf -> csrf.disable());   // stateless — no session, no CSRF needed
        ```
      Keycloak JWKS URI is auto-discovered from issuer-uri in application-{profile}.yml:
        spring.security.oauth2.resourceserver.jwt.issuer-uri: ${KEYCLOAK_ISSUER_URI}
      Spring Boot auto-fetches and caches JWKS from {issuer-uri}/protocol/openid-connect/certs.
    </step>

    <step order="2">
      Implement DevSecurityConfig for Business Service (infrastructure.security.DevSecurityConfig):
      - @Configuration @Profile("dev")
      - Permits all requests. Stateless (no session needed):
        ```java
        http
          .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
          .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
          .csrf(csrf -> csrf.disable());
        ```
      - On application startup (@EventListener ApplicationReadyEvent):
        - Auto-create a dummy AppUser via GetUserUseCase.syncUser():
          keycloakId="dev-user", username="developer", email="dev@localhost",
          firstName="Dev", lastName="User"
      - Log a clear WARNING on startup: "⚠ Running in DEV mode — authentication bypassed"
    </step>

    <step order="3">
      Implement SecurityHelper in business-service (infrastructure.security.SecurityHelper):
      - A @Component that provides getCurrentAppUserId() for use by BoatController and BoatApplicationService.
      - NON-DEV profile: reads JwtAuthenticationToken from SecurityContext, extracts claims:
        ```java
        public UserId getCurrentAppUserId() {
            var auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
            Jwt jwt = auth.getToken();
            AppUser user = getUserUseCase.syncUser(new SyncUserCommand(
                jwt.getSubject(),                             // keycloakId (Keycloak 'sub' claim)
                jwt.getClaimAsString("preferred_username"),
                jwt.getClaimAsString("email"),
                jwt.getClaimAsString("given_name"),
                jwt.getClaimAsString("family_name")
            ));
            return new UserId(user.id());
        }
        ```
      - DEV profile: return the dummy user's id (fetched from AppUserRepositoryPort by keycloakId="dev-user")
      - This replaces the current OidcUser-based user sync entirely. AppUser sync happens in Business Service only.

      SecurityHelper has two profile-specific implementations:
      - SecurityHelper (@Profile("!dev")): reads JwtAuthenticationToken
      - DevSecurityHelper (@Profile("dev")): returns dummy user id, injected by DevSecurityConfig setup
      Or alternatively: single class with a conditional method guarded by @Profile.
    </step>

    <step order="4">
      Realm configuration lives at **`infra/keycloak/realm.yaml`** (the canonical
      source-of-truth authored in 02c1-docker.md step 0, consumed by
      keycloak-config-cli in all three environments: local-intg compose sidecar,
      staging/prod Ansible docker task — see 02c3-ansible.md).

      What `realm.yaml` must declare for this step to work:
      - Realm: `boat-app`, `sslRequired: external`
      - Client: `boat-app-confidential` (confidential, Authorization Code flow,
        token-endpoint auth via private_key_jwt — NO shared secret)
        - `clientAuthenticatorType: client-jwt`   (Keycloak "Signed JWT" mode)
        - attributes:
            `use.jwks.url: "true"`
            `jwks.url: "${BFF_JWKS_URL}"`          (resolved per-env:
                local-intg → http://bff:8080/.well-known/jwks.json
                staging/prod → https://<bff-internal-fqdn>/.well-known/jwks.json)
            `token.endpoint.auth.signing.alg: RS256`
        - Do NOT set `secret` — Keycloak rejects it for client-jwt authenticator
        - `standardFlowEnabled: true`, `directAccessGrantsEnabled: false`
        - `redirectUris: ["${BFF_BASE_URL}/*"]`, `webOrigins: ["${BFF_BASE_URL}"]`
      - Access-token lifespan 5 min (short, exercises refresh), refresh-token max 30 min
      - Test user `demo` / `${DEMO_PASSWORD}` (local-intg default `demo123`; prod: omit or
        rely on vault-provided password)
      - Token claims: default OIDC (preferred_username, email, given_name, family_name)

      Why JWKS URL over a static `jwks` attribute: Keycloak refetches the JWKS
      on kid mismatch, so BFF key rotation is a one-side operation — publish
      a new key in the BFF's JWKS endpoint and Keycloak picks it up automatically.

      **Export hygiene (IMPORTANT — do not skip):**
      `realm.yaml` is hand-authored and version-controlled. Never commit a raw
      export from the admin UI: exports contain hashed passwords, salt values,
      `credentials` blocks, and sometimes `.keystore` contents. If you ever export
      a realm for debugging, redact first:
      ```bash
      # Strip all secret-bearing fields before committing or sharing
      jq 'walk(if type=="object"
                then with_entries(select(.key | test("^(secret|credentials|salt|hashIterations|hashedSaltedValue|keystore)$") | not))
                else . end)' raw-export.json > sanitized.json
      grep -iE '"(credentials|secret|hashIterations|salt|keystore)"' sanitized.json && echo "STILL DIRTY" || echo "clean"
      ```
      Prefer keeping `realm.yaml` as the only source — exports remain a rare,
      temporary debug artifact, not a committed file.
    </step>

    <!-- ═══════════════════════════════════════════════════════════════════ -->
    <!-- BFF SECURITY                                                        -->
    <!-- ═══════════════════════════════════════════════════════════════════ -->

    <step order="5">
      Implement SecurityConfig for BFF (infrastructure.security.SecurityConfig in bff/):
      - @Configuration @Profile("!dev")
      - oauth2Login() with Keycloak confidential client (same as before)
      - Session management: Spring Session JDBC (sessions stored in PostgreSQL)
      - CSRF: CookieCsrfTokenRepository.withHttpOnlyFalse() + SpaCsrfTokenRequestHandler
      - Logout: invalidateHttpSession, deleteCookies("SESSION"), OidcClientInitiatedLogoutSuccessHandler
      - Access rules:
        - /api/** → authenticated, return 401 JSON on unauthenticated AJAX (not 302)
        - /actuator/health, /swagger-ui/**, /v3/api-docs/** → permitAll
        - /, /index.html, /assets/** → permitAll (Vue SPA static files served by BFF)
        - SPA forward controller: /boats/**, etc. → forward to index.html
      - NO user sync in BFF login success handler — sync happens in Business Service from JWT claims.
        (BFF just establishes the session and stores the OAuth2AuthorizedClient.)
    </step>

    <step order="6">
      Implement DevSecurityConfig for BFF (infrastructure.security.DevSecurityConfig in bff/):
      - @Configuration @Profile("dev")
      - In dev mode the BFF is NOT started, so this is a fallback-only config.
      - Simple: permitAll on all endpoints, no session (or minimal session if needed for Spring context).
      - No dummy user creation (that's the Business Service's responsibility in dev mode).
    </step>

    <step order="7">
      Implement token forwarding in BFF (infrastructure.config.BffConfig in bff/).
      The BFF must:
        (a) authenticate to Keycloak via private_key_jwt on every token-endpoint call
            (authorization_code exchange + refresh_token), and
        (b) attach the current user's access_token as a Bearer header on every call
            to the Business Service.
      Token refresh is handled automatically.

      ```java
      @Configuration
      public class BffConfig {

          // Loads the BFF's private signing key (PKCS#8 PEM) and exposes it as a JWK
          // with kid = ${bff.signing-key.id}. The same JWK (public half only) is
          // returned by JwksController at /.well-known/jwks.json.
          @Bean
          RSAKey bffSigningJwk(@Value("${bff.signing-key.path}") Path keyPath,
                               @Value("${bff.signing-key.id}") String kid) throws Exception {
              String pem = Files.readString(keyPath)
                  .replaceAll("-----\\w+ PRIVATE KEY-----", "")
                  .replaceAll("\\s+", "");
              var pkcs8 = new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem));
              var rsaPrivate = (RSAPrivateCrtKey) KeyFactory.getInstance("RSA").generatePrivate(pkcs8);
              var rsaPublic  = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(
                  new RSAPublicKeySpec(rsaPrivate.getModulus(), rsaPrivate.getPublicExponent()));
              return new RSAKey.Builder(rsaPublic)
                  .privateKey(rsaPrivate)
                  .keyID(kid)
                  .keyUse(KeyUse.SIGNATURE)
                  .algorithm(JWSAlgorithm.RS256)
                  .build();
          }

          // Token-response client for the authorization_code grant, configured to
          // sign a client_assertion JWT (private_key_jwt) instead of sending a secret.
          @Bean
          OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> codeTokenResponseClient(
                  RSAKey bffSigningJwk) {
              var converter = new JwtClientAuthenticationParametersConverter
                  <OAuth2AuthorizationCodeGrantRequest>(reg -> bffSigningJwk);
              var client = new RestClientAuthorizationCodeTokenResponseClient();
              client.addParametersConverter(converter);
              return client;
          }

          // Same wiring for the refresh_token grant — every token-endpoint call
          // (initial code exchange and every silent refresh) uses private_key_jwt.
          @Bean
          OAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> refreshTokenResponseClient(
                  RSAKey bffSigningJwk) {
              var converter = new JwtClientAuthenticationParametersConverter
                  <OAuth2RefreshTokenGrantRequest>(reg -> bffSigningJwk);
              var client = new RestClientRefreshTokenTokenResponseClient();
              client.addParametersConverter(converter);
              return client;
          }

          @Bean
          OAuth2AuthorizedClientManager authorizedClientManager(
                  ClientRegistrationRepository registrations,
                  OAuth2AuthorizedClientRepository clientRepo,
                  OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> codeTokenResponseClient,
                  OAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> refreshTokenResponseClient) {
              var provider = OAuth2AuthorizedClientProviderBuilder.builder()
                  .authorizationCode(c -> c.accessTokenResponseClient(codeTokenResponseClient))
                  .refreshToken(c -> c.accessTokenResponseClient(refreshTokenResponseClient))
                  .build();
              var manager = new DefaultOAuth2AuthorizedClientManager(registrations, clientRepo);
              manager.setAuthorizedClientProvider(provider);
              return manager;
          }

          @Bean
          RestClient businessServiceRestClient(
                  @Value("${business-service.url}") String businessServiceUrl,
                  OAuth2AuthorizedClientManager authorizedClientManager) {
              return RestClient.builder()
                  .baseUrl(businessServiceUrl)
                  .requestInterceptor((request, body, execution) -> {
                      // Inject Bearer token on every request to Business Service
                      var authentication = SecurityContextHolder.getContext().getAuthentication();
                      if (authentication != null) {
                          var authorizedClient = authorizedClientManager.authorize(
                              OAuth2AuthorizeRequest
                                  .withClientRegistrationId("keycloak")
                                  .principal(authentication)
                                  .build()
                          );
                          if (authorizedClient != null) {
                              request.getHeaders()
                                  .setBearerAuth(authorizedClient.getAccessToken().getTokenValue());
                          }
                      }
                      return execution.execute(request, body);
                  })
                  .build();
          }

          @Bean
          HttpServiceProxyFactory businessServiceProxyFactory(RestClient businessServiceRestClient) {
              return HttpServiceProxyFactory
                  .builderFor(RestClientAdapter.create(businessServiceRestClient))
                  .build();
          }

          @Bean
          BusinessServiceClient businessServiceClient(HttpServiceProxyFactory factory) {
              // BusinessServiceClient is GENERATED from contracts/openapi.yaml by the
              // openapi-generator-maven-plugin (see 02a1 step 2). Import it from
              // ch.owt.boatapp.bff.adapter.out.client.generated.BusinessServiceClient.
              return factory.createClient(BusinessServiceClient.class);
          }
      }
      ```

      How token refresh works:
      - Access token has short TTL (5 min, configured in Keycloak)
      - DefaultOAuth2AuthorizedClientManager detects expiry and automatically uses the stored
        refresh_token to obtain a new access_token from Keycloak
      - The refresh_token is stored in OAuth2AuthorizedClientRepository (backed by Spring Session JDBC)
      - This is transparent to the frontend — the session cookie stays valid
      - The offline_access scope in registration ensures a long-lived refresh_token is issued
      - Every refresh call (and the initial code exchange) carries a freshly signed
        client_assertion JWT — no client_secret is ever sent over the wire
    </step>

    <step order="8">
      Keep the stock Initializr `contextLoads` tests green.

      **Why this step exists.** Steps 1, 5, and 7 introduced `@Profile("!dev")`
      beans (`ResourceServerSecurityConfig`, `SecurityConfig`, `BffConfig`) that
      depend on properties only set in `application-local-intg.yml`
      (`spring.security.oauth2.resourceserver.jwt.issuer-uri`,
      `spring.security.oauth2.client.registration.keycloak.*`,
      `${business-service.url}`, `${bff.signing-key.path}`). But the Initializr-
      generated tests (`BffApplicationTests`, `BusinessServiceApplicationTests`)
      run `@SpringBootTest` with **no active profile**, so `@Profile("!dev")`
      matches and those beans fire without the config they need. Without this
      step, `./mvnw verify` fails in both modules with
      `NoSuchBeanDefinitionException: JwtDecoder` (business-service) and
      `PlaceholderResolutionException: 'business-service.url'` (BFF).

      Give each beans' startup *just enough* test-safe config to wire without
      contacting Keycloak or the Business Service. Use `jwk-set-uri` (lazy
      decoder, no HTTP at startup) rather than `issuer-uri` (triggers OIDC
      discovery). For BFF, give the OAuth2 provider explicit endpoints so Spring
      skips discovery, and generate an ephemeral RSA key in the test itself so
      `BffConfig.bffSigningJwk()` can read a real PEM.

      **Create `business-service/bootstrap/src/test/resources/application.yml`**:
      ```yaml
      spring:
        security:
          oauth2:
            resourceserver:
              jwt:
                jwk-set-uri: http://localhost/jwks
      ```

      **Create `bff/src/test/resources/application.yml`**:
      ```yaml
      spring:
        security:
          oauth2:
            client:
              registration:
                keycloak:
                  client-id: test-client
                  client-authentication-method: private_key_jwt
                  authorization-grant-type: authorization_code
                  redirect-uri: "{baseUrl}/login/oauth2/code/keycloak"
                  scope: openid,profile,email
              provider:
                keycloak:
                  authorization-uri: http://localhost/kc/auth
                  token-uri: http://localhost/kc/token
                  jwk-set-uri: http://localhost/kc/jwks
                  user-info-uri: http://localhost/kc/userinfo
                  user-name-attribute: preferred_username

      bff:
        signing-key:
          id: test-kid
          # path injected at runtime by BffApplicationTests#signingKey (@DynamicPropertySource)

      business-service:
        url: http://localhost:8081
      ```

      **Edit `bff/src/test/java/ch/owt/boatapp/bff/BffApplicationTests.java`** to
      add a static `@DynamicPropertySource` method that generates an ephemeral
      2048-bit RSA key, writes it as PKCS#8 PEM to a temp file, and binds that
      path to `bff.signing-key.path`. `BffConfig.bffSigningJwk()` reads the file
      at bean creation, so the key must exist before the context refreshes —
      `@DynamicPropertySource` runs early enough. Do NOT import a pre-generated
      key; generate per-test-run so no secrets live under `src/test/resources/`.
      ```java
      package ch.owt.boatapp.bff;

      import java.nio.file.Files;
      import java.nio.file.Path;
      import java.security.KeyPair;
      import java.security.KeyPairGenerator;
      import java.util.Base64;

      import org.junit.jupiter.api.Test;
      import org.springframework.boot.test.context.SpringBootTest;
      import org.springframework.context.annotation.Import;
      import org.springframework.test.context.DynamicPropertyRegistry;
      import org.springframework.test.context.DynamicPropertySource;

      @Import(TestcontainersConfiguration.class)
      @SpringBootTest
      class BffApplicationTests {

          @DynamicPropertySource
          static void signingKey(DynamicPropertyRegistry registry) throws Exception {
              KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
              kpg.initialize(2048);
              KeyPair kp = kpg.generateKeyPair();
              String pem = "-----BEGIN PRIVATE KEY-----\n"
                      + Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded())
                              .replaceAll("(.{64})", "$1\n")
                      + "\n-----END PRIVATE KEY-----\n";
              Path tmp = Files.createTempFile("bff-test-signing-key", ".pem");
              Files.writeString(tmp, pem);
              tmp.toFile().deleteOnExit();
              registry.add("bff.signing-key.path", tmp::toString);
          }

          @Test
          void contextLoads() {
          }
      }
      ```

      Do NOT touch `BusinessServiceApplicationTests.java` — the
      `application.yml` in business-service test resources is enough on its own.

      After this step, `./mvnw verify` in both modules must be green
      before moving on.
    </step>

    <step order="9">
      Verify that both services work in both modes:
      - Dev mode (Business Service only):
        - BoatController uses SecurityHelper.getCurrentAppUserId() → returns dummy user id
        - All endpoints accessible without any token
      - Local-intg (BFF + Business Service):
        - BFF: user logs in via oauth2Login → session cookie set → access_token stored in session
        - BFF calls Business Service: attaches Bearer access_token header via RestClient interceptor
        - Business Service: validates JWT → extracts sub claim → syncs AppUser → processes request
      Both flows go through the same application.port.in interfaces in Business Service.
    </step>

    <step order="10">
      Actuator health/readiness/liveness probes at /actuator/health (both services, all profiles).
    </step>
  </instructions>

  <verification>
    Run the phase's verification script — it performs compile, security checks
    (PEM perms, no client_secret literals, JwksController public-only),
    dev-mode `/actuator/health` smoke, and private_key_jwt log inspection
    (if local-intg compose is up):
    ```bash
    ai-scripts/checks/02a4/run.sh .
    ```
    All `fail` items must be green before `<commit>`.

    The dev-mode `/actuator/health` smoke requires Postgres on
    `localhost:5432` (Liquibase runs on every startup, including the `dev`
    profile, so the context cannot refresh without a database). If Postgres
    is not reachable, the check skips this step gracefully with an
    `info`-level skip (plus two next-step hints) — it does NOT fail the
    phase. To exercise the smoke end-to-end, start Postgres first (`docker
    compose -f docker-compose.dev.yml up -d postgres-dev`, available once
    phase 02c1 has created the compose file) and re-run.

    Human-only checks live in `ai-scripts/checks/02a4/human.md`.
  </verification>

  <commit>
    ```bash
    git add -A
    git commit -m "feat(security): BFF OAuth2 session + Business Service JWT resource server

    - Business Service: JWT Bearer token validation via spring-oauth2-resource-server
      - ResourceServerSecurityConfig: stateless, CSRF disabled, JWKS auto-discovery from Keycloak
      - SecurityHelper: reads JwtAuthenticationToken, syncs AppUser from JWT claims (sub, email, etc.)
      - DevSecurityConfig: permitAll, dummy user auto-created on startup
    - BFF: OAuth2 session + token forwarding
      - SecurityConfig: oauth2Login, session cookie (HttpOnly/SameSite), CSRF (SPA cookie style)
      - BffConfig: DefaultOAuth2AuthorizedClientManager with refreshToken provider
      - private_key_jwt: JwtClientAuthenticationParametersConverter wired into both
        the auth-code and refresh-token RestClient*TokenResponseClient beans —
        every token-endpoint call carries a signed client_assertion (no shared secret)
      - JwksController publishes the public half at /.well-known/jwks.json (Keycloak fetches it)
      - RestClient interceptor: attaches Bearer access_token on every Business Service call
      - Automatic token refresh: offline_access scope + DefaultOAuth2AuthorizedClientManager
    - Keycloak realm: access_token TTL 5min, refresh_token 30min, test user demo/demo123
    - Dev mode: Business Service only (no BFF, no Keycloak needed)"
    ```
  </commit>
</task>
