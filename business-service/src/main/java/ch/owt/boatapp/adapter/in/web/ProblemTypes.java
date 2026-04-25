package ch.owt.boatapp.adapter.in.web;

import java.net.URI;

/**
 * Typed constants for the problem-type URI registry defined in
 * {@code contracts/openapi.yaml} (see the {@code ProblemDetail} schema
 * description) and {@code .claude/rules/validation-and-errors.md}.
 *
 * <p>Handlers MUST reference these constants — never hand-write the URIs.
 * This guarantees that the wire {@code type} field always points at a
 * registered category and never falls back to RFC 9457's {@code about:blank}
 * placeholder.
 *
 * <p>The same registry is replicated verbatim on the BFF side
 * ({@code bff/.../adapter/in/web/ProblemTypes.java}), with the addition of
 * {@code UPSTREAM_FAILURE} for BFF-only 502 responses.
 */
public final class ProblemTypes {

    /** 400 / 422 — Bean Validation failure, malformed JSON, or domain validation failure. */
    public static final URI VALIDATION =
            URI.create("https://boatapp.owt.ch/problems/validation");

    /** 401 — missing or invalid Bearer JWT (issued by {@code RestAuthenticationEntryPoint}). */
    public static final URI AUTH_REQUIRED =
            URI.create("https://boatapp.owt.ch/problems/auth-required");

    /** 404 — {@code BoatNotFoundException}. */
    public static final URI NOT_FOUND =
            URI.create("https://boatapp.owt.ch/problems/not-found");

    /** 409 — domain {@code ConcurrentModificationException} or JPA {@code OptimisticLockException}. */
    public static final URI CONCURRENCY_CONFLICT =
            URI.create("https://boatapp.owt.ch/problems/concurrency-conflict");

    /** 428 — {@code If-Match} header missing on a write that requires optimistic locking. */
    public static final URI PRECONDITION_REQUIRED =
            URI.create("https://boatapp.owt.ch/problems/precondition-required");

    /** 500 — fallback for any unexpected exception. */
    public static final URI INTERNAL =
            URI.create("https://boatapp.owt.ch/problems/internal");

    private ProblemTypes() {
        // utility class — not instantiable
    }
}
