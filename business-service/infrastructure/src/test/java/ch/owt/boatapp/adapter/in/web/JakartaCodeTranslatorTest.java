package ch.owt.boatapp.adapter.in.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-Java parameterized tests for {@link JakartaCodeTranslator}.
 *
 * <p>Pins the wire-stable mapping from Jakarta constraint annotation simple
 * names to application codes. The codes are part of the public wire contract
 * (response.messages[].code) and the keys in {@code messages.properties} —
 * any change here is a breaking change for clients.
 *
 * <p>Unknown / null inputs MUST fall back to {@code field.invalid} (defensive
 * — a Jakarta constraint not yet mapped should not leak its name).
 */
class JakartaCodeTranslatorTest {

    @ParameterizedTest(name = "Jakarta {0} → app code {1}")
    @CsvSource({
            "NotBlank,        field.required",
            "NotNull,         field.required",
            "NotEmpty,        field.required",
            "Size,            field.size.invalid",
            "Min,             field.range.invalid",
            "Max,             field.range.invalid",
            "DecimalMin,      field.range.invalid",
            "DecimalMax,      field.range.invalid",
            "Email,           field.email.invalid",
            "Pattern,         field.format.invalid",
            "Positive,        field.range.invalid",
            "PositiveOrZero,  field.range.invalid",
            "Negative,        field.range.invalid",
            "NegativeOrZero,  field.range.invalid",
            "Digits,          field.format.invalid",
            "Past,            field.range.invalid",
            "PastOrPresent,   field.range.invalid",
            "Future,          field.range.invalid",
            "FutureOrPresent, field.range.invalid"
    })
    void toApplicationCode_mapsKnownConstraints(String jakartaCode, String expectedAppCode) {
        assertThat(JakartaCodeTranslator.toApplicationCode(jakartaCode)).isEqualTo(expectedAppCode);
    }

    /** Unknown constraint name → generic {@code field.invalid} fallback. */
    @Test
    void toApplicationCode_unknownConstraint_returnsGenericFallback() {
        assertThat(JakartaCodeTranslator.toApplicationCode("CustomMadeUpAnnotation"))
                .isEqualTo("field.invalid");
    }

    /** {@code null} → fallback (defensive — Spring sometimes hands us null). */
    @Test
    void toApplicationCode_null_returnsGenericFallback() {
        assertThat(JakartaCodeTranslator.toApplicationCode(null)).isEqualTo("field.invalid");
    }
}
