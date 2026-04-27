package ch.owt.boatapp.domain;

import ch.owt.boatapp.domain.model.validation.MessageType;
import ch.owt.boatapp.domain.model.validation.Severity;
import ch.owt.boatapp.domain.model.validation.ValidationMessage;
import ch.owt.boatapp.domain.service.validation.SemanticValidator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-JUnit unit test for {@link SemanticValidator}.
 *
 * <p>Pins the demonstration {@code FORBIDDEN}-token rule: ensures the
 * SemanticValidator → 422 pipeline has a deterministic trigger that is also
 * exercised by the HTTP-layer integration test
 * {@code ValidationAndErrorsIntegrationTest#semanticDomainFailure_returns422}.
 * If the rule is removed or weakened, both tests must change in lockstep.
 */
class SemanticValidatorTest {

    private final SemanticValidator validator = new SemanticValidator();

    /** Clean inputs (with a description) produce no findings. */
    @Test
    void validate_returns_empty_for_normal_name_and_description() {
        assertThat(validator.validate("Black Pearl", "A pirate ship")).isEmpty();
    }

    /** A {@code null} name with a description produces no findings (syntactic layer rejects null name as 400). */
    @Test
    void validate_returns_empty_for_null_name_with_description() {
        assertThat(validator.validate(null, "A pirate ship")).isEmpty();
    }

    /** Exact-case "FORBIDDEN" substring trips the rule. */
    @Test
    void validate_flags_uppercase_forbidden_token() {
        // Pass a non-blank description so the assertion isolates the
        // FORBIDDEN-token rule from the DESCRIPTION_MISSING advisory.
        List<ValidationMessage> messages = validator.validate("FORBIDDEN", "ok");
        assertThat(messages).hasSize(1);
        ValidationMessage m = messages.get(0);
        assertThat(m.severity()).isEqualTo(Severity.ERROR);
        assertThat(m.type()).isEqualTo(MessageType.INVALID_FORMAT);
        assertThat(m.field()).isEqualTo("Boat.name");
    }

    /** Mixed-case "Forbidden" trips the rule (case-insensitive matching). */
    @Test
    void validate_flags_mixed_case_forbidden_token() {
        assertThat(validator.validate("Forbidden boat", "ok"))
                .extracting(ValidationMessage::type)
                .containsExactly(MessageType.INVALID_FORMAT);
    }

    /** Substring match: rule fires when "forbidden" appears anywhere in name. */
    @Test
    void validate_flags_forbidden_substring() {
        assertThat(validator.validate("My-Forbidden-Vessel", "ok")).hasSize(1);
    }

    /** A {@code null} description triggers the {@code DESCRIPTION_MISSING} INFO advisory. */
    @Test
    void validate_null_description_returns_descriptionMissing_info() {
        List<ValidationMessage> messages = validator.validate("Black Pearl", null);
        assertThat(messages).hasSize(1);
        ValidationMessage m = messages.get(0);
        assertThat(m.severity()).isEqualTo(Severity.INFO);
        assertThat(m.type()).isEqualTo(MessageType.DESCRIPTION_MISSING);
        assertThat(m.field()).isEqualTo("Boat.description");
    }

    /** A whitespace-only description also triggers the INFO advisory. */
    @Test
    void validate_blank_description_returns_descriptionMissing_info() {
        assertThat(validator.validate("Black Pearl", "   "))
                .extracting(ValidationMessage::type)
                .containsExactly(MessageType.DESCRIPTION_MISSING);
    }

    /** Forbidden name + missing description → both findings, ERROR alongside INFO. */
    @Test
    void validate_forbidden_name_and_missing_description_returns_both_findings() {
        List<ValidationMessage> messages = validator.validate("FORBIDDEN-X", null);
        assertThat(messages)
                .extracting(ValidationMessage::severity)
                .containsExactlyInAnyOrder(Severity.ERROR, Severity.INFO);
    }
}
