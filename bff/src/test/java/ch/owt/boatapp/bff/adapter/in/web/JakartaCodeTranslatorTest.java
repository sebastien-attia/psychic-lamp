package ch.owt.boatapp.bff.adapter.in.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-Java parameterized tests for {@link JakartaCodeTranslator} on the BFF
 * side. Mirrors {@code JakartaCodeTranslatorTest} in the Business Service —
 * the two translator copies must stay in lockstep, so the same assertions
 * (including the {@code null} short-circuit) live on both sides.
 *
 * <p>Pinning the {@code null} case prevents the BFF from regressing to the
 * NPE that the Business Service copy was patched to avoid (see the inline
 * comment in {@link JakartaCodeTranslator#toApplicationCode(String)}).
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
