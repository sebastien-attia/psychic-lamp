package ch.owt.boatapp.domain.model.validation;

/**
 * Catalogue of validation message kinds, each carrying a stable, wire-safe
 * application code via {@link #applicationCode()}.
 *
 * <p>The application code is the single source of truth for the
 * {@code code} field of {@code ValidationMessageResponse} on the wire and
 * for the keys in {@code messages.properties} (added in step 02a3). Mappers
 * and tests must read it from {@link #applicationCode()} — never from
 * {@link #name()} (e.g. {@code "CANNOT_BE_BLANK"}), which is an internal
 * symbol and MUST NOT leak.
 *
 * <p>Mapping (kept in sync with {@code messages.properties}):
 * <table>
 * <caption>Type → application code</caption>
 * <tr><th>{@link #CANNOT_BE_BLANK}</th><td>{@code field.required}</td></tr>
 * <tr><th>{@link #CANNOT_BE_EMPTY}</th><td>{@code field.required}</td></tr>
 * <tr><th>{@link #SIZE_EXCEEDED}</th><td>{@code field.size.invalid}</td></tr>
 * <tr><th>{@link #INVALID_FORMAT}</th><td>{@code field.format.invalid}</td></tr>
 * </table>
 */
public enum MessageType {

    /** A required string field was {@code null} or whitespace-only. */
    CANNOT_BE_BLANK("field.required"),

    /** A required collection field was {@code null} or empty. */
    CANNOT_BE_EMPTY("field.required"),

    /** A field's length / size exceeds the allowed bound. */
    SIZE_EXCEEDED("field.size.invalid"),

    /** A field does not match its required format (regex, parse, …). */
    INVALID_FORMAT("field.format.invalid");

    private final String applicationCode;

    MessageType(String applicationCode) {
        this.applicationCode = applicationCode;
    }

    /**
     * @return the wire-safe application code for this message type — the
     *         exact string emitted on the wire and used as the
     *         {@code messages.properties} key
     */
    public String applicationCode() {
        return applicationCode;
    }
}
