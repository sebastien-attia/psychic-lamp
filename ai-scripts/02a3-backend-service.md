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

  <role>You are a senior Java architect implementing use case services (pure Java), web adapters, and the BFF Spring Cloud Gateway route table.</role>

  <context>
    <project>The Boat App — Business Service domain + Spring Cloud Gateway BFF</project>
    <existing-code>Domain models, ports, persistence adapter from Step 02A.2. Read them.</existing-code>
    <hexagonal-rule>
      Business Service: domain.service implements domain.port.in interfaces. Pure Java. Zero Spring.
      infrastructure.service.BoatApplicationService is the Spring @Transactional bridge.
      adapter.in.web.BoatController is the HTTP adapter that depends on infrastructure.service only.

      BFF (Spring Cloud Gateway Server Web MVC 5.0.1): proxying to the
      Business Service is DECLARATIVE in `bff/src/main/resources/application-routes.yml`,
      not Java code. There is no BoatController in the BFF, no BoatBffService,
      no outbound BusinessServiceClient — those would be a regression. The
      BFF still has Java for: BFF-LOCAL endpoints (AuthController for /api/me,
      JwksController, GlobalExceptionHandler), OAuth2 wiring (BffConfig +
      SecurityConfig), and the SCG response filter
      (ScgUpstreamFailureFilter). No @Transactional in BFF. No JPA in BFF.
    </hexagonal-rule>
    <logging-rule>
      EVERY class that catches or handles exceptions MUST log them. Rules:
      1. Logger declaration: private static final Logger log = LoggerFactory.getLogger(ClassName.class)
         Use SLF4J only (org.slf4j.Logger + LoggerFactory). No Lombok @Slf4j.
      2. Log levels:
         - ERROR: unexpected/unhandled exceptions (5xx) — always pass exception as last argument
         - WARN: expected business/client exceptions (4xx) — no stack trace, include context
      3. Mandatory context: HTTP method, path, userId, entity IDs, messages.
      4. Never log sensitive data: tokens, passwords, full request bodies.
      5. Use parameterised SLF4J placeholders ({}) — never string concatenation.
    </logging-rule>
  </context>

  <!-- ═══════════════════════════════════════════════════════════════════════ -->
  <!-- PART A: BUSINESS SERVICE                                               -->
  <!-- ═══════════════════════════════════════════════════════════════════════ -->

  <instructions-business-service>
    <step order="1">
      Implement DOMAIN SERVICES (business-service/domain.service) — PURE JAVA (zero Spring imports):

      BoatDomainService.java (implements ManageBoatsUseCase):
      - Constructor: BoatRepositoryPort, BoatAuditRepositoryPort, SyntacticValidator, SemanticValidator
      - listBoats(ListBoatsQuery): delegates to repository with page/size/sortBy/sortDir/search
      - getBoat(GetBoatQuery): throws BoatNotFoundException if absent
      - createBoat(CreateBoatCommand):
          1. Validate (syntactic + semantic). If errors → return ServiceResponse.failure(messages)
          2. Create Boat (id=UUID.randomUUID(), createdAt=OffsetDateTime.now(ZoneOffset.UTC), version=0)
          3. Save boat, create BoatAudit(CREATED, performedByUserId=command.performedBy().value()), save audit
          4. Return ServiceResponse.success(savedBoat)
      - updateBoat(UpdateBoatCommand):
          1. Validate first (before any DB hit). If errors → return ServiceResponse.failure(messages)
          2. Fetch boat (throw BoatNotFoundException if absent)
          3. If boat.version != command.expectedVersion() → throw ConcurrentModificationException
          4. Update, save, audit(UPDATED), return ServiceResponse.success(updatedBoat)
      - deleteBoat(DeleteBoatCommand): audit(DELETED) BEFORE delete, then deleteById
      NO @Service, NO @Transactional.

      UserDomainService.java (implements GetUserUseCase):
      - syncUser(SyncUserCommand): find by keycloakId, create or update, save
      - getUserByKeycloakId(String): find or throw

      Domain exceptions (domain.exception, pure Java):
      - BoatNotFoundException extends RuntimeException
      - ConcurrentModificationException extends RuntimeException
      - ValidationFailureException extends RuntimeException (carries List&lt;ValidationMessage&gt;):
          Bridge exception: thrown by BoatApplicationService when domain returns hasErrors()=true.
          Spring @Transactional rolls back automatically on RuntimeException.
    </step>

    <step order="2">
      Create BoatApplicationService (business-service/infrastructure.service.BoatApplicationService):
      - @Service @Transactional — transaction boundary and bridge to pure-Java domain
      - private static final Logger log = LoggerFactory.getLogger(BoatApplicationService.class) (MANDATORY)
      - Injects ManageBoatsUseCase (wired to BoatDomainService via BeanConfig)

      Methods (delegate to domain, convert ServiceResponse → exceptions):
      - listBoats(page, size, sortBy, sortDir, search) → PageResult&lt;Boat&gt;
      - getBoat(UUID id) → Boat  (BoatNotFoundException propagates)
      - createBoat(String name, String description, UUID userId) → Boat
          If response.hasErrors(): log.warn(...) + throw new ValidationFailureException(messages)
          Else: log.info(...) + return data
      - updateBoat(UUID id, String name, String description, Long expectedVersion, UUID userId) → Boat
          Same pattern
      - deleteBoat(UUID id, UUID userId) → void  (BoatNotFoundException propagates)

      Rules: @Service @Transactional on class, NEVER on controller, mandatory SLF4J logger.
    </step>

    <step order="3">
      DTOs and the controller API interface are GENERATED — do NOT create them by hand.

      The `openapi-generator-maven-plugin` declared in business-service/pom.xml (02a1 step 6)
      produces, on every `./mvnw generate-sources`:
      - `ch.owt.boatapp.adapter.in.web.dto.generated.BoatResponse`
      - `ch.owt.boatapp.adapter.in.web.dto.generated.BoatCreateRequest`
      - `ch.owt.boatapp.adapter.in.web.dto.generated.BoatUpdateRequest`
      - `ch.owt.boatapp.adapter.in.web.dto.generated.PageBoatResponse`
      - `ch.owt.boatapp.adapter.in.web.dto.generated.ProblemDetail`    ← RFC 9457 shape with optional `messages[]` extension
      - `ch.owt.boatapp.adapter.in.web.dto.generated.ValidationMessageResponse`
      - `ch.owt.boatapp.adapter.in.web.generated.BusinessServiceApi` (controller interface)

      NOTE: `ValidationErrorResponse` is NO LONGER generated. Under RFC 9457 the
      single error shape is `ProblemDetail` with an optional `messages` extension
      member; multi-error responses populate that field. See 01-openapi-contract.md.

      The plugin is configured with `useBeanValidation=true` (02a1 step 6), so generated
      request DTOs carry `@NotBlank`, `@Size(min=, max=)`, `@NotNull`, `@Pattern`, etc.
      derived from the OpenAPI `required` / `minLength` / `maxLength` / `pattern`
      constraints. The REST adapter relies on these (via `@Valid`) to enforce syntactic
      validation → HTTP 400. SyntacticValidator / SemanticValidator in the domain remain
      in place as defense-in-depth (see 02a2 step 3, "ROLE OF THE DOMAIN VALIDATORS").

      The plugin is configured with `useResponseEntity=true`, so method signatures on
      `BusinessServiceApi` return `ResponseEntity&lt;BoatResponse&gt;` etc., preserving
      ETag and Location headers.

      If contracts/openapi.yaml changes, re-run `./mvnw generate-sources` (or just
      `./mvnw compile`) to refresh the generated classes.

      Note: no UserInfoResponse in Business Service — the User tag (/api/me) is filtered
      out of this service's codegen (see globalProperties.apis=BusinessService in 02a1).
    </step>

    <step order="4">
      Implement BoatController for Business Service (adapter.in.web.BoatController):
      - @RestController
      - @Validated on the class — required so Jakarta Bean Validation fires on
        @PathVariable/@RequestParam-level constraints (e.g. `@Min(0)` on `page`,
        `@Max(100)` on `size`). Without it, only @RequestBody validation fires.
      - **implements ch.owt.boatapp.adapter.in.web.generated.BusinessServiceApi**
        (the generated interface already carries @RequestMapping, @GetMapping/@PostMapping/etc.,
         @PathVariable, @RequestHeader, @RequestBody, and @RequestParam annotations —
         DO NOT duplicate them on the implementation methods. Method signatures MUST
         match the generated interface exactly; any contract drift is a compile error.)
      - Because `useBeanValidation=true` is set in codegen, the generated interface's
        @RequestBody parameters are already annotated with `@Valid` and the DTO fields
        with `@NotBlank` / `@Size` / `@NotNull` / `@Pattern`. The controller inherits
        these annotations by virtue of implementing the interface — do NOT strip them.
        If you override a method signature for any reason, preserve `@Valid` on the
        body parameter. Failure to do so is a bug: syntactic validation will silently
        stop firing and the domain will receive invalid payloads.
      - Depends on BoatApplicationService + SecurityHelper (from infrastructure.security) + BoatWebMapper
      - NEVER @Transactional on controller
      - Get current user: SecurityHelper.getCurrentAppUserId() (reads JWT sub claim → AppUser.id)

      DTO → DOMAIN COMMAND mapping (small, plain-Java mapper in adapter.in.web.mapper):
      - Do NOT pass generated DTOs (BoatCreateRequest / BoatUpdateRequest) into
        BoatApplicationService. Map them first into domain commands, wrapping
        raw UUIDs into `BoatId` / `UserId` value objects so the domain's
        invariant checks (02a2) fire.
      - Example:
        ```java
        // adapter.in.web.mapper.BoatCommandMapper (hand-written — the project uses no MapStruct anywhere)
        static CreateBoatCommand toCreateCommand(BoatCreateRequest dto, UUID currentUserId) {
            return new CreateBoatCommand(dto.getName(), dto.getDescription(), new UserId(currentUserId));
        }
        static UpdateBoatCommand toUpdateCommand(UUID pathId, Long ifMatch, BoatUpdateRequest dto, UUID currentUserId) {
            return new UpdateBoatCommand(new BoatId(pathId), dto.getName(), dto.getDescription(), ifMatch, new UserId(currentUserId));
        }
        ```
      - The controller calls the mapper, then delegates to BoatApplicationService
        via a command-taking overload (extend BoatApplicationService accordingly).

      Endpoints (inherited signatures from BusinessServiceApi):
      - GET /api/v1/boats → ResponseEntity&lt;PageBoatResponse&gt; 200  (page/size Bean-Validation → 400 on invalid range)
      - GET /api/v1/boats/{id} → ResponseEntity&lt;BoatResponse&gt; 200 + ETag header  (BoatNotFoundException → 404)
      - POST /api/v1/boats → ResponseEntity&lt;BoatResponse&gt; 201 + Location + ETag  (blank name / oversize → 400 via @Valid)
      - PUT /api/v1/boats/{id} → ResponseEntity&lt;BoatResponse&gt; 200 + ETag  (If-Match header required; missing → MissingRequestHeaderException)
      - DELETE /api/v1/boats/{id} → ResponseEntity&lt;Void&gt; 204
    </step>

    <step order="5">
      RFC 9457 PROBLEM-DETAILS SUPPORT (shared by the Global ExceptionHandler in step 5a).

      Create three SUPPORTING ARTIFACTS in adapter.in.web (so they live alongside
      the handler that uses them):

      (a) ProblemTypes.java — typed constants for the problem-type URI registry
          defined in contracts/openapi.yaml (ProblemDetail schema description).
          Handlers MUST reference these constants — never hand-write the URIs.
          ```java
          package ch.owt.boatapp.adapter.in.web;

          import java.net.URI;

          public final class ProblemTypes {
              public static final URI VALIDATION              = URI.create("https://boatapp.owt.ch/problems/validation");
              public static final URI NOT_FOUND               = URI.create("https://boatapp.owt.ch/problems/not-found");
              public static final URI CONCURRENCY_CONFLICT    = URI.create("https://boatapp.owt.ch/problems/concurrency-conflict");
              public static final URI PRECONDITION_REQUIRED   = URI.create("https://boatapp.owt.ch/problems/precondition-required");
              public static final URI INTERNAL                = URI.create("https://boatapp.owt.ch/problems/internal");
              private ProblemTypes() {}
          }
          ```

      (b) JakartaCodeTranslator.java — maps Jakarta Bean Validation constraint
          codes (the last segment of `{jakarta.validation.constraints.X.message}`)
          to stable APPLICATION codes. The wire contract NEVER exposes Jakarta
          constraint names.
          ```java
          package ch.owt.boatapp.adapter.in.web;

          import java.util.Map;

          public final class JakartaCodeTranslator {
              private static final Map<String, String> TO_APP_CODE = Map.ofEntries(
                  Map.entry("NotBlank",  "field.required"),
                  Map.entry("NotNull",   "field.required"),
                  Map.entry("NotEmpty",  "field.required"),
                  Map.entry("Size",      "field.size.invalid"),
                  Map.entry("Min",       "field.range.invalid"),
                  Map.entry("Max",       "field.range.invalid"),
                  Map.entry("Email",     "field.email.invalid"),
                  Map.entry("Pattern",   "field.format.invalid"),
                  Map.entry("Positive",  "field.range.invalid"),
                  Map.entry("PositiveOrZero", "field.range.invalid"),
                  Map.entry("Negative",  "field.range.invalid"),
                  Map.entry("NegativeOrZero", "field.range.invalid"),
                  Map.entry("Digits",    "field.format.invalid"),
                  Map.entry("Past",      "field.range.invalid"),
                  Map.entry("Future",    "field.range.invalid")
              );
              public static String toApplicationCode(String jakartaCode) {
                  return TO_APP_CODE.getOrDefault(jakartaCode, "field.invalid");
              }
              private JakartaCodeTranslator() {}
          }
          ```

      (c) src/main/resources/messages.properties — i18n message keys for
          application codes. Spring Boot auto-wires MessageSource; handlers
          resolve strings against LocaleContextHolder.getLocale() and set
          `Content-Language` on the response.
          ```properties
          # Syntactic / Jakarta-mapped application codes
          field.required={0} is required
          field.size.invalid={0} has an invalid size
          field.range.invalid={0} is out of range
          field.email.invalid={0} is not a valid email
          field.format.invalid={0} has an invalid format
          field.invalid={0} is invalid

          # Semantic / domain codes (extend as new business rules are added)
          boat.name.duplicate=Boat name already exists

          # Request-level codes
          request.body.malformed=Request body is malformed or unreadable

          # Fallback
          internal.error=An unexpected internal error occurred
          ```
          A `messages_de.properties` / `messages_fr.properties` can be added
          later without code changes — that is why we don't concatenate
          messages in Java.
    </step>

    <step order="5a">
      Implement GlobalExceptionHandler for Business Service
      (@RestControllerAdvice in adapter.in.web.GlobalExceptionHandler).

      MANDATORY:
      - private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class)
      - EVERY @ExceptionHandler calls log.warn/log.error (see logging-rule above).
      - EVERY handler returns `ResponseEntity&lt;ProblemDetail&gt;` (Spring's built-in
        `org.springframework.http.ProblemDetail` — RFC 9457 compatible) with
        `Content-Type: application/problem+json` and `Content-Language` headers.
      - EVERY handler calls `.setType(ProblemTypes.X)` and
        `.setInstance(URI.create(request.getRequestURI()))`. No `about:blank`.
      - The `messages` extension is populated via `.setProperty("messages", List.of(...))`
        for syntactic (400) and semantic (422) responses; absent for other statuses.

      Inject MessageSource; resolve titles/details/messages against
      LocaleContextHolder.getLocale(); set Content-Language accordingly.

      Handlers (all mandatory):

      1) MethodArgumentNotValidException → 400 (syntactic, @RequestBody @Valid):
         - Iterate ex.getBindingResult().getFieldErrors().
         - For each FieldError: code = JakartaCodeTranslator.toApplicationCode(fe.getCode());
           field = fe.getField(); message = messageSource.getMessage(code, new Object[]{field}, locale).
         - Build ProblemDetail(400): type=VALIDATION, title="Request validation failed",
           instance=requestURI, property("messages", [{ERROR, code, field, message}, ...]).
         - log.warn("400 validation at {} {} — {} fields invalid", method, path, n).

      2) ConstraintViolationException → 400 (syntactic, @PathVariable/@RequestParam @Validated):
         - Iterate ex.getConstraintViolations().
         - For each ConstraintViolation: derive Jakarta code from
           violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName();
           field = last segment of violation.getPropertyPath();
           message = messageSource.getMessage(...).
         - Build ProblemDetail(400): type=VALIDATION, same shape as (1).

      3) HttpMessageNotReadableException → 400 (malformed JSON / unreadable body):
         - Build ProblemDetail(400): type=VALIDATION, title="Malformed request body",
           detail=messageSource.getMessage("request.body.malformed", ...),
           instance=requestURI,
           property("messages", [{ERROR, "request.body.malformed", null, <message>}]).
         - log.warn("400 malformed body at {} {}", method, path).

      4) ValidationFailureException → 422 (semantic / domain-origin):
         - Build ProblemDetail(422): type=VALIDATION (same URI as 400 — the status
           differentiates the origin, the URI categorises the problem),
           title="Domain validation failed", instance=requestURI,
           property("messages", ex.getMessages() mapped via BoatWebMapper.toWire()).
         - Mapper maps domain ValidationMessage → ValidationMessageResponse:
             severity = same enum (ERROR/WARNING/INFO — no WARN bridge)
             code     = vm.type().applicationCode()  (from MessageType — see 02a2 step 1)
             field    = vm.field()
             message  = messageSource.getMessage(vm.type().applicationCode(), new Object[]{vm.field()}, locale)
         - log.warn("422 domain validation at {} {} — {} messages", method, path, size).

      5) BoatNotFoundException → 404:
         - type=NOT_FOUND, title="Boat not found", detail="No boat with id {id}",
           instance=requestURI. No `messages`.
         - log.warn("404 boat not found at {} {} id={}", method, path, id).

      6) ConcurrentModificationException → 409 (optimistic lock):
         - type=CONCURRENCY_CONFLICT, title="Concurrent modification", detail=...,
           instance=requestURI. No `messages`.
         - log.warn.

      7) MissingRequestHeaderException → 428 (If-Match missing):
         - type=PRECONDITION_REQUIRED, title="If-Match header required",
           detail=messageSource.getMessage("request.header.if-match.required", ...),
           instance=requestURI. No `messages`.
         - log.warn.

      8) Exception (fallback) → 500:
         - type=INTERNAL, title="Internal server error",
           detail="An unexpected error occurred. Reference: {traceId}",
           instance=requestURI. No `messages` (do NOT leak stack trace or
           internal exception messages to the client).
         - log.error(..., ex) — full stack trace in logs.

      Helper: extract userId from SecurityContextHolder using JwtAuthenticationToken.
      In dev profile: authentication is anonymous → use "dev-user" as fallback.
      Include userId in every log line.
    </step>

    <step order="6">
      Create infrastructure.config.BeanConfig for Business Service:
      - @Configuration — wires pure-Java domain beans only
      - @Bean SyntacticValidator, SemanticValidator
      - @Bean ManageBoatsUseCase → new BoatDomainService(boatRepo, auditRepo, syntacticValidator, semanticValidator)
      - @Bean GetUserUseCase → new UserDomainService(userRepo)
      NO @Transactional here — lives on BoatApplicationService (@Service).
    </step>
  </instructions-business-service>

  <!-- ═══════════════════════════════════════════════════════════════════════ -->
  <!-- PART B: BFF (Spring Cloud Gateway Server Web MVC)                       -->
  <!-- ═══════════════════════════════════════════════════════════════════════ -->

  <instructions-bff>
    <step order="7">
      The BFF proxies via Spring Cloud Gateway Server Web MVC (5.0.1, Spring
      Cloud 2025.1.1). Routes are DECLARATIVE — there is no
      BusinessServiceClient, no `@HttpExchange` interface, no
      `BoatBffService`, no `BoatController` in the BFF. Anyone re-introducing
      those is reverting the SCG migration.

      Required pom dependency (already in bff/pom.xml from 02a1 — verify):
        - `org.springframework.cloud:spring-cloud-starter-gateway-server-webmvc`
          (BOM-managed via `spring-cloud-dependencies:2025.1.1`).
        - `spring-boot-starter-validation` is REMOVED — Bean Validation now
          lives only on the Business Service. The BFF is a transparent
          gateway and does not re-validate request bodies.

      The OpenAPI codegen on the BFF side is narrowed to the BFF-LOCAL DTOs
      only (see step 12 below): `UserInfoResponse`, `Severity`, and
      `ValidationMessageResponse`. The `BusinessService` tag is no longer
      generated — there is no outbound HTTP-Interface client to feed.
    </step>

    <step order="8">
      Declare the SCG route table in `bff/src/main/resources/application-routes.yml`:

        ```yaml
        spring:
          cloud:
            gateway:
              server:
                webmvc:
                  routes:
                    - id: boats-api
                      uri: ${business-service.url}
                      predicates:
                        - Path=/api/v1/boats/{*subpath}
                      filters:
                        - TokenRelay=keycloak
        ```

      Notes:
      - Path predicate uses the named-capture wildcard `{*subpath}` because
        Spring's PathPattern only formally guarantees zero-segment matching
        for that form. It cleanly covers `/api/v1/boats`, `/api/v1/boats/`,
        and `/api/v1/boats/{id}` from one declaration.
      - The `TokenRelay=keycloak` filter resolves the
        `OAuth2AuthorizedClientManager` bean (defined in BffConfig — see 02a4)
        per request via `getApplicationContext(request).getBean(...)` and
        attaches the user's access token as a `Bearer` header. Refresh is
        transparent — no extra wiring beyond the manager bean is needed.
      - `uri: ${business-service.url}` resolves from the active profile's
        property file.

      Wire the import from each non-dev profile YAML so dev never registers
      TokenRelay-bearing routes (BffConfig is `@Profile("!dev")` and would
      have no manager bean):

        ```yaml
        # application-local-intg.yml / application-staging.yml / application-prod.yml
        spring:
          config:
            import: classpath:application-routes.yml
        ```

      Do NOT put the import in `application.yml` — the dev profile would
      pick it up and TokenRelay would fail at request time.
    </step>

    <step order="9">
      Pin the BFF's outbound RestClient to HTTP/1.1.
      Spring Cloud Gateway MVC's `RestClientProxyExchange` builds its
      RestClient from Spring Boot's autoconfigured builder. The default
      `JdkClientHttpRequestFactory` on Java 25 negotiates HTTP/2 cleartext
      (h2c) on plaintext upstreams. WireMock 3.x and many Container Apps
      ingresses are HTTP/1.1 only and surface this as
      `Received RST_STREAM: Stream cancelled`. Provide a
      `ClientHttpRequestFactory` bean that SCG's `gatewayRestClientCustomizer`
      consumes via its `ObjectProvider`:

        ```java
        // bff/.../infrastructure/web/Http11RestClientCustomizer.java
        @Configuration
        public class Http11RestClientCustomizer {
            @Bean
            public ClientHttpRequestFactory gatewayHttp11ClientHttpRequestFactory() {
                HttpClient http11 = HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .build();
                return new JdkClientHttpRequestFactory(http11);
            }
        }
        ```

      A naive `RestClientCustomizer` does NOT work here: SCG's own
      `gatewayRestClientCustomizer` runs after yours and overrides the
      request factory unless an explicit `ClientHttpRequestFactory` bean is
      provided.
    </step>

    <step order="10">
      Implement AuthController for the BFF
      (`adapter.in.web.AuthController`) — the only BFF-LOCAL boats-domain
      adjacent endpoint that survives the SCG migration:
      - GET /api/me → returns `ch.owt.boatapp.bff.adapter.in.web.dto.generated.UserInfoResponse`
        (generated from the `User` tag in contracts/openapi.yaml — id, username, email, firstName, lastName).
      - Reads from the current OidcUser in SecurityContext (available after oauth2Login).
      - When the principal is anonymous or non-OIDC (dev fallback or test
        with `@WithMockUser`), returns the dummy dev user info.

      `/api/me` is BFF-local (NOT proxied via SCG) because the user profile
      lives only in the BFF's session. SCG never sees it. The endpoint is
      under `/api/**` so the security filter chain still applies — anonymous
      callers receive 401 from `RestAuthenticationEntryPoint`, NOT the dev
      fallback. The dev fallback only fires for an authenticated principal
      that is not an `OidcUser`.

      Note: `UserInfoResponse` is generated from the OpenAPI spec on the BFF
      side only. The Business Service's codegen filters out the User tag.
    </step>

    <step order="11">
      Implement GlobalExceptionHandler for BFF
      (`@RestControllerAdvice` in `bff/adapter/in/web/GlobalExceptionHandler`).
      MANDATORY: `private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class)` + every handler logs.

      Since the SCG migration the BFF only handles errors raised by its own
      locally-served endpoints (`/api/me`, `/.well-known/jwks.json`,
      `/api/logout`, `/actuator/**`). Upstream errors from the Business
      Service no longer flow through this advice — Spring Cloud Gateway
      forwards the BS's RFC 9457 response back to the client unchanged.
      Upstream 5xx WITHOUT an RFC 9457 body is rewritten to a 502
      `upstream-failure` envelope by a dedicated SCG response filter (see
      step 11b), NOT by this advice.

      Replicate the Business Service's supporting artifacts on the BFF side
      so any locally-raised error envelope is byte-identical to the BS's:
        - bff/.../adapter/in/web/ProblemTypes.java             (same URI constants — copy verbatim, plus add `UPSTREAM_FAILURE`)
        - bff/.../adapter/in/web/JakartaCodeTranslator.java    (same translation table — copy verbatim)
        - bff/src/main/resources/messages.properties           (same keys — copy verbatim, plus `upstream.failure`)

      Reachability of the validation handlers is narrow now:
      `spring-boot-starter-validation` was removed from the BFF along with
      the SPA-edge `@Valid` contracts, so `MethodArgumentNotValidException`
      and `ConstraintViolationException` are not raised by anything on the
      current BFF surface. Keep them as a defense-in-depth shell so the
      envelope shape stays consistent if a future BFF-local endpoint
      re-introduces Bean Validation. Today only
      `HttpMessageNotReadableException`, `MissingRequestHeaderException`,
      `AuthenticationException`, `NoResourceFoundException`, and the
      `Exception` fallback are reachable.

      Handlers (each MUST log + emit `application/problem+json` + `Content-Language`):
        1) MethodArgumentNotValidException → 400 (type=VALIDATION, messages[])  [shell]
        2) ConstraintViolationException    → 400 (type=VALIDATION, messages[])  [shell]
        3) HttpMessageNotReadableException → 400 (type=VALIDATION, messages=[{ERROR, "request.body.malformed"}])
        4) MissingRequestHeaderException   → 428 (type=PRECONDITION_REQUIRED)
        5) AuthenticationException         → 401 (type=AUTH_REQUIRED)
        6) NoResourceFoundException        → 404 (type=NOT_FOUND)
        7) Exception fallback              → 500 (type=INTERNAL, never leak ex.getMessage())

      Do NOT add a `RestClientResponseException` handler — there is no
      outbound `RestClient` left in the BFF (proxying is in SCG config).
      Such a handler would be dead code and a confusing breadcrumb.
    </step>

    <step order="11b">
      Implement `ScgUpstreamFailureFilter` for upstream 5xx without an RFC
      9457 body (e.g. connection reset, plain-text 500). Servlet
      `OncePerRequestFilter` scoped to the SCG-routed prefix `/api/v1/`,
      gated on `@Profile("!dev")`. Wraps the response with
      `ContentCachingResponseWrapper`, lets the SCG handler write into it,
      then inspects the committed status:

      - If the status is &lt; 500 → copy the buffered body to the original
        response untouched.
      - If the status is &ge; 500 AND the content type is already
        `application/problem+json` → copy the buffered body untouched (the
        BS may legitimately emit a 5xx envelope from its own registry;
        rewriting it would lose fidelity).
      - Else (5xx without RFC 9457 body) → reset the response, write a 502
        `application/problem+json` envelope: type=UPSTREAM_FAILURE
        (`https://boatapp.owt.ch/problems/upstream-failure`), title="Upstream service failed",
        detail from `messages.properties` key `upstream.failure`,
        instance=request URI. NEVER leak the upstream body — the BS is an
        internal component.

      MANDATORY: SLF4J Logger field; `log.warn` on every rewrite with
      `(originalStatus, method, requestURI)`.
    </step>

    <step order="12">
      BFF DTOs are GENERATED — do NOT create them by hand. The BFF
      `openapi-generator-maven-plugin` execution in bff/pom.xml is configured
      with `generateApis=false` (no outbound interface generation) and a
      `models` allow-list that emits ONLY:
      - `UserInfoResponse`              ← consumed by AuthController
      - `Severity`                      ← consumed by GlobalExceptionHandler
      - `ValidationMessageResponse`     ← consumed by GlobalExceptionHandler

      The generated package is `ch.owt.boatapp.bff.adapter.in.web.dto.generated`.
      Do NOT add any additional model to the allow-list unless a new
      BFF-LOCAL endpoint genuinely needs it — `BoatResponse`,
      `BoatCreateRequest`, `BoatUpdateRequest`, `PageBoatResponse`, etc.
      are NOT BFF-side concerns under the SCG model.

      The `ch.owt.boatapp.bff.adapter.out.client.generated` package MUST
      remain absent — `BffArchitectureTest` enforces this. Re-introducing
      it is a regression.

      IMPORTANT: Do NOT edit files under dto.generated/ — they are
      overwritten on every build. Change contracts/openapi.yaml and re-run
      `./mvnw generate-sources`.
    </step>
  </instructions-bff>

  <verification>
    Run the phase's verification script — it compiles both services,
    confirms business-service controller implements the generated
    BusinessServiceApi interface, the BFF declares
    `spring-cloud-starter-gateway-server-webmvc` and the TokenRelay route
    in `application-routes.yml`, the BFF's `adapter.out.client` package and
    `*BffService` classes are absent (regression guards), ETag/409/audit
    logic is present in business-service, and `generate-sources` is clean
    (no spec drift):
    ```bash
    ai-scripts/checks/02a3/run.sh .
    ```
    All `fail` items must be green before `<commit>`.
    Human-only checks live in `ai-scripts/checks/02a3/human.md`.
  </verification>

  <commit>
    ```bash
    git add -A
    git commit -m "feat: Business Service domain services + BFF Spring Cloud Gateway routes (RFC 9457 envelope)

    Business Service:
    - DTOs + BusinessServiceApi interface generated from contracts/openapi.yaml
      (openapi-generator-maven-plugin, useBeanValidation=true, useResponseEntity=true)
    - BoatDomainService: pure Java, SyntacticValidator + SemanticValidator as
      defense-in-depth; primary syntactic gate is Jakarta Bean Validation at the adapter
    - BoatApplicationService: @Service @Transactional bridge, throws ValidationFailureException
    - BoatController implements BusinessServiceApi with @Valid @RequestBody and @Validated;
      DTO → domain command mapper (BoatCommandMapper) wraps raw UUIDs in BoatId/UserId
    - GlobalExceptionHandler: RFC 9457 ProblemDetail for every error;
      populated type/instance/Content-Language; handlers for MethodArgumentNotValidException,
      ConstraintViolationException, HttpMessageNotReadableException → 400;
      ValidationFailureException → 422; BoatNotFoundException → 404;
      ConcurrentModificationException → 409; MissingRequestHeaderException → 428; fallback → 500
    - ProblemTypes constants + JakartaCodeTranslator + messages.properties (i18n-ready)

    BFF (Spring Cloud Gateway Server Web MVC 5.0.1):
    - application-routes.yml declares the boats-api route
      (Path=/api/v1/boats/{*subpath}, TokenRelay=keycloak, uri=\${business-service.url}),
      imported from each non-dev profile YAML
    - Http11RestClientCustomizer pins SCG's outbound RestClient to HTTP/1.1
      via a ClientHttpRequestFactory bean (gatewayRestClientCustomizer picks it up)
    - AuthController: GET /api/me from OidcUser session, returns generated UserInfoResponse
    - GlobalExceptionHandler: RFC 9457 envelope for BFF-local handlers
      (validation handlers kept as defense-in-depth shell)
    - ScgUpstreamFailureFilter: rewrites upstream 5xx without RFC 9457 body
      to a 502 upstream-failure envelope; 4xx and RFC-9457-bearing 5xx are pass-through
    - No BoatController, no BoatBffService, no BusinessServiceClient — proxying is YAML"
    ```
  </commit>
</task>
