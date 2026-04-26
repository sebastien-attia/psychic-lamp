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

  <role>You are a senior QA architect writing ArchUnit architecture tests and integration tests for two Spring Boot services.</role>

  <context>
    <project>The Boat App — ArchUnit + integration tests for BFF and Business Service</project>
    <existing-code>Full hexagonal backend from Steps 02A.1-02A.4. Read all packages in both services.</existing-code>
    <key-difference-from-monolith>
      Business Service is a stateless JWT resource server. Tests use jwt() post-processor
      (SecurityMockMvcRequestPostProcessors.jwt()), NOT oidcLogin() — there is no session in Business Service.
      No Keycloak container needed for Business Service tests (jwt() bypasses token validation entirely).

      BFF tests are layered:
      - Most BFF tests use a Mockito @MockitoBean for `OAuth2AuthorizedClientManager`
        and a JDK `com.sun.net.httpserver.HttpServer` (HTTP/1.1 only) as the
        upstream Business Service stub, avoiding the JDK 25 + WireMock h2c
        stream-cancel hazard.
      - WireMock stays available via `BffIntegrationTestBase` for tests that
        hit the BFF directly (CsrfIntegrationTest, SecurityBffIntegrationTest)
        — these bypass SCG so the h2c hazard does not apply.
      - One standalone `KeycloakOAuthFlowIntegrationTest` runs Testcontainers
        Keycloak (real OAuth2 flow) to catch issuer / realm-import / JWT
        signing regressions; the rest of the BFF suite stays fast with mocks.
    </key-difference-from-monolith>
  </context>

  <!-- ═══════════════════════════════════════════════════════════════════════ -->
  <!-- PART A: BUSINESS SERVICE TESTS                                          -->
  <!-- ═══════════════════════════════════════════════════════════════════════ -->

  <instructions-business-service>
    <step order="1">
      Create ArchUnit hexagonal tests for Business Service
      (business-service/src/test/java/ch/owt/boatapp/architecture/HexagonalArchitectureTest.java):

      ```java
      @AnalyzeClasses(packages = "ch.owt.boatapp", importOptions = ImportOption.DoNotIncludeTests.class)
      class HexagonalArchitectureTest {

          // Domain must be framework-free
          @ArchTest
          static final ArchRule domain_model_should_not_depend_on_spring =
              noClasses().that().resideInAPackage("..domain.model..")
              .should().dependOnClassesThat().resideInAnyPackage(
                  "org.springframework..", "jakarta..", "javax..",
                  "..adapter..", "..infrastructure.."
              ).as("Domain model must be pure Java — no Spring/Jakarta imports");

          // Stronger: Jakarta Bean Validation annotations are forbidden anywhere in the domain.
          // Syntactic validation lives at the REST adapter (Bean Validation on generated DTOs).
          // Value-object invariants live in compact constructors using plain
          // IllegalArgumentException — not @NotNull/@Size.
          @ArchTest
          static final ArchRule domain_must_not_import_jakarta_validation =
              noClasses().that().resideInAPackage("..domain..")
              .should().dependOnClassesThat().resideInAPackage("jakarta.validation..")
              .as("Domain packages must not import jakarta.validation — Bean Validation is an adapter concern");

          @ArchTest
          static final ArchRule domain_ports_should_not_depend_on_spring =
              noClasses().that().resideInAPackage("..domain.port..")
              .should().dependOnClassesThat().resideInAnyPackage(
                  "org.springframework..", "jakarta..", "javax..",
                  "..adapter..", "..infrastructure.."
              ).as("Domain ports must be pure Java interfaces");

          @ArchTest
          static final ArchRule domain_services_should_not_depend_on_spring =
              noClasses().that().resideInAPackage("..domain.service..")
              .should().dependOnClassesThat().resideInAnyPackage(
                  "org.springframework..", "jakarta..", "javax..",
                  "..adapter..", "..infrastructure.."
              ).as("Domain services must be pure Java");

          @ArchTest
          static final ArchRule domain_services_should_only_depend_on_domain =
              classes().that().resideInAPackage("..domain.service..")
              .should().onlyDependOnClassesThat().resideInAnyPackage(
                  "..domain..", "java..", "org.slf4j.."
              ).as("Domain services only depend on domain packages + java stdlib");

          // Adapter direction
          @ArchTest
          static final ArchRule web_adapter_should_depend_on_application_service =
              classes().that().resideInAPackage("..adapter.in.web..")
              .should().onlyDependOnClassesThat().resideInAnyPackage(
                  "..adapter.in.web..", "..domain.model..", "..infrastructure.service..",
                  "..infrastructure.security..",
                  "org.springframework..", "jakarta..", "java..", "org.slf4j.."
              ).as("Web adapter depends on infrastructure.service (application services) and SecurityHelper only");

          @ArchTest
          static final ArchRule persistence_adapter_should_implement_outbound_ports =
              classes().that().resideInAPackage("..adapter.out.persistence..")
              .and().areAnnotatedWith(org.springframework.stereotype.Repository.class)
              .should().implement(resideInAPackage("..domain.port.out.."))
              .as("Persistence adapters must implement outbound port interfaces");

          @ArchTest
          static final ArchRule domain_should_not_depend_on_adapters =
              noClasses().that().resideInAPackage("..domain..")
              .should().dependOnClassesThat().resideInAnyPackage(
                  "..adapter..", "..infrastructure.."
              ).as("Domain must never depend on adapters or infrastructure");

          // @Transactional placement
          @ArchTest
          static final ArchRule transactional_forbidden_on_controller_classes =
              noClasses()
                  .that().areAnnotatedWith(org.springframework.stereotype.Controller.class)
                  .or().areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
                  .should().beAnnotatedWith(org.springframework.transaction.annotation.Transactional.class)
                  .because("@Transactional must never appear on @Controller/@RestController classes");

          @ArchTest
          static final ArchRule transactional_forbidden_on_controller_methods =
              noMethods()
                  .that().areDeclaredInClassesThat().areAnnotatedWith(org.springframework.stereotype.Controller.class)
                  .or().areDeclaredInClassesThat().areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
                  .should().beAnnotatedWith(org.springframework.transaction.annotation.Transactional.class)
                  .because("@Transactional must never appear on controller methods");

          // Logging enforcement
          @ArchTest
          static final ArchRule controller_advice_must_have_logger =
              classes()
                  .that().areAnnotatedWith(ControllerAdvice.class)
                  .should(haveAFieldOfType(org.slf4j.Logger.class))
                  .because("@ControllerAdvice must declare a private static final SLF4J Logger field");

          @ArchTest
          static final ArchRule exception_handlers_must_log =
              methods()
                  .that().areAnnotatedWith(ExceptionHandler.class)
                  .should(callALoggerMethod())
                  .because("Every @ExceptionHandler must call a Logger method");

          // Business Service must NOT import oauth2-client (it is a resource server only)
          @ArchTest
          static final ArchRule no_oauth2_client_imports =
              noClasses().that().resideInAPackage("ch.owt.boatapp..")
              .should().dependOnClassesThat()
              .resideInAPackage("org.springframework.security.oauth2.client..")
              .as("Business Service must only use oauth2-resource-server, never oauth2-client");

          // Onion architecture (built-in ArchUnit check)
          @ArchTest
          static final ArchRule onion_architecture = Architectures.onionArchitecture()
              .domainModels("..domain.model..")
              .domainServices("..domain.service..")
              .applicationServices("..domain.port..")
              .adapter("web", "..adapter.in.web..")
              .adapter("persistence", "..adapter.out.persistence..")
              .withOptionalLayers(true);
      }
      ```
    </step>

    <step order="2">
      Create NamingConventionTest.java for Business Service:
      - Controllers: suffixed "Controller"
      - Domain services: suffixed "DomainService"
      - Spring application services (infrastructure.service): suffixed "ApplicationService"
      - Repository adapters: suffixed "RepositoryAdapter"
      - JPA entities: suffixed "JpaEntity"
      - Ports: must be interfaces
      - @Service classes must reside in "..infrastructure.service.."
      - Command objects in domain.port.in: suffixed "Command", must be records
      - Query objects in domain.port.in: suffixed "Query", must be records
      - Use case interface methods must not have more than 1 primitive/UUID/String parameter (use Command/Query)
    </step>

    <step order="3">
      Create integration tests for Business Service
      (business-service/src/test/java/ch/owt/boatapp/integration/).

      AbstractIntegrationTest (base — JWT auth, NO Keycloak container):
      - @SpringBootTest(webEnvironment = RANDOM_PORT) + @Testcontainers
      - @ActiveProfiles("local-intg")
      - @Container static PostgreSQLContainer — shared, Testcontainers reuse
      - DynamicPropertySource: override spring.datasource.url
      - Auth: use SecurityMockMvcRequestPostProcessors.jwt() — this bypasses JWT validation entirely
        (no real Keycloak needed for Business Service integration tests)
      - @BeforeEach: pre-create test AppUser via direct repository call or via GetUserUseCase

      Standard JWT mock setup for tests:
      ```java
      // In test methods or @BeforeEach:
      mockMvc.perform(get("/api/v1/boats")
          .with(jwt().jwt(j -> j
              .subject("test-keycloak-id")
              .claim("preferred_username", "testuser")
              .claim("email", "test@example.com")
              .claim("given_name", "Test")
              .claim("family_name", "User"))))
          .andExpect(status().isOk());
      ```

      BoatControllerIntegrationTest extends AbstractIntegrationTest:
      - All CRUD happy paths + error paths (400, 404, 409, 422, 428)
      - ETag/If-Match optimistic locking
      - Pagination + search
      - Auth: all requests use jwt() post-processor

      ValidationAndErrorsIntegrationTest extends AbstractIntegrationTest (NEW — RFC 9457 coverage):
      Every assertion must check the FULL envelope: Content-Type, Content-Language,
      populated `type` (drawn from the problem-type URI registry — no `about:blank`),
      `title`, `status`, `instance` (equal to the request path), and (for 400/422)
      a populated `messages[]`.

      Shared helper (local to the test class):
        private static final String VALIDATION_TYPE = "https://boatapp.owt.ch/problems/validation";
        private static final String NOT_FOUND_TYPE  = "https://boatapp.owt.ch/problems/not-found";
        private static final String CONFLICT_TYPE   = "https://boatapp.owt.ch/problems/concurrency-conflict";
        private static final String PRECOND_TYPE    = "https://boatapp.owt.ch/problems/precondition-required";
        private static final String INTERNAL_TYPE   = "https://boatapp.owt.ch/problems/internal";

      Test methods (at least these):
      - `postBlankName_returns400_withFieldRequired()`
          POST /api/v1/boats with body `{"name":""}` →
          status 400, Content-Type "application/problem+json", Content-Language header non-null,
          `$.type == VALIDATION_TYPE`, `$.status == 400`, `$.title == "Request validation failed"`,
          `$.instance == "/api/v1/boats"`,
          `$.messages[0].severity == "ERROR"`, `$.messages[0].code == "field.required"`,
          `$.messages[0].field == "name"`.
      - `postNameOver64_returns400_withSizeInvalid()`
          POST with a 65-char name → 400, code `field.size.invalid`.
      - `postOversizeDescription_returns400_withSizeInvalid()`
          POST with a 257-char description → 400, code `field.size.invalid`, field `description`.
      - `postMalformedJson_returns400_withBodyMalformed()`
          POST with `{`  (invalid JSON) → 400, messages[0].code == `request.body.malformed`.
      - `getListWithInvalidPage_returns400()`
          GET /api/v1/boats?page=-1 → 400, code `field.range.invalid`.
      - `semanticDomainFailure_returns422_withSameTypeUri()`
          Trigger a domain-origin ValidationFailureException (seed a rule in
          SemanticValidator for the test — e.g. refuse name equal to "FORBIDDEN") →
          422, `$.type == VALIDATION_TYPE`, `$.instance == request path`,
          `$.messages` preserves severities.
      - `getMissingBoat_returns404_withNotFoundType()`
          GET /api/v1/boats/{random-uuid} → 404, `$.type == NOT_FOUND_TYPE`, no `messages`.
      - `putWithoutIfMatch_returns428_withPreconditionType()`
          PUT without If-Match header → 428, `$.type == PRECOND_TYPE`.
      - `putWithStaleVersion_returns409_withConflictType()`
          PUT with stale If-Match → 409, `$.type == CONFLICT_TYPE`.
      - `unhandledException_returns500_withInternalType()`
          Force an unhandled exception via a test-only endpoint or a mocked bean
          (use @MockitoBean on BoatApplicationService to throw a runtime exception) →
          500, `$.type == INTERNAL_TYPE`, no leaked stack trace in the body.

      All tests MUST also assert the response body does NOT contain
      `about:blank` anywhere (regression guard for the RFC 9457 compliance rule).

      OptimisticLockingIntegrationTest extends AbstractIntegrationTest:
      - Create, update (v0→v1), stale update (v0 again) → 409

      AuditIntegrationTest extends AbstractIntegrationTest:
      - Create→Update→Delete, verify 3 BOAT_AUDIT records with correct FK to APP_USER
      - AppUser.id is derived from JWT sub claim ("test-keycloak-id")

      UserSyncFromJwtTest extends AbstractIntegrationTest:
      - Replaces UserSyncIntegrationTest (no real Keycloak needed — jwt() provides claims)
      - Perform GET /api/v1/boats with jwt() carrying claims (sub, preferred_username, email, etc.)
      - Verify AppUser is created in APP_USER table with correct keycloakId, email, firstName, lastName
      - Perform same request again → verify lastLogin is updated, no duplicate record

      SecurityIntegrationTest extends AbstractIntegrationTest:
      - GET /api/v1/boats without Bearer token → 401
      - GET /api/v1/boats with jwt() → 200
      - GET /actuator/health without token → 200 (public endpoint)

      DevModeTest:
      - @SpringBootTest(webEnvironment = RANDOM_PORT) + @Testcontainers
      - @ActiveProfiles("dev") — auth bypass (permitAll), no JWT validation
      - @Container static PostgreSQLContainer
      - Verify dummy AppUser auto-created on startup (query APP_USER via JdbcTemplate)
      - Verify GET /api/v1/boats returns 200 without any auth token
      - Verify POST /api/v1/boats {"name":"Test"} returns 201 without auth token
      - Verify BOAT_AUDIT record has performedByUserId = dummy user's id

      Tests REMOVED from Business Service (moved to BFF or no longer applicable):
      - SessionPersistenceIntegrationTest (no session in stateless resource server)
      - CsrfIntegrationTest (CSRF disabled in resource server)
      - UserSyncIntegrationTest with real Keycloak OAuth2 flow (moved to BFF)
    </step>
  </instructions-business-service>

  <!-- ═══════════════════════════════════════════════════════════════════════ -->
  <!-- PART B: BFF TESTS                                                       -->
  <!-- ═══════════════════════════════════════════════════════════════════════ -->

  <instructions-bff>
    <step order="4">
      Create ArchUnit tests for BFF
      (bff/src/test/java/ch/owt/boatapp/bff/architecture/BffArchitectureTest.java).

      The BFF's architecture rules (no domain layer, no JPA, no outbound
      HTTP-Interface client — proxying lives in SCG config). The deleted
      `adapter.out.client` package and `*BffService` classes MUST NOT come
      back; the route YAML MUST be on the classpath:

      ```java
      @AnalyzeClasses(packages = "ch.owt.boatapp.bff", importOptions = ImportOption.DoNotIncludeTests.class)
      class BffArchitectureTest {

          // No JPA in BFF
          @ArchTest
          static final ArchRule no_jpa_in_bff =
              noClasses().that().resideInAPackage("ch.owt.boatapp.bff..")
              .should().dependOnClassesThat().resideInAPackage("jakarta.persistence..")
              .as("BFF must never import JPA — it has no database access of its own");

          // No @Transactional in BFF
          @ArchTest
          static final ArchRule no_transactional_in_bff =
              noClasses().that().resideInAPackage("ch.owt.boatapp.bff..")
              .should().beAnnotatedWith(org.springframework.transaction.annotation.Transactional.class)
              .as("@Transactional is forbidden in BFF — BFF has no database transactions");

          @ArchTest
          static final ArchRule no_transactional_methods_in_bff =
              noMethods().that().areDeclaredInClassesThat().resideInAPackage("ch.owt.boatapp.bff..")
              .should().beAnnotatedWith(org.springframework.transaction.annotation.Transactional.class)
              .as("@Transactional methods are forbidden in BFF");

          // The deleted outbound HTTP-Interface client must not come back —
          // proxying lives in application-routes.yml under SCG, not Java.
          @ArchTest
          static final ArchRule no_outbound_client_package =
              noClasses().that().resideInAPackage("ch.owt.boatapp.bff..")
              .should().resideInAPackage("..bff.adapter.out.client..")
              .as("BFF must not (re-)introduce an outbound HTTP client — proxying lives in SCG config");

          // Forbid hand-rolled outbound HTTP via RestTemplate. KeycloakServerSideLogoutHandler
          // legitimately uses RestClient for the back-channel logout call and is exempted by name.
          @ArchTest
          static final ArchRule no_resttemplate_in_bff =
              noClasses().that().resideInAPackage("ch.owt.boatapp.bff..")
                       .and().haveSimpleNameNotEndingWith("KeycloakServerSideLogoutHandler")
              .should().dependOnClassesThat().haveFullyQualifiedName("org.springframework.web.client.RestTemplate")
              .as("RestTemplate must not appear in the BFF — proxying to BS is owned by SCG config");

          // Logging enforcement
          @ArchTest
          static final ArchRule controller_advice_must_have_logger =
              classes().that().areAnnotatedWith(RestControllerAdvice.class)
              .should(LoggerArchConditions.haveAFieldOfType(org.slf4j.Logger.class))
              .because("@RestControllerAdvice must declare a private static final SLF4J Logger");

          @ArchTest
          static final ArchRule exception_handlers_must_call_logger =
              methods().that().areAnnotatedWith(ExceptionHandler.class)
              .should(LoggerArchConditions.callALoggerMethod())
              .because("Every @ExceptionHandler must emit a log record");

          // The SCG route table is the source of truth for all proxying;
          // anchor a build-time check that the file is on the classpath.
          @ArchTest
          static final ArchRule scg_route_table_must_exist =
              classes().should(routeYamlOnClasspath())
              .because("application-routes.yml must be on the classpath — it is the SCG route table");

          // routeYamlOnClasspath() is a small custom ArchCondition that opens
          // the classloader's resources("application-routes.yml") and checks
          // hasMoreElements(); fires once per analysis run.
      }
      ```
    </step>

    <step order="5">
      Create BFF integration tests
      (bff/src/test/java/ch/owt/boatapp/bff/integration/).

      BffIntegrationTestBase (base):
      - @SpringBootTest(properties = "spring.config.import=classpath:application-routes.yml")
        — required so the SCG routes load even though the test profile does
        not import them by default.
      - @AutoConfigureMockMvc + @Import(TestcontainersConfiguration.class)
      - JVM-shared static WireMockServer (HTTP/1.1 only) for tests that
        proxy through SCG to a stubbed upstream Business Service.
      - @DynamicPropertySource binds `business-service.url` to the WireMock
        port AND generates an ephemeral RSA signing key (so
        `BffConfig.bffSigningJwk` can be created on context refresh — no
        committed PEM material under src/test/resources/).
      - @Container static PostgreSQLContainer wired via @ServiceConnection
        for Spring Session JDBC storage.

      Required BFF integration tests (REPLACES the deleted BoatController /
      BoatBffService / RestClient-interceptor tests — those concepts no
      longer exist):

      ScgRouteRegistrationTest extends BffIntegrationTestBase
        Static-config regression. Inject `GatewayMvcProperties` and assert
        that route `boats-api` exists with predicate
        `Path=/api/v1/boats/{*subpath}`, filter chain contains
        `TokenRelay` configured with `keycloak`, URI is set. Catches YAML
        typos before any traffic is served.

      TokenRelayIntegrationTest extends BffIntegrationTestBase
        Replace the live `OAuth2AuthorizedClientManager` with a Mockito
        @MockitoBean stub that returns a pre-built OAuth2AuthorizedClient
        carrying a synthetic JWT. Drive an authenticated request through
        the SCG route and assert an embedded HTTP/1.1 server (JDK
        com.sun.net.httpserver.HttpServer — NOT WireMock for this test;
        see note below) received exactly one upstream call with
        `Authorization: Bearer <synthetic-jwt>`. Negative path: anonymous
        request → 401 RFC 9457, zero upstream calls, manager never invoked.

      TokenRelayRefreshIntegrationTest extends BffIntegrationTestBase
        Same fixture as TokenRelayIntegrationTest. Stub the manager to
        return token-A on call 1 and token-B on call 2. Drive two
        sequential authenticated requests; assert the upstream observed
        `Bearer token-A` then `Bearer token-B` in order. Proves TokenRelay
        re-resolves the manager per request rather than caching.

      BffStaticContentRegressionTest extends BffIntegrationTestBase
        Regression guard for "BFF must NOT serve the SPA bundle":
        - GET /, /index.html, /assets/foo.js → assert non-2xx (the
          precise status is implementation-detail; today they yield 401
          since the permitAll matchers were dropped).
        - GET /.well-known/jwks.json → 200 with valid JWK Set.
        - GET /actuator/health → 200.
        - GET /api/me with @WithMockUser → 200 dev-fallback payload.
        - GET /api/me anonymous → 401 with application/problem+json.

      ScgErrorEnvelopePassThroughTest extends BffIntegrationTestBase
        Stub the upstream (embedded HttpServer, see note below) to return
        each of: 422 RFC 9457, 404 RFC 9457, 500 plain-text, 503 empty
        body, 500 RFC 9457. Assert:
        - 4xx with RFC 9457 body → forwarded byte-identical (status,
          Content-Type, body).
        - 5xx without RFC 9457 → rewritten by `ScgUpstreamFailureFilter`
          to a 502 envelope with type=`.../upstream-failure` and no leak
          of the upstream body.
        - 5xx WITH RFC 9457 → forwarded byte-identical (the BS may
          legitimately emit a 5xx envelope from its own registry).

      CsrfIntegrationTest extends BffIntegrationTestBase
        - POST /api/v1/boats without XSRF-TOKEN header → 403
        - POST /api/v1/boats with valid XSRF-TOKEN cookie + X-XSRF-TOKEN
          header → 201 (WireMock stub). Stub uses `urlPathEqualTo` (not
          `urlEqualTo`) because SCG forwards the entire request URI
          byte-identically, including the `?_csrf=…` query param that
          MockMvc's csrf() post-processor appends. Production SPAs send
          the CSRF token via header only (no query string).

      SecurityBffIntegrationTest extends BffIntegrationTestBase
        - GET /api/v1/boats without session → 401 RFC 9457
        - GET /actuator/health without session → 200
        - GET /oauth2/authorization/keycloak → 302 redirect to Keycloak

      KeycloakOAuthFlowIntegrationTest (standalone, not extending the base)
        Real Keycloak Testcontainer (quay.io/keycloak/keycloak:26.6.1) +
        realm fixture. Proves the BFF's OAuth2 wiring talks to a real
        Keycloak end-to-end — the only test that catches issuer / realm
        import / JWT-signing regressions. Other BFF tests can stay fast
        with Mockito-stubbed managers.

      KeycloakServerSideLogoutHandlerTest
        Pure Mockito test of the back-channel logout handler.

      WHY a JDK HttpServer instead of WireMock for the SCG-routed tests:
      Spring Cloud Gateway MVC's `RestClientProxyExchange` uses Spring's
      `RestClient`. On Java 25 / Spring Boot 4.0 the JDK HttpClient
      negotiates HTTP/2 cleartext (h2c) on plaintext upstreams. WireMock
      3.x's standalone server's HTTP/2 handling resets streams in this
      combination ("Received RST_STREAM: Stream cancelled"), masking real
      bugs as transport noise. Either:
      (a) install `Http11RestClientCustomizer` (see 02a3 step 9 / 02a4)
          which pins the SCG outbound RestClient to HTTP/1.1, OR
      (b) use the JDK's bundled `com.sun.net.httpserver.HttpServer` which
          is HTTP/1.1 only and has zero dependencies.
      The current codebase does BOTH — (a) for production safety, (b) for
      test isolation. WireMock stays in `BffIntegrationTestBase` for the
      tests that hit the BFF directly without going through an SCG route
      (CsrfIntegrationTest, SecurityBffIntegrationTest).

      DELETED — do not create these (their assumptions are gone):
        - BoatBffService (no service layer for boats anymore)
        - BffShortCircuitIntegrationTest, BffValidationForwardingIntegrationTest
          (asserted local @Valid → 400 short-circuit; Bean Validation moved
          entirely to BS)
        - TokenForwardingTest (asserted RestClient interceptor; replaced
          by TokenRelayIntegrationTest)
        - BffUpstreamFailureIntegrationTest (asserted
          RestClientResponseException → 502 mapping; replaced by
          ScgErrorEnvelopePassThroughTest with the supporting
          ScgUpstreamFailureFilter)
    </step>
  </instructions-bff>

  <verification>
    Run the phase's verification script — it runs `./mvnw verify` on both
    services, confirms ArchUnit tests exist, checks Testcontainers /
    jwt() / WireMock usage, and jacoco is declared:
    ```bash
    ai-scripts/checks/02a5/run.sh .
    ```
    All `fail` items must be green before `<commit>`.
    Human-only checks live in `ai-scripts/checks/02a5/human.md`.
  </verification>

  <commit>
    ```bash
    git add -A
    git commit -m "test: ArchUnit enforcement + integration tests for BFF and Business Service

    Business Service:
    - ArchUnit: domain has ZERO Spring/Jakarta imports (enforced on every build)
    - ArchUnit: jakarta.validation forbidden in ..domain.. (Bean Validation is an adapter concern)
    - ArchUnit: no oauth2-client imports (resource server only)
    - ArchUnit: @Transactional forbidden on controllers, controllers depend on infrastructure.service
    - Integration: jwt() post-processor for auth (no Keycloak container needed)
    - ValidationAndErrorsIntegrationTest: full RFC 9457 envelope coverage —
      400 (syntactic via @Valid), 422 (semantic via ValidationFailureException),
      404/409/428/500 with registry type URIs; asserts populated type/instance/Content-Language
    - UserSyncFromJwtTest: AppUser synced from JWT claims on first request
    - DevModeTest: permits all, dummy user auto-created, no JWT needed

    BFF (Spring Cloud Gateway Server Web MVC):
    - ArchUnit: no JPA, no @Transactional, no adapter.out.client package
      (regression guard), no RestTemplate (except KeycloakServerSideLogoutHandler),
      application-routes.yml on classpath, RestControllerAdvice has Logger,
      every @ExceptionHandler logs.
    - ScgRouteRegistrationTest: route boats-api shape (predicate, filter,
      uri) parsed from application-routes.yml.
    - TokenRelayIntegrationTest: SCG TokenRelay attaches the user's Bearer
      token via OAuth2AuthorizedClientManager (Mockito @MockitoBean),
      anonymous request → 401 RFC 9457 + zero upstream calls.
    - TokenRelayRefreshIntegrationTest: per-request token rotation
      (manager returns A then B → upstream observes both Bearers).
    - BffStaticContentRegressionTest: BFF must NOT serve /, /index.html,
      /assets/**; /jwks.json + /actuator/health + /api/me reachable.
    - ScgErrorEnvelopePassThroughTest: 4xx and 5xx-with-RFC9457 forwarded
      byte-identical; 5xx without RFC 9457 → 502 upstream-failure.
    - CsrfIntegrationTest: SPA cookie-based CSRF protection (urlPathEqualTo).
    - SecurityBffIntegrationTest: 401 envelope on /api/**, 200 on
      /actuator/health, 302 on /oauth2/authorization/keycloak.
    - KeycloakOAuthFlowIntegrationTest: real Testcontainers Keycloak — issuer
      / realm import / JWT signing end-to-end."
    ```
  </commit>
</task>
