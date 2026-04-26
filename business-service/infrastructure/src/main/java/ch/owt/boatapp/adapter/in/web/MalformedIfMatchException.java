package ch.owt.boatapp.adapter.in.web;

/**
 * Thrown by the boat controller when the inbound {@code If-Match} header
 * value cannot be parsed as a bare integer version per the contract in
 * {@code contracts/openapi.yaml}.
 *
 * <p>A typed adapter-layer exception (rather than reusing the generic
 * {@link IllegalArgumentException}) so the {@code GlobalExceptionHandler}
 * can map it to HTTP 400 with the precise {@code field.format.invalid}
 * application code without inadvertently catching unrelated
 * {@code IllegalArgumentException}s thrown by domain value objects or
 * third-party libraries — those should surface as 500.
 */
public class MalformedIfMatchException extends RuntimeException {

    /**
     * @param ifMatch the offending raw header value (informational; never logged
     *                as user data and never echoed verbatim to the wire)
     * @param cause   the underlying parse failure (e.g. {@code NumberFormatException})
     */
    public MalformedIfMatchException(String ifMatch, Throwable cause) {
        super("If-Match header must be a bare integer version (got: " + ifMatch + ")", cause);
    }
}
