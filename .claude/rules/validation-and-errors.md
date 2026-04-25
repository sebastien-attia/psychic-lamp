---
paths: ["bff/**", "business-service/**", "contracts/**"]
---
# Validation & Errors — Unified Two-Layer Design (RFC 9457)

Both services expose REST over HTTP and both act as trust boundaries
(Business Service to its Bearer-token callers; BFF to the browser). Both
enforce the same two-layer model and emit the same wire envelope.

## Two-layer validation model

1. **Syntactic** (null/blank/size/format/range/regex) — enforced at the REST
   adapter by **Jakarta Bean Validation** via `@Valid` on request bodies and
   `@Validated` on controller classes (for @PathVariable / @RequestParam).
   Produces HTTP **400 Bad Request**.

2. **Semantic** (business rules, invariants, state-dependent rules) —
   enforced in the domain. Surfaces as `ValidationFailureException`
   (carries `List<ValidationMessage>`). Produces HTTP **422 Unprocessable
   Entity**.

The domain remains authoritative: `SyntacticValidator` / `SemanticValidator`
and value-object invariants (BoatId/UserId compact constructors) still fire
for non-REST callers (CLI, queue, test). Bean Validation at the adapter is
defense-in-depth at the HTTP trust boundary — not a substitute for domain
invariants, and not a duplicate of them from the domain's point of view.

## Single wire shape — RFC 9457 ProblemDetail

Every non-2xx response uses media type `application/problem+json` and the
single schema `ProblemDetail` (generated from contracts/openapi.yaml).
Required fields: `type`, `title`, `status`, `instance`. Optional: `detail`,
and the extension member `messages: [ValidationMessageResponse]`.

- `type`     — MUST be a stable URI from the registry below. NEVER
               `about:blank`.
- `instance` — MUST be the request path (`request.getRequestURI()` in
               Spring).
- `detail`   — human-readable; safe to localize.
- `messages` — populated for 400 (syntactic) and 422 (semantic); each entry
               is `{severity, code, field, message}`. `code` is a stable
               application-level code (e.g. `field.required`,
               `field.size.invalid`). NEVER emit Jakarta constraint names
               (`NotBlank`, `Size`, …) — translate via `JakartaCodeTranslator`.
               `message` is i18n-resolved against `messages.properties`.
- Response headers — `Content-Type: application/problem+json` and
  `Content-Language` (resolved from `Accept-Language`, default `en`).

## Problem-type URI registry

| Status | `type` URI                                                  | Trigger                                   |
|--------|-------------------------------------------------------------|-------------------------------------------|
| 400    | https://boatapp.owt.ch/problems/validation                  | Bean Validation / malformed JSON          |
| 401    | https://boatapp.owt.ch/problems/auth-required               | No / expired session                      |
| 404    | https://boatapp.owt.ch/problems/not-found                   | BoatNotFoundException                     |
| 409    | https://boatapp.owt.ch/problems/concurrency-conflict        | OptimisticLockException / ConcurrentModification |
| 422    | https://boatapp.owt.ch/problems/validation                  | ValidationFailureException (domain)       |
| 428    | https://boatapp.owt.ch/problems/precondition-required       | Missing `If-Match` header                 |
| 500    | https://boatapp.owt.ch/problems/internal                    | Fallback Exception                        |
| 502    | https://boatapp.owt.ch/problems/upstream-failure            | BFF only: 5xx from Business Service       |

Handlers reference these as constants from `adapter/in/web/ProblemTypes.java`
(one copy per service). Never hand-write the URI in handler code.

## Exception → handler mapping

Every `@RestControllerAdvice` in both services handles (at minimum):

| Exception                            | Status | `type`                  | `messages[]` populated? |
|--------------------------------------|--------|-------------------------|--------------------------|
| `MethodArgumentNotValidException`    | 400    | `.../validation`        | yes (per FieldError)     |
| `ConstraintViolationException`       | 400    | `.../validation`        | yes (per violation)      |
| `HttpMessageNotReadableException`    | 400    | `.../validation`        | yes (single entry, `request.body.malformed`) |
| `AuthenticationException` (Spring Security entry point) | 401 | `.../auth-required` | no (handled by `RestAuthenticationEntryPoint`, not @ControllerAdvice) |
| `ValidationFailureException`         | 422    | `.../validation`        | yes (from domain)        |
| `BoatNotFoundException`              | 404    | `.../not-found`         | no                       |
| `OptimisticLockException` / `ConcurrentModificationException` | 409 | `.../concurrency-conflict` | no |
| `MissingRequestHeaderException`      | 428    | `.../precondition-required` | no                    |
| `Exception` (fallback)               | 500    | `.../internal`          | no (never leak stack)    |

BFF only: `RestClientResponseException` from the upstream Business Service →
pass through 4xx responses byte-identical (upstream body is already
ProblemDetail-compliant); wrap 5xx as 502 `.../upstream-failure` without
leaking upstream body.

## Anti-patterns (DO NOT)

- ❌ Skipping Bean Validation annotations because "the domain validates
  anyway". Both layers run; each has a distinct purpose.
- ❌ Leaking Jakarta constraint names (`NotBlank`, `Size`, …) as wire codes.
  Map them via `JakartaCodeTranslator`.
- ❌ Importing `jakarta.validation.*` or `org.springframework.*` inside
  `..domain..`. ArchUnit enforces this.
- ❌ Returning `ResponseEntity` or HTTP-specific types from use cases.
- ❌ Using HTTP 400 for domain/business rule failures. Domain failures = 422.
- ❌ Different response shapes for adapter-origin vs domain-origin errors —
  always a single `ProblemDetail`.
- ❌ Emitting `about:blank` as `type`. Always use the registry.
- ❌ Forgetting to update contracts/openapi.yaml when adding or changing a
  Jakarta constraint on a DTO. OpenAPI constraints and Bean Validation
  annotations are kept in sync by `useBeanValidation=true` in the codegen.

## Supporting artifacts (present in BOTH services)

- `adapter/in/web/ProblemTypes.java`            — URI constants from the registry
- `adapter/in/web/JakartaCodeTranslator.java`   — Jakarta constraint → application code
- `adapter/in/web/GlobalExceptionHandler.java`  — @RestControllerAdvice with all handlers
- `src/main/resources/messages.properties`      — application-code → localized string

## Tests

- `@WebMvcTest` slice or `@SpringBootTest` integration asserts full RFC 9457
  envelope: Content-Type, Content-Language, populated `type` (matching
  registry), `instance` (matching request path), populated `messages` for
  400/422.
- Regression guard: the body must NEVER contain `about:blank`.
- BFF-specific: validation-failure test must also assert WireMock received
  ZERO upstream requests (proving the BFF's own @Valid kicked in).
