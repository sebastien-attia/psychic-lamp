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

      BFF tests require Testcontainers Keycloak (real OAuth2 flow) + WireMock (mock Business Service).
    </key-difference-from-monolith>
  </context>

  <!-- ═══════════════════════════════════════════════════════════════════════ -->
  <!-- PART A: BUSINESS SERVICE TESTS                                          -->
  <!-- ═══════════════════════════════════════════════════════════════════════ -->

  <instructions-business-service>
    <step order="1">
      Create ArchUnit hexagonal tests for Business Service
      (business-service/bootstrap/src/test/java/ch/owt/boatapp/architecture/BusinessServiceArchitectureTest.java
      — they live in the bootstrap submodule because that is the only test
      classpath that sees all four jars at once):

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
          static final ArchRule application_ports_should_not_depend_on_spring =
              noClasses().that().resideInAPackage("..application.port..")
              .should().dependOnClassesThat().resideInAnyPackage(
                  "org.springframework..", "jakarta..", "javax..",
                  "..adapter..", "..infrastructure.."
              ).as("Application ports must be pure Java interfaces");

          @ArchTest
          static final ArchRule domain_services_should_not_depend_on_spring =
              noClasses().that().resideInAPackage("..application.service..")
              .should().dependOnClassesThat().resideInAnyPackage(
                  "org.springframework..", "jakarta..", "javax..",
                  "..adapter..", "..infrastructure.."
              ).as("Domain services must be pure Java");

          @ArchTest
          static final ArchRule domain_services_should_only_depend_on_domain =
              classes().that().resideInAPackage("..application.service..")
              .should().onlyDependOnClassesThat().resideInAnyPackage(
                  "..domain..", "java..", "org.slf4j.."
              ).as("Domain services only depend on domain packages + java stdlib");

          // Adapter direction
          @ArchTest
          static final ArchRule web_adapter_should_depend_on_application_service =
              classes().that().resideInAPackage("..adapter.in.web..")
              .should().onlyDependOnClassesThat().resideInAnyPackage(
                  "..adapter.in.web..", "..domain.model..", "..application.service..",
                  "..infrastructure.security..",
                  "org.springframework..", "jakarta..", "java..", "org.slf4j.."
              ).as("Web adapter depends on application.service (use cases) and SecurityHelper only");

          @ArchTest
          static final ArchRule persistence_adapter_should_implement_outbound_ports =
              classes().that().resideInAPackage("..adapter.out.persistence..")
              .and().areAnnotatedWith(org.springframework.stereotype.Repository.class)
              .should().implement(resideInAPackage("..application.port.out.."))
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

          // Onion architecture (built-in ArchUnit check) — package layout
          // matches the multi-module Maven boundaries.
          @ArchTest
          static final ArchRule onion_architecture = Architectures.onionArchitecture()
              .domainModels("..domain.model..")
              .domainServices("..domain.service..")
              .applicationServices("..application..")
              .adapter("web", "..adapter.in.web..")
              .adapter("persistence", "..adapter.out.persistence..")
              .withOptionalLayers(true);
      }
      ```
    </step>

    <step order="2">
      Create NamingConventionTest.java for Business Service:
      - Controllers: suffixed "Controller"
      - Spring application services (in application.service, annotated @Service): suffixed "ApplicationService"
      - Repository adapters: suffixed "RepositoryAdapter"
      - JPA entities: suffixed "JpaEntity"
      - Ports: must be interfaces
      - @Service classes must reside in "..application.service.."
      - Command objects in application.port.in: suffixed "Command", must be records
      - Query objects in application.port.in: suffixed "Query", must be records
      - Use case interface methods must not have more than 1 primitive/UUID/String parameter (use Command/Query)
    </step>

    <step order="3">
      Create integration tests for Business Service
      (business-service/bootstrap/src/test/java/ch/owt/boatapp/integration/
      — bootstrap is the only submodule with the full Spring Boot test
      classpath: spring-boot-testcontainers, security-test, oauth2-rs-test,
      etc.).

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
      (bff/src/test/java/ch/owt/boatapp/bff/architecture/BffArchitectureTest.java):

      BFF has different architecture rules (no domain layer, no JPA):

      ```java
      @AnalyzeClasses(packages = "ch.owt.boatapp.bff", importOptions = ImportOption.DoNotIncludeTests.class)
      class BffArchitectureTest {

          // No JPA in BFF
          @ArchTest
          static final ArchRule no_jpa_in_bff =
              noClasses().that().resideInAPackage("ch.owt.boatapp.bff..")
              .should().dependOnClassesThat().resideInAPackage("jakarta.persistence..")
              .as("BFF must never import JPA — it has no database access of its own");

          // Controller → Service → Client chain
          @ArchTest
          static final ArchRule bff_controllers_depend_on_service_only =
              classes().that().resideInAPackage("..bff.adapter.in.web..")
              .should().onlyDependOnClassesThat().resideInAnyPackage(
                  "..bff.adapter.in.web..", "..bff.infrastructure.service..",
                  "org.springframework..", "jakarta..", "java..", "org.slf4j.."
              ).as("BFF controllers must depend on infrastructure.service only, not on adapter.out.client directly");

          // No @Transactional in BFF
          @ArchTest
          static final ArchRule no_transactional_in_bff =
              noClasses().that().resideInAPackage("ch.owt.boatapp.bff..")
              .should().beAnnotatedWith(org.springframework.transaction.annotation.Transactional.class)
              .and(noMethods().that().areDeclaredInClassesThat().resideInAPackage("ch.owt.boatapp.bff..")
                  .should().beAnnotatedWith(org.springframework.transaction.annotation.Transactional.class))
              .as("@Transactional is forbidden in BFF — BFF has no database transactions");

          // BusinessServiceClient must be an interface
          @ArchTest
          static final ArchRule business_service_client_must_be_interface =
              classes().that().haveSimpleNameEndingWith("Client")
                       .and().resideInAPackage("..bff.adapter.out.client..")
              .should().beInterfaces()
              .as("BusinessServiceClient must be a Spring HTTP Interface (interface, not class)");

          // Logging enforcement (same as Business Service)
          @ArchTest
          static final ArchRule controller_advice_must_have_logger =
              classes()
                  .that().areAnnotatedWith(ControllerAdvice.class)
                  .should(haveAFieldOfType(org.slf4j.Logger.class))
                  .because("@ControllerAdvice must declare a SLF4J Logger field");

          @ArchTest
          static final ArchRule exception_handlers_must_log =
              methods()
                  .that().areAnnotatedWith(ExceptionHandler.class)
                  .should(callALoggerMethod())
                  .because("Every @ExceptionHandler must call a Logger method");

          // BFF service naming convention
          @ArchTest
          static final ArchRule bff_services_suffixed =
              classes().that().resideInAPackage("..bff.infrastructure.service..")
                       .and().areAnnotatedWith(org.springframework.stereotype.Service.class)
              .should().haveSimpleNameEndingWith("BffService")
              .as("BFF application services must be suffixed BffService");
      }
      ```
    </step>

    <step order="5">
      Create BFF integration tests
      (bff/src/test/java/ch/owt/boatapp/bff/integration/).

      AbstractBffIntegrationTest (base):
      - @SpringBootTest(webEnvironment = RANDOM_PORT) + @Testcontainers
      - @ActiveProfiles("local-intg")
      - @Container static KeycloakContainer (quay.io/keycloak/keycloak:26.6.1)
        - Apply realm config from the canonical source: invoke adorsys/keycloak-config-cli
          in a @BeforeAll hook (Docker run) against the running KeycloakContainer,
          importing infra/keycloak/realm.yaml with IMPORT_VAR_SUBSTITUTION_ENABLED=true
          (so the same source-of-truth YAML used by compose + Ansible is also used
          in tests — no drift).
        - Alternative (if preferred for test speed): ship a pre-materialised
          realm-export.json under business-service/bootstrap/src/test/resources/ AND add a
          test that regenerates it from realm.yaml via config-cli and diffs —
          so drift is still caught on every build.
      - @WireMockTest or @Container WireMockContainer — stubs Business Service responses
      - DynamicPropertySource: override spring.security.oauth2.client.provider.keycloak.issuer-uri
        with Keycloak container URL, and business-service.url with WireMock base URL
      - @Container static PostgreSQLContainer — for Spring Session JDBC storage

      BffBoatControllerTest extends AbstractBffIntegrationTest:
      - GET /api/v1/boats: WireMock stubs Business Service → verify BFF forwards response correctly
      - POST /api/v1/boats: WireMock stubs 201 → verify BFF returns 201 + Location + ETag
      - GET /api/v1/boats/{id}: WireMock stubs 404 ProblemDetail (application/problem+json,
        type=https://boatapp.owt.ch/problems/not-found) → verify BFF forwards body BYTE-IDENTICAL,
        preserving Content-Type, Content-Language, type URI, instance, status.
      - PUT /api/v1/boats/{id}: WireMock stubs 409 ProblemDetail → verify BFF returns 409 unchanged.
      - PUT /api/v1/boats/{id}: WireMock stubs 422 ProblemDetail with populated messages[] →
        verify BFF forwards body verbatim (type=VALIDATION_TYPE, status=422, messages preserved).
      - GET /api/v1/boats: WireMock stubs 500 → verify BFF returns 502 with
        type=https://boatapp.owt.ch/problems/upstream-failure and no leaked upstream body.
      Tests are authenticated with a real Keycloak session (get session cookie via OidcLogin test helper).

      BffValidationTest extends AbstractBffIntegrationTest (NEW — BFF enforces its own syntactic gate):
      - POST /api/v1/boats with `{"name":""}`:
          * expect 400 with RFC 9457 envelope — Content-Type application/problem+json,
            Content-Language header present, type=VALIDATION_TYPE, messages[0].code=="field.required".
          * assert WireMock received ZERO requests (the BFF's @Valid rejected the payload
            BEFORE forwarding). This is the critical guarantee: the BFF is a trust
            boundary, not a pass-through.
      - POST /api/v1/boats with malformed JSON → 400, messages[0].code=="request.body.malformed",
        WireMock received zero requests.
      - GET /api/v1/boats?page=-1 → 400, code `field.range.invalid`, WireMock received zero requests.

      TokenForwardingTest extends AbstractBffIntegrationTest:
      - Log in with demo/demo123 via real Keycloak, get session cookie
      - Perform GET /api/v1/boats (BFF proxies to WireMock)
      - Verify WireMock received request with Authorization: Bearer header
      - Verify the token is a valid JWT with sub=keycloak-user-id

      TokenRefreshTest extends AbstractBffIntegrationTest:
      - Log in with demo/demo123, get session cookie
      - WireMock stub for Business Service: first request returns 401 (simulates expired token),
        second request (after BFF refreshes) returns 200
      - Actually: DefaultOAuth2AuthorizedClientManager refreshes transparently when access_token
        is expired. Test this by:
        1. Log in and store session
        2. Fast-forward time past access_token TTL (mock clock or use very short TTL in test Keycloak config)
        3. Next BFF request should succeed (BFF refreshed token transparently)
        4. WireMock verifies second call received a different (new) Bearer token

      CsrfIntegrationTest extends AbstractBffIntegrationTest:
      - POST /api/v1/boats without XSRF-TOKEN header → 403
      - POST /api/v1/boats with valid XSRF-TOKEN cookie + X-XSRF-TOKEN header → 201 (WireMock stub)

      SessionPersistenceIntegrationTest extends AbstractBffIntegrationTest:
      - Authenticate via real Keycloak, receive SESSION cookie
      - Query SPRING_SESSION table via JdbcTemplate: verify session row exists.
        The BFF Testcontainers PostgreSQLContainer (line 313) runs an isolated
        instance per test class — the BFF's own Liquibase creates SPRING_SESSION
        inside that container (any DB name — the test targets the container's
        default). In runtime envs this schema lives in the `bff_session` database
        owned by role `bff`; in Testcontainers there's no cross-service isolation
        concern, so the test doesn't need to assert a specific DB name.
      - Verify same SESSION cookie accepted on a second request

      SecurityBffIntegrationTest extends AbstractBffIntegrationTest:
      - GET /api/v1/boats without session cookie → 401 JSON (not 302, since /api/** returns 401 for AJAX)
      - GET /actuator/health without session → 200
      - GET /oauth2/authorization/keycloak → 302 redirect to Keycloak
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

    BFF:
    - ArchUnit: no JPA imports, no @Transactional, controller→service→client chain enforced
    - ArchUnit: BusinessServiceClient must be interface (HTTP Interface)
    - Integration: Testcontainers Keycloak (real OAuth2 flow) + WireMock (Business Service mock)
    - BffValidationTest: BFF's own @Valid rejects invalid payloads BEFORE upstream
      (asserts WireMock received zero requests)
    - BffBoatControllerTest: upstream RFC 9457 ProblemDetail forwarded byte-identical
      for 4xx; 5xx wrapped as 502 upstream-failure (upstream body not leaked)
    - TokenForwardingTest: BFF attaches Bearer token to Business Service calls
    - TokenRefreshTest: BFF transparently refreshes expired access_token
    - CsrfIntegrationTest: SPA cookie-based CSRF protection
    - SessionPersistenceIntegrationTest: Spring Session JDBC stores sessions in PostgreSQL"
    ```
  </commit>
</task>
