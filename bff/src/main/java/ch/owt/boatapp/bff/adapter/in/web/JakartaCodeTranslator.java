package ch.owt.boatapp.bff.adapter.in.web;

import java.util.Map;

/**
 * Maps Jakarta Bean Validation constraint annotation simple names (e.g.
 * {@code NotBlank}, {@code Size}, {@code Pattern}) to stable, wire-safe
 * application codes (e.g. {@code field.required}, {@code field.size.invalid}).
 *
 * <p>The wire contract NEVER exposes Jakarta constraint names: those are
 * implementation detail of the validation framework and would leak the
 * underlying technology to clients. Application codes are the keys in
 * {@code messages.properties} and the {@code code} field of
 * {@code ValidationMessageResponse} on the wire.
 *
 * <p>Mirrors the Business Service's {@code JakartaCodeTranslator} verbatim
 * — both copies stay in sync via the prompt and the phase verification script.
 */
public final class JakartaCodeTranslator {

    private static final Map<String, String> TO_APP_CODE = Map.ofEntries(
            Map.entry("NotBlank",       "field.required"),
            Map.entry("NotNull",        "field.required"),
            Map.entry("NotEmpty",       "field.required"),
            Map.entry("Size",           "field.size.invalid"),
            Map.entry("Min",            "field.range.invalid"),
            Map.entry("Max",            "field.range.invalid"),
            Map.entry("DecimalMin",     "field.range.invalid"),
            Map.entry("DecimalMax",     "field.range.invalid"),
            Map.entry("Email",          "field.email.invalid"),
            Map.entry("Pattern",        "field.format.invalid"),
            Map.entry("Positive",       "field.range.invalid"),
            Map.entry("PositiveOrZero", "field.range.invalid"),
            Map.entry("Negative",       "field.range.invalid"),
            Map.entry("NegativeOrZero", "field.range.invalid"),
            Map.entry("Digits",         "field.format.invalid"),
            Map.entry("Past",           "field.range.invalid"),
            Map.entry("PastOrPresent",  "field.range.invalid"),
            Map.entry("Future",         "field.range.invalid"),
            Map.entry("FutureOrPresent","field.range.invalid")
    );

    /**
     * Translate a Jakarta constraint annotation simple name into the
     * application code this project uses on the wire.
     *
     * @param jakartaCode the Jakarta constraint annotation simple name (e.g.
     *                    {@code "NotBlank"}, {@code "Size"}); may be
     *                    {@code null}, in which case the fallback applies
     * @return the application code (e.g. {@code "field.required"});
     *         {@code "field.invalid"} if {@code jakartaCode} is {@code null}
     *         or unknown
     */
    public static String toApplicationCode(String jakartaCode) {
        return TO_APP_CODE.getOrDefault(jakartaCode, "field.invalid");
    }

    private JakartaCodeTranslator() {
        // utility class — not instantiable
    }
}
