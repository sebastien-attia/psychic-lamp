package ch.owt.boatapp.domain.service.validation;

import ch.owt.boatapp.domain.model.validation.MessageType;
import ch.owt.boatapp.domain.model.validation.Severity;
import ch.owt.boatapp.domain.model.validation.ValidationMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the defense-in-depth rules of {@link SyntacticValidator}. Bean
 * Validation at the REST adapter is the primary gate; this validator catches
 * non-REST callers (CLI, queue consumer, integration test). The 64-char
 * name limit and 256-char description limit MUST stay in lockstep with the
 * Jakarta {@code @Size} annotations on the request DTOs (enforced by the
 * OpenAPI contract).
 */
class SyntacticValidatorTest {

    private final SyntacticValidator validator = new SyntacticValidator();

    /** Happy path: well-formed name and description → no findings. */
    @Test
    void validate_returnsEmpty_forValidInputs() {
        assertThat(validator.validate("Black Pearl", "A pirate ship")).isEmpty();
    }

    /** {@code null} description is valid (it's optional). */
    @Test
    void validate_acceptsNullDescription() {
        assertThat(validator.validate("Argo", null)).isEmpty();
    }

    /** {@code null} or blank name → CANNOT_BE_BLANK on Boat.name. */
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t", "\n", "   "})
    void validate_flagsBlankName(String blank) {
        List<ValidationMessage> messages = validator.validate(blank, null);

        assertThat(messages).hasSize(1);
        ValidationMessage m = messages.get(0);
        assertThat(m.severity()).isEqualTo(Severity.ERROR);
        assertThat(m.type()).isEqualTo(MessageType.CANNOT_BE_BLANK);
        assertThat(m.field()).isEqualTo("Boat.name");
    }

    /** Null name treated like blank: same CANNOT_BE_BLANK finding. */
    @Test
    void validate_flagsNullName() {
        List<ValidationMessage> messages = validator.validate(null, null);

        assertThat(messages)
                .extracting(ValidationMessage::type)
                .containsExactly(MessageType.CANNOT_BE_BLANK);
    }

    /** Name at the boundary (64 chars) is valid. */
    @Test
    void validate_acceptsNameAtMaxLength() {
        String name = "x".repeat(SyntacticValidator.NAME_MAX_LENGTH);
        assertThat(validator.validate(name, null)).isEmpty();
    }

    /** Name one over the boundary (65 chars) → SIZE_EXCEEDED on Boat.name. */
    @Test
    void validate_flagsOversizeName() {
        String name = "x".repeat(SyntacticValidator.NAME_MAX_LENGTH + 1);
        List<ValidationMessage> messages = validator.validate(name, null);

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).type()).isEqualTo(MessageType.SIZE_EXCEEDED);
        assertThat(messages.get(0).field()).isEqualTo("Boat.name");
    }

    /** Description at the boundary (256 chars) is valid. */
    @Test
    void validate_acceptsDescriptionAtMaxLength() {
        String description = "x".repeat(SyntacticValidator.DESCRIPTION_MAX_LENGTH);
        assertThat(validator.validate("OK", description)).isEmpty();
    }

    /** Description one over the boundary → SIZE_EXCEEDED on Boat.description. */
    @Test
    void validate_flagsOversizeDescription() {
        String description = "x".repeat(SyntacticValidator.DESCRIPTION_MAX_LENGTH + 1);
        List<ValidationMessage> messages = validator.validate("OK", description);

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).type()).isEqualTo(MessageType.SIZE_EXCEEDED);
        assertThat(messages.get(0).field()).isEqualTo("Boat.description");
    }

    /** Both fields invalid → both findings, in order. */
    @Test
    void validate_reportsAllFindings() {
        String name = "x".repeat(100);
        String description = "y".repeat(300);
        List<ValidationMessage> messages = validator.validate(name, description);

        assertThat(messages)
                .extracting(ValidationMessage::field)
                .containsExactly("Boat.name", "Boat.description");
    }

    /** The returned list is unmodifiable — defense against caller mutation. */
    @Test
    void validate_returnsUnmodifiableList() {
        List<ValidationMessage> messages = validator.validate(null, null);
        ValidationMessage extra = new ValidationMessage(
                Severity.ERROR, MessageType.INVALID_FORMAT, "fake");

        assertThatThrownBy(() -> messages.add(extra))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
