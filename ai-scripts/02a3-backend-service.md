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

  <role>You are a senior Java architect implementing use case services (pure Java), web adapters, and BFF client layer.</role>

  <context>
    <project>The Boat App — Business Service domain + BFF proxy layer</project>
    <existing-code>Domain models, ports, persistence adapter from Step 02A.2. Read them.</existing-code>
    <hexagonal-rule>
      Business Service: domain.service implements domain.port.in interfaces. Pure Java. Zero Spring.
      infrastructure.service.BoatApplicationService is the Spring @Transactional bridge.
      adapter.in.web.BoatController is the HTTP adapter that depends on infrastructure.service only.

      BFF: adapter.in.web.BoatController depends on infrastructure.service.BoatBffService.
      infrastructure.service.BoatBffService depends on adapter.out.client.BusinessServiceClient.
      No @Transactional in BFF. No JPA in BFF.
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
  <!-- PART B: BFF                                                             -->
  <!-- ═══════════════════════════════════════════════════════════════════════ -->

  <instructions-bff>
    <step order="7">
      BusinessServiceClient is GENERATED — do NOT create it by hand.

      The `openapi-generator-maven-plugin` in bff/pom.xml (02a1 step 2) produces:
      - `ch.owt.boatapp.bff.adapter.out.client.generated.BusinessServiceClient`
          — a Spring @HttpExchange declarative interface with one method per
            BusinessService-tagged operation in contracts/openapi.yaml.
      - `ch.owt.boatapp.bff.adapter.in.web.dto.generated.*`
          — the same set of DTOs listed in step 3 above.

      The plugin is configured with `library=spring-http-interface` and
      `apiNameSuffix=Client`, so the generated interface is literally named
      `BusinessServiceClient` (derived from the `BusinessService` tag). The generated
      signatures carry all Spring annotations (@GetExchange / @PostExchange /
      @PathVariable / @RequestParam / @RequestHeader / @RequestBody) automatically.

      `useResponseEntity=true` ensures headers-returning operations (GET by id, POST,
      PUT) return `ResponseEntity&lt;T&gt;` so the BFF controller can forward ETag and
      Location transparently.

      The base URL and Bearer token injection are configured in BffConfig (step 02A.4),
      which calls `HttpServiceProxyFactory.createClient(BusinessServiceClient.class)`
      against the generated interface.

      HTTP error handling: when Business Service returns 4xx/5xx, RestClient throws
      RestClientResponseException → caught by BFF GlobalExceptionHandler.
    </step>

    <step order="8">
      Implement BoatBffService for BFF (infrastructure.service.BoatBffService in bff/):
      - @Service (NOT @Transactional — BFF has no DB transaction)
      - private static final Logger log = LoggerFactory.getLogger(BoatBffService.class) (MANDATORY)
      - Injects BusinessServiceClient (the GENERATED @HttpExchange interface)
      - Thin orchestrator: delegates all calls to BusinessServiceClient, logs operations.

      Types come from the generated packages:
        ch.owt.boatapp.bff.adapter.in.web.dto.generated.*   (BoatCreateRequest, BoatUpdateRequest,
                                                             BoatResponse, PageBoatResponse, ...)
        ch.owt.boatapp.bff.adapter.out.client.generated.BusinessServiceClient

      Because `useResponseEntity=true` is set in codegen, EVERY client method returns
      `ResponseEntity&lt;T&gt;` (even the list endpoint). The service returns the
      ResponseEntity directly so the controller can forward headers transparently.

        ```java
        public ResponseEntity&lt;PageBoatResponse&gt; listBoats(int page, int size, String sort, String search) {
            return businessServiceClient.listBoats(page, size, sort, search);
        }

        public ResponseEntity&lt;BoatResponse&gt; getBoat(UUID id) {
            return businessServiceClient.getBoat(id);   // ETag header preserved
        }

        public ResponseEntity&lt;BoatResponse&gt; createBoat(BoatCreateRequest request) {
            return businessServiceClient.createBoat(request);   // Location + ETag preserved
        }

        public ResponseEntity&lt;BoatResponse&gt; updateBoat(UUID id, Long ifMatch, BoatUpdateRequest request) {
            return businessServiceClient.updateBoat(id, ifMatch, request);   // ETag preserved
        }

        public ResponseEntity&lt;Void&gt; deleteBoat(UUID id) {
            return businessServiceClient.deleteBoat(id);
        }
        ```
    </step>

    <step order="9">
      Implement BoatController for BFF (adapter.in.web.BoatController in bff/):
      - @RestController @RequestMapping("/api/v1/boats")
      - @Validated on the class — required for @PathVariable / @RequestParam
        constraints to fire (same reason as the Business Service controller).
      - Hand-written, one method per BusinessServiceClient operation. Do NOT `implement`
        BusinessServiceClient on the controller: that interface carries `@HttpExchange`
        annotations (outbound), whereas the controller needs `@RequestMapping`-family
        annotations (inbound). Use the same method signatures but fresh Spring MVC
        annotations; the controller delegates to BoatBffService and returns the
        `ResponseEntity&lt;T&gt;` it receives unchanged — ETag / Location / status code
        are forwarded automatically.

      - THE BFF IS A REST ADAPTER AT THE TRUST BOUNDARY TO THE BROWSER. It MUST
        enforce syntactic validation on inbound DTOs — do not merely proxy.
        Every @RequestBody parameter MUST be annotated `@Valid`, and the
        generated DTO classes carry `@NotBlank` / `@Size` / `@NotNull` /
        `@Pattern` because bff/pom.xml's openapi-generator now runs with
        `useBeanValidation=true` (02a1 step 2). Example:
          ```java
          @PostMapping
          public ResponseEntity&lt;BoatResponse&gt; createBoat(@Valid @RequestBody BoatCreateRequest request) {
              return boatBffService.createBoat(request);
          }
          ```
        Rationale: failing fast at the BFF means invalid payloads never hit
        the upstream Business Service, cutting upstream load and producing
        clean 400s to the browser with the same RFC 9457 envelope the browser
        sees for upstream errors.

        Optional hardening (not required): configure a SECOND openapi-generator execution
        in bff/pom.xml with `interfaceOnly=true` (spring generator, no spring-http-interface
        library) to produce an inbound `BusinessServiceApi` server interface the BFF
        controller can `implement`, locking the BFF's inbound contract to the spec too.
        Start without it; add it only if BFF controller signatures drift.

      - Depends on BoatBffService — NOT on BusinessServiceClient directly (enforced by ArchUnit).
        The ArchUnit rule `bff_controllers_depend_on_service_only` (02a5) permits
        `..bff.adapter.in.web..` (which transitively includes `dto.generated`) but NOT
        `..adapter.out.client.generated..`. Do not import `BusinessServiceClient` in the
        controller — only in BoatBffService.
      - NEVER @Transactional
      - Forwards headers (Location, ETag, If-Match) to/from Business Service transparently
        by returning the `ResponseEntity&lt;T&gt;` from BoatBffService as-is.

      Endpoints (same routes as Business Service):
      - GET /api/v1/boats → ResponseEntity&lt;PageBoatResponse&gt; 200  (page/size validated → 400)
      - GET /api/v1/boats/{id} → ResponseEntity&lt;BoatResponse&gt; 200 + ETag  (forwarded from Business Service)
      - POST /api/v1/boats → ResponseEntity&lt;BoatResponse&gt; 201 + Location + ETag  (@Valid body → 400 on bad input; else forwarded)
      - PUT /api/v1/boats/{id} → ResponseEntity&lt;BoatResponse&gt; 200 + ETag  (If-Match from browser → client; @Valid body)
      - DELETE /api/v1/boats/{id} → ResponseEntity&lt;Void&gt; 204
    </step>

    <step order="10">
      Implement AuthController for BFF (adapter.in.web.AuthController in bff/):
      - GET /api/me → returns `ch.owt.boatapp.bff.adapter.in.web.dto.generated.UserInfoResponse`
        (generated from the `User` tag in contracts/openapi.yaml — id, username, email, firstName, lastName)
      - Reads from the current OidcUser in SecurityContext (available after oauth2Login)
      - In dev profile (fallback): returns hardcoded dev user info

      Note: UserInfoResponse is generated from the OpenAPI spec on the BFF side only.
      The Business Service's codegen filters out the User tag (see 02a1 step 6's
      globalProperties.apis=BusinessService), so Business Service never sees UserInfoResponse.
    </step>

    <step order="11">
      Implement GlobalExceptionHandler for BFF (@RestControllerAdvice in bff/adapter.in.web/GlobalExceptionHandler).
      MANDATORY: private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class) + log all handlers.

      The BFF handles TWO classes of errors:

      CLASS 1 — Errors that ORIGINATE AT THE BFF (before the upstream hop):
      The BFF runs Jakarta Bean Validation on inbound requests (step 9). When
      the request is syntactically invalid, the BFF MUST produce its own RFC 9457
      ProblemDetail — it never reaches the Business Service.

      Replicate the Business Service's supporting artifacts on the BFF side so
      the wire shape is byte-identical to what the Business Service would emit:
        - bff/.../adapter/in/web/ProblemTypes.java             (same URI constants as 02a3 step 5 — copy verbatim)
        - bff/.../adapter/in/web/JakartaCodeTranslator.java    (same translation table — copy verbatim)
        - bff/src/main/resources/messages.properties           (same keys — copy verbatim)
      These three files are duplicated intentionally: each service is a
      standalone Spring Boot app with its own classpath, and they stay in
      sync via the prompt and the check-script (02a3 run.sh greps for
      ProblemTypes + messages.properties in BOTH services).

      Handlers for CLASS 1 (same bodies as the Business Service equivalents — see step 5a):
        1) MethodArgumentNotValidException → 400 (type=VALIDATION, messages[])
        2) ConstraintViolationException    → 400 (type=VALIDATION, messages[])
        3) HttpMessageNotReadableException → 400 (type=VALIDATION, messages=[{ERROR, "request.body.malformed"}])

      CLASS 2 — UPSTREAM errors forwarded from the Business Service:
      The upstream Business Service already returns RFC 9457 ProblemDetail
      (single shape, populated type/instance/messages). The BFF forwards the
      body and headers verbatim, preserving the status code and
      `Content-Language`.

      Handler for CLASS 2:
        4) RestClientResponseException:
           - If ex.getStatusCode() is 4xx:
               * Copy the upstream body (already RFC 9457 JSON) into the outgoing
                 ResponseEntity. Preserve Content-Type=application/problem+json
                 and Content-Language. Preserve status.
               * log.warn("Upstream {} at {} {} — forwarding", status, method, path).
           - If ex.getStatusCode() is 5xx:
               * Build a fresh ProblemDetail(502) with type=UPSTREAM_FAILURE
                 (add `UPSTREAM_FAILURE = URI.create("https://boatapp.owt.ch/problems/upstream-failure")`
                 to the BFF's ProblemTypes), title="Upstream service failed",
                 instance=requestURI. Do NOT leak the upstream body — the
                 Business Service is an internal component.
               * log.error("Upstream 5xx at {} {}", method, path, ex).

      Handler for UNEXPECTED errors:
        5) Exception (fallback) → 500:
           - type=INTERNAL (same URI registry as business-service),
             title="Internal server error", instance=requestURI.
           - log.error(..., ex).

      Every response in every handler MUST carry `Content-Type: application/problem+json`
      AND `Content-Language` (default `en` if no Accept-Language).

      IMPORTANT: Remove any prior code that special-cased `ValidationErrorResponse`
      (the schema is deleted — see 01-openapi-contract.md and step 12 below).
      The upstream 422 body is just another ProblemDetail; pass-through handles it.
    </step>

    <step order="12">
      BFF DTOs are GENERATED — do NOT create them by hand.

      The BFF pom's openapi-generator-maven-plugin (02a1 step 2, with
      `useBeanValidation=true`) produces all DTOs in
      `ch.owt.boatapp.bff.adapter.in.web.dto.generated.*`:
      - BoatResponse, BoatCreateRequest, BoatUpdateRequest, PageBoatResponse
        (request DTOs carry `@NotBlank` / `@Size` / `@NotNull` / `@Pattern` —
        the controller's `@Valid` triggers them → HTTP 400)
      - ProblemDetail   ← RFC 9457 shape, optional `messages[]` extension
      - ValidationMessageResponse
      - UserInfoResponse   ← generated on the BFF only (User tag); consumed by AuthController

      NOTE: `ValidationErrorResponse` is NO LONGER generated (removed from
      contracts/openapi.yaml per 01-openapi-contract.md). Multi-error responses
      use `ProblemDetail.messages`.

      Because both services generate from the same contracts/openapi.yaml, the wire
      format is guaranteed to be identical on both sides. Controllers and services
      import these types from their respective `*.dto.generated` package.

      IMPORTANT: Do NOT edit files under dto.generated/ — they are overwritten on every
      build. Change contracts/openapi.yaml and re-run `./mvnw generate-sources`.
    </step>
  </instructions-bff>

  <verification>
    Run the phase's verification script — it compiles both services,
    confirms controllers implement the generated API interfaces, the BFF
    does not import BusinessServiceClient directly, ETag/409/audit logic
    is present, and `generate-sources` is clean (no spec drift):
    ```bash
    ai-scripts/checks/02a3/run.sh .
    ```
    All `fail` items must be green before `<commit>`.
    Human-only checks live in `ai-scripts/checks/02a3/human.md`.
  </verification>

  <commit>
    ```bash
    git add -A
    git commit -m "feat: Business Service domain services + BFF proxy layer (two-layer validation, RFC 9457)

    Business Service:
    - DTOs + BusinessServiceApi interface generated from contracts/openapi.yaml
      (openapi-generator-maven-plugin, useBeanValidation=true, useResponseEntity=true)
    - BoatDomainService: pure Java, SyntacticValidator + SemanticValidator as
      defense-in-depth; primary syntactic gate is now Jakarta Bean Validation at the adapter
    - BoatApplicationService: @Service @Transactional bridge, throws ValidationFailureException
    - BoatController implements BusinessServiceApi with @Valid @RequestBody and @Validated;
      DTO → domain command mapper (BoatCommandMapper) wraps raw UUIDs in BoatId/UserId
    - GlobalExceptionHandler: RFC 9457 ProblemDetail (Spring 3 built-in) for every error;
      populated type/instance/Content-Language; handlers for MethodArgumentNotValidException,
      ConstraintViolationException, HttpMessageNotReadableException → 400; ValidationFailureException → 422;
      BoatNotFoundException → 404; ConcurrentModificationException → 409;
      MissingRequestHeaderException → 428; fallback → 500
    - ProblemTypes constants + JakartaCodeTranslator + messages.properties (i18n-ready)

    BFF:
    - DTOs + BusinessServiceClient @HttpExchange interface generated from contracts/openapi.yaml
      (openapi-generator-maven-plugin, library=spring-http-interface, apiNameSuffix=Client,
      useBeanValidation=true — BFF is itself a trust boundary)
    - BoatBffService: thin orchestrator, NO @Transactional, delegates to generated BusinessServiceClient
    - BoatController: @Valid on inbound DTOs (syntactic → 400 locally);
      proxies the rest to BoatBffService, forwards ResponseEntity headers (Location/ETag/If-Match) transparently
    - AuthController: GET /api/me from OidcUser session, returns generated UserInfoResponse
    - GlobalExceptionHandler: same RFC 9457 envelope as Business Service for locally-raised errors;
      pass-through for upstream 4xx (body already compliant); 5xx wrapped as 502 upstream-failure"
    ```
  </commit>
</task>
