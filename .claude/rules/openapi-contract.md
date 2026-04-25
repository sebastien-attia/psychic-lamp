---
paths: ["contracts/**"]
---
# Contract Rules
- contracts/openapi.yaml is single source of truth
- No Bearer security scheme (session-based auth visible to browser)
- ETag/If-Match for optimistic locking
- Boat: name max 64, description max 256, createdAt OffsetDateTime
- Errors: RFC 9457 (obsoletes RFC 7807). Single shape = ProblemDetail with an
  optional `messages: [ValidationMessageResponse]` extension member. Media type
  `application/problem+json`. `type` is drawn from the problem-type URI
  registry in the ProblemDetail schema description — never `about:blank`.
  `instance` is the request path. Every error response declares the
  `Content-Language` header. Declare 400, 401, 404, 409, 422, 428, 500 on
  every operation where applicable (all referencing the single shape).
- Severity enum: `ERROR | WARNING | INFO` (no `WARN`).
- `ValidationErrorResponse` is NOT part of the contract — multi-error uses
  `ProblemDetail.messages`.
