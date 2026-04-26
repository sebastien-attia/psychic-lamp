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

  <role>You are a senior API architect designing a RESTful contract for a boat management application.</role>

  <context>
    <project>The Boat App — a fullstack web app for authenticated users to manage a fleet of boats.</project>
    <approach>API-first: this OpenAPI spec is the single source of truth. Backend stubs and frontend TypeScript types are generated from it.</approach>
    <architecture>
      Two Spring Boot services: the API shape defined here is implemented by the
      Business Service (JWT resource server, :8081) and transparently proxied by
      the BFF (Spring Cloud Gateway, :8080). The SPA is hosted separately —
      by Vite at :5173 in dev/local-intg and by Azure Static Web Apps in
      staging/prod — and reaches the BFF same-origin (Vite proxy or SWA
      linked-backend). From the browser's perspective the API is same-origin
      behind the BFF's HttpOnly session cookie (no CORS, no Bearer token in
      the browser).

      The OpenAPI spec documents the API shape for code generation (both the
      Business Service controllers and the frontend TypeScript client are
      generated from it) and Swagger UI. Authentication is cookie-based at the
      edge; internally the BFF forwards the OAuth2 access token to the Business
      Service as a Bearer header, but that hop is not browser-visible and is
      not part of this contract.
    </architecture>
  </context>

  <requirements>
    <tagging>
      Use exactly TWO tags — they drive code-generation class names:
      - `BusinessService` — ALL endpoints under /api/v1/** (hosted by the Business Service).
        Drives generated interface names: `BusinessServiceApi` (server-side, in Business Service)
        and `BusinessServiceClient` (BFF-side, via apiNameSuffix=Client).
      - `User` — /api/me ONLY (BFF-only endpoint, not hosted by Business Service).
        Not generated into the BFF's outbound client — only into frontend TS types.
    </tagging>

    <boat-model>
      <field name="id" type="string" format="uuid" description="Unique identifier, generated server-side"/>
      <field name="name" type="string" minLength="1" maxLength="64" nullable="false" description="Boat name, required"/>
      <field name="description" type="string" maxLength="256" nullable="true" description="Optional description"/>
      <field name="createdAt" type="string" format="date-time" description="Creation timestamp in UTC (ISO 8601, e.g. 2026-04-22T14:30:00Z)"/>
      <field name="version" type="integer" format="int64" description="Optimistic locking version. Send via If-Match header on PUT."/>
    </boat-model>

    <optimistic-locking>
      - GET responses include an ETag header containing the version number
      - PUT requests MUST include an If-Match header with the current version
      - 409 Conflict if version mismatch (concurrent modification)
      - version also included in BoatResponse body for convenience
      - POST 201 responses include a Location header pointing to the created
        boat. Its schema is `type: string, format: uri-reference` (NOT `uri`) —
        the example is a relative path like `/api/v1/boats/{uuid}`, which
        OpenAPI/JSON-Schema tooling rejects as a strict `format: uri`.
    </optimistic-locking>

    <endpoints>
      <!--
        Error-response convention (applied to every endpoint below):
          All error responses use media type `application/problem+json` and
          reference the single `ProblemDetail` schema. Syntactic validation
          failures surface as HTTP 400 with `messages[]` populated; semantic
          (domain rule) failures surface as HTTP 422 with the same shape.
          Every error response declares the `Content-Language` header.
          `ProblemDetail.type` is a stable URI from the registry
          (components.schemas.ProblemDetail.description) — never `about:blank`.
      -->
      <endpoint method="GET" path="/api/v1/boats" description="List all boats with pagination and optional search" tag="BusinessService" operationId="listBoats">
        <query-params>page (default 0, min 0), size (default 10, min 1, max 100), sort (default createdAt,desc), search (optional)</query-params>
        <response code="200">Paginated list of boats</response>
        <response code="400">Bad request — query-param validation (ProblemDetail, application/problem+json)</response>
        <response code="401">Unauthorized (session expired or not authenticated)</response>
        <response code="500">Internal server error (ProblemDetail, application/problem+json)</response>
      </endpoint>
      <endpoint method="GET" path="/api/v1/boats/{id}" description="Get a single boat by ID" tag="BusinessService" operationId="getBoat">
        <response code="200">Boat detail with ETag header</response>
        <response code="400">Bad request — malformed path parameter (ProblemDetail)</response>
        <response code="401">Unauthorized</response>
        <response code="404">Boat not found (ProblemDetail)</response>
        <response code="500">Internal server error (ProblemDetail)</response>
      </endpoint>
      <endpoint method="POST" path="/api/v1/boats" description="Create a new boat" tag="BusinessService" operationId="createBoat">
        <request-body>name (required, 1-64 chars), description (optional, max 256 chars)</request-body>
        <response code="201">Created boat with Location header and ETag</response>
        <response code="400">Bad request — syntactic validation failure or malformed JSON (ProblemDetail with populated messages[])</response>
        <response code="401">Unauthorized</response>
        <response code="422">Unprocessable entity — semantic/business-rule failure (ProblemDetail with populated messages[])</response>
        <response code="500">Internal server error (ProblemDetail)</response>
      </endpoint>
      <endpoint method="PUT" path="/api/v1/boats/{id}" description="Update an existing boat" tag="BusinessService" operationId="updateBoat">
        <request-headers>If-Match: required, current version for optimistic locking</request-headers>
        <request-body>name (required, 1-64 chars), description (optional, max 256 chars)</request-body>
        <response code="200">Updated boat with new ETag</response>
        <response code="400">Bad request — syntactic validation failure or malformed JSON (ProblemDetail with messages[])</response>
        <response code="401">Unauthorized</response>
        <response code="404">Boat not found (ProblemDetail)</response>
        <response code="409">Conflict — version mismatch (ProblemDetail)</response>
        <response code="422">Unprocessable entity — semantic/business-rule failure (ProblemDetail with messages[])</response>
        <response code="428">Precondition Required — If-Match header missing (ProblemDetail)</response>
        <response code="500">Internal server error (ProblemDetail)</response>
      </endpoint>
      <endpoint method="DELETE" path="/api/v1/boats/{id}" description="Delete a boat" tag="BusinessService" operationId="deleteBoat">
        <response code="204">No content</response>
        <response code="400">Bad request — malformed path parameter (ProblemDetail)</response>
        <response code="401">Unauthorized</response>
        <response code="404">Boat not found (ProblemDetail)</response>
        <response code="500">Internal server error (ProblemDetail)</response>
      </endpoint>
      <endpoint method="GET" path="/api/me" description="Current authenticated user's profile (BFF-only — not proxied)" tag="User" operationId="getCurrentUser">
        <response code="200">UserInfoResponse (read from BFF's OidcUser session; in dev profile returns the dummy dev user)</response>
        <response code="401">Unauthorized</response>
        <response code="500">Internal server error (ProblemDetail)</response>
      </endpoint>
    </endpoints>

    <models>
      <model name="BoatResponse">id (UUID), name, description (nullable), createdAt (date-time), version (int64)</model>
      <model name="BoatCreateRequest">name (string, required, minLength 1, maxLength 64), description (string, optional, maxLength 256)</model>
      <model name="BoatUpdateRequest">name (string, required, minLength 1, maxLength 64), description (string, optional, maxLength 256)</model>
      <model name="PageBoatResponse">content (array of BoatResponse), totalElements, totalPages, size, number, first, last, empty</model>
      <model name="ProblemDetail">
        RFC 9457 (obsoletes RFC 7807) "Problem Details for HTTP APIs". Single
        error shape for this API — returned for every non-2xx status with
        media type `application/problem+json`.

        Fields (required: type, title, status, instance; detail optional; messages optional extension):
          - `type`    (string, format: uri, **required**) — stable URI identifying the problem
                      category. MUST come from the registry below; MUST NOT be
                      `about:blank` (we always have at least `instance`, so the
                      RFC 9457 §4.1 constraint against `about:blank` with
                      extensions applies).
          - `title`   (string, required) — short human-readable summary, stable
                      across occurrences of the same `type`.
          - `status`  (integer, int32, required) — HTTP status code.
          - `detail`  (string, optional) — human-readable explanation specific
                      to this occurrence; safe to localize against Accept-Language.
          - `instance`(string, format: **uri-reference** per RFC 3986, required) —
                      relative URI identifying the specific occurrence, typically
                      the request path (e.g. `/api/v1/boats/{uuid}`). Do NOT use
                      `format: uri` or the relative example fails lint.
          - `messages`(array of ValidationMessageResponse, optional extension
                      member per RFC 9457 §4.1) — populated for HTTP 400 (syntactic)
                      and HTTP 422 (semantic) responses; empty/absent otherwise.

        Problem-type URI registry (authoritative — handlers and tests reference these):
          | Status | `type` URI                                                         | Trigger                                    |
          | 400    | https://boatapp.owt.ch/problems/validation                         | Bean Validation or malformed JSON          |
          | 404    | https://boatapp.owt.ch/problems/not-found                          | BoatNotFoundException                      |
          | 409    | https://boatapp.owt.ch/problems/concurrency-conflict               | OptimisticLockException                    |
          | 422    | https://boatapp.owt.ch/problems/validation                         | ValidationFailureException (domain)        |
          | 428    | https://boatapp.owt.ch/problems/precondition-required              | Missing If-Match header                    |
          | 500    | https://boatapp.owt.ch/problems/internal                           | Fallback Exception                         |
          | 502    | https://boatapp.owt.ch/problems/upstream-failure                   | BFF only: 5xx from Business Service        |
      </model>
      <model name="ValidationMessageResponse">
        severity (enum: ERROR, WARNING, INFO — three values only; no WARN),
        code (string, stable machine-readable application code, e.g. `field.required`, `field.size.invalid`, `field.email.invalid`, `request.body.malformed`, `internal.error`; MUST NOT leak Jakarta constraint names like `NotBlank`/`Size`),
        field (string, optional — JSON pointer or dotted path to the offending field, e.g. `name`, `lines[2].quantity`),
        message (string — human-readable, localized against Accept-Language)
      </model>
      <!-- ValidationErrorResponse is deliberately removed: per RFC 9457 §4.1
           multi-error problems use an extension member on the single
           `application/problem+json` body. The extension is `ProblemDetail.messages`. -->
      <model name="UserInfoResponse">id (UUID — AppUser id in the Business Service; in dev profile returns the dummy user's id), username (string), email (string), firstName (string), lastName (string) — returned by GET /api/me (BFF-only)</model>
    </models>

    <security>
      Authentication is session-based (HttpOnly cookie from oauth2Login).
      Do NOT declare a Bearer security scheme — the cookie is automatic.
      Mark all endpoints as requiring authentication in the description.
      Swagger UI will work because the browser sends the session cookie automatically.
    </security>
  </requirements>

  <o>
    <file path="contracts/openapi.yaml">
      Complete OpenAPI 3.0.3 specification in YAML.
      Include: info, servers (localhost:8080), paths, components (schemas, headers, responses), tags.
      Realistic examples for each schema. Use $ref for reusable schemas.
      No securitySchemes/Bearer — auth is session-cookie-based.

      === RFC 9457 compliance (MANDATORY) ===
      Every non-2xx response MUST:
        1. Use media type `application/problem+json`.
        2. Reference `#/components/schemas/ProblemDetail` as the body schema.
        3. Declare a `Content-Language` response header (schema: `{ type: string, example: en }`).
        4. Carry a realistic example whose `type` is drawn from the registry on
           the ProblemDetail schema description and whose `instance` is a
           concrete relative path (e.g. `/api/v1/boats/11111111-1111-1111-1111-111111111111`).

      Define **reusable components.responses** entries so every operation just
      $refs them (no copy-paste):
        - BadRequest           (400, ProblemDetail, example: syntactic field validation)
        - Unauthorized         (401, ProblemDetail)
        - NotFound             (404, ProblemDetail)
        - Conflict             (409, ProblemDetail)
        - UnprocessableEntity  (422, ProblemDetail, example: semantic/business-rule failure)
        - PreconditionRequired (428, ProblemDetail)
        - InternalServerError  (500, ProblemDetail)
      Each of these MUST include `headers: { Content-Language: { schema: { type: string } } }`.

      === Examples (MANDATORY — add under each named response or under the ProblemDetail schema) ===
        - `syntactic-validation` (400): two `ERROR` entries on `name` (`field.required`)
          and `description` (`field.size.invalid`). `type` = `https://boatapp.owt.ch/problems/validation`,
          `title` = "Request validation failed", `status` = 400, `instance` = `/api/v1/boats`.
        - `semantic-validation`  (422): one `ERROR` entry for a business rule
          (example: "boat.name.duplicate"). `type` = `https://boatapp.owt.ch/problems/validation`,
          `status` = 422.
        - `success-with-warnings` (200): included inline in the ProblemDetail schema's
          `messages` field description (not an actual response example) to document
          that `messages` can also carry WARNING/INFO on a 2xx response for future use.

      === URI format rule ===
      Use `format: uri-reference` for URI fields whose example values are relative
      paths — in this spec that is `ProblemDetail.instance` and the `Location`
      response header. Use `format: uri` for absolute URIs — that is
      `ProblemDetail.type` (always drawn from the registry:
      `https://boatapp.owt.ch/problems/...`, never `about:blank`).

      === Severity enum ===
      The `ValidationMessageResponse.severity` enum is exactly `[ERROR, WARNING, INFO]`.
      NO `WARN` — align with the domain enum so no mapper bridge is needed.

      === Request schemas stay in sync with Bean Validation ===
      The existing constraints on `BoatCreateRequest` / `BoatUpdateRequest`
      (`required: [name]`, `minLength: 1`, `maxLength: 64`, `maxLength: 256`)
      are what the openapi-generator will turn into `@NotBlank` + `@Size` once
      `useBeanValidation=true` (see ai-scripts/02a1-backend-scaffold.md). Keep
      them exhaustive and accurate; every constraint MUST be mirrored by a
      generated Jakarta annotation.

      Tags (exactly two — see <tagging> above):
        - name: BusinessService
          description: Endpoints hosted by the Business Service (proxied by the BFF).
        - name: User
          description: BFF-only endpoints reading from the OAuth2 session.

      Every boats operation MUST set tags: [BusinessService] and an explicit operationId
      (listBoats, getBoat, createBoat, updateBoat, deleteBoat). The /api/me operation
      MUST set tags: [User] and operationId: getCurrentUser. These drive the generated
      class names (BusinessServiceApi, BusinessServiceClient, UserApi).

      This spec is the single source of truth and is consumed by:
        - business-service/pom.xml → openapi-generator-maven-plugin
            (spring generator, interfaceOnly=true, **useBeanValidation=true**,
             useResponseEntity=true, filters to tag=BusinessService → generates
             BusinessServiceApi interface that BoatController implements, plus DTOs
             carrying `@NotBlank`/`@Size`/`@NotNull`/`@Pattern` from the OpenAPI constraints)
        - bff/pom.xml → openapi-generator-maven-plugin
            (spring generator, library=spring-http-interface, apiNameSuffix=Client,
             **useBeanValidation=true** — so BFF inbound DTOs also carry Jakarta
             constraints when reused at the BFF controller; filters to
             tag=BusinessService → generates BusinessServiceClient @HttpExchange
             interface, plus DTOs; /api/me NOT generated as a client because it
             is not proxied)
        - frontend/package.json → openapi-generator-cli typescript-axios
            (generates the full set of types + axios client, including /api/me;
             TS types include ProblemDetail.messages and the Severity enum)

      See ai-scripts/02a1-backend-scaffold.md and ai-scripts/02b1-frontend-scaffold.md
      for the plugin/script wiring.
    </file>
  </o>

  <verification>
    Run the phase's verification script — it validates YAML parsing, runs
    redocly lint, checks required endpoints/verbs, Boat fields, ETag/If-Match,
    400/404/409/422/428/500 responses, no Bearer scheme, pagination params, and
    RFC 9457 compliance (unified ProblemDetail, registry type URIs, no
    ValidationErrorResponse, no `about:blank`, Content-Language header on every
    error response):
    ```bash
    ai-scripts/checks/1/run.sh .
    ```
    All `fail` items must be green before `<commit>`.
    Human-only checks live in `ai-scripts/checks/1/human.md`.
  </verification>

  <commit>
    ```bash
    git add contracts/openapi.yaml
    git commit -m "contracts: define OpenAPI 3.0 Boat CRUDL specification (RFC 9457)

    - GET/POST/PUT/DELETE /api/v1/boats endpoints (tag: BusinessService)
    - GET /api/me endpoint (tag: User, BFF-only)
    - Boat: id (UUID), name (max 64), description (max 256), createdAt, version
    - Optimistic locking via ETag / If-Match headers
    - Pagination, search, validation schemas
    - RFC 9457 ProblemDetail (obsoletes 7807) as the single error shape for
      400/401/404/409/422/428/500, with a messages[] extension member,
      stable problem-type URIs, and Content-Language headers
    - Drop ValidationErrorResponse — multi-error uses ProblemDetail.messages
    - Severity enum: ERROR | WARNING | INFO (no WARN)
    - UserInfoResponse for /api/me
    - Session-based auth (cookie, no Bearer scheme)
    - Single source of truth for openapi-generator (backend + frontend codegen)"
    ```
  </commit>
</task>
