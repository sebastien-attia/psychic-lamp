package ch.owt.boatapp.bff.adapter.in.web;

import java.net.URI;

/**
 * Typed constants for the problem-type URI registry defined in
 * {@code contracts/openapi.yaml} (see the {@code ProblemDetail} schema
 * description) and {@code .claude/rules/validation-and-errors.md}.
 *
 * <p>Handlers MUST reference these constants — never hand-write the URIs.
 *
 * <p>Mirrors the Business Service's {@code ProblemTypes} verbatim, with one
 * addition: {@link #UPSTREAM_FAILURE} for BFF-only 502 responses (raised when
 * the upstream Business Service returns 5xx — the BFF wraps such failures
 * rather than leaking the upstream body, which is internal).
 */
public final class ProblemTypes {

    /** 400 / 422 — Bean Validation failure, malformed JSON, or domain validation failure. */
    public static final URI VALIDATION =
            URI.create("https://boatapp.owt.ch/problems/validation");

    /** 401 — no / expired session. */
    public static final URI AUTH_REQUIRED =
            URI.create("https://boatapp.owt.ch/problems/auth-required");

    /** 404 — boat not found (forwarded from the upstream Business Service). */
    public static final URI NOT_FOUND =
            URI.create("https://boatapp.owt.ch/problems/not-found");

    /** 409 — optimistic-lock conflict (forwarded from the upstream Business Service). */
    public static final URI CONCURRENCY_CONFLICT =
            URI.create("https://boatapp.owt.ch/problems/concurrency-conflict");

    /** 428 — {@code If-Match} header missing on a write that requires optimistic locking. */
    public static final URI PRECONDITION_REQUIRED =
            URI.create("https://boatapp.owt.ch/problems/precondition-required");

    /** 500 — fallback for any unexpected exception. */
    public static final URI INTERNAL =
            URI.create("https://boatapp.owt.ch/problems/internal");

    /** 502 — BFF-specific: the upstream Business Service returned a 5xx response. */
    public static final URI UPSTREAM_FAILURE =
            URI.create("https://boatapp.owt.ch/problems/upstream-failure");

    private ProblemTypes() {
        // utility class — not instantiable
    }
}
