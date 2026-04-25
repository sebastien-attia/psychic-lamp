package ch.owt.boatapp.domain.model.validation;

/**
 * Severity of a {@link ValidationMessage}.
 *
 * <p>Aligned 1:1 with the wire enum declared in {@code contracts/openapi.yaml}
 * ({@code Severity}), so no mapper bridge is needed between the generated
 * DTO enum and this domain enum. Three values exactly:
 * {@link #ERROR}, {@link #WARNING}, {@link #INFO}.
 */
public enum Severity {

    /** Blocks the operation: HTTP 400 (syntactic) or 422 (semantic). */
    ERROR,

    /** Non-blocking advisory; reserved for future surfacing on 2xx responses. */
    WARNING,

    /** Informational; reserved for future surfacing on 2xx responses. */
    INFO
}
