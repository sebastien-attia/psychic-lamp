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

    /** Clean inputs produce no findings. */
    @Test
    void validate_returns_empty_for_normal_name() {
        assertThat(validator.validate("Black Pearl", "A pirate ship")).isEmpty();
    }

    /** A {@code null} name is treated as no finding here (syntactic layer rejects it as 400). */
    @Test
    void validate_returns_empty_for_null_name() {
        assertThat(validator.validate(null, null)).isEmpty();
    }

    /** Exact-case "FORBIDDEN" substring trips the rule. */
    @Test
    void validate_flags_uppercase_forbidden_token() {
        List<ValidationMessage> messages = validator.validate("FORBIDDEN", null);
        assertThat(messages).hasSize(1);
        ValidationMessage m = messages.get(0);
        assertThat(m.severity()).isEqualTo(Severity.ERROR);
        assertThat(m.type()).isEqualTo(MessageType.INVALID_FORMAT);
        assertThat(m.field()).isEqualTo("Boat.name");
    }

    /** Mixed-case "Forbidden" trips the rule (case-insensitive matching). */
    @Test
    void validate_flags_mixed_case_forbidden_token() {
        assertThat(validator.validate("Forbidden boat", null))
                .extracting(ValidationMessage::type)
                .containsExactly(MessageType.INVALID_FORMAT);
    }

    /** Substring match: rule fires when "forbidden" appears anywhere in name. */
    @Test
    void validate_flags_forbidden_substring() {
        assertThat(validator.validate("My-Forbidden-Vessel", null)).hasSize(1);
    }
}
