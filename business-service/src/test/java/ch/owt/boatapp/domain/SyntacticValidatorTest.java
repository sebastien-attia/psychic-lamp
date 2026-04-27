package ch.owt.boatapp.domain;

import ch.owt.boatapp.domain.model.validation.MessageType;
import ch.owt.boatapp.domain.model.validation.Severity;
import ch.owt.boatapp.domain.model.validation.ValidationMessage;
import ch.owt.boatapp.domain.service.validation.SyntacticValidator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Pure-JUnit unit tests for {@link SyntacticValidator}.
 *
 * <p>Locks the documented rule set: ERROR-severity findings for blank /
 * over-sized fields, plus the advisory {@link MessageType#NAME_TOO_SHORT}
 * WARNING for non-blank names below
 * {@link SyntacticValidator#NAME_SHORT_THRESHOLD}. The WARNING must NOT
 * fire alongside {@code CANNOT_BE_BLANK} or {@code SIZE_EXCEEDED} —
 * advisory and blocking findings on the same field would be redundant.
 */
class SyntacticValidatorTest {

    private final SyntacticValidator validator = new SyntacticValidator();

    /** A clean payload yields no findings. */
    @Test
    void validate_returns_empty_for_clean_input() {
        assertThat(validator.validate("Black Pearl", "A pirate ship")).isEmpty();
    }

    /** A null name produces only {@code CANNOT_BE_BLANK}, no advisory. */
    @Test
    void validate_null_name_returns_cannotBeBlank_only() {
        List<ValidationMessage> messages = validator.validate(null, "validName");
        assertThat(messages)
                .extracting(ValidationMessage::severity, ValidationMessage::type)
                .containsExactly(tuple(Severity.ERROR, MessageType.CANNOT_BE_BLANK));
    }

    /** A whitespace-only name produces only {@code CANNOT_BE_BLANK}, no advisory. */
    @Test
    void validate_blank_name_returns_cannotBeBlank_only() {
        List<ValidationMessage> messages = validator.validate("   ", "validName");
        assertThat(messages)
                .extracting(ValidationMessage::severity, ValidationMessage::type)
                .containsExactly(tuple(Severity.ERROR, MessageType.CANNOT_BE_BLANK));
    }

    /** A 65-char name yields {@code SIZE_EXCEEDED} only — never the WARNING. */
    @Test
    void validate_oversize_name_returns_sizeExceeded_only() {
        String over = "x".repeat(SyntacticValidator.NAME_MAX_LENGTH + 1);
        List<ValidationMessage> messages = validator.validate(over, "validName");
        assertThat(messages)
                .extracting(ValidationMessage::severity, ValidationMessage::type)
                .containsExactly(tuple(Severity.ERROR, MessageType.SIZE_EXCEEDED));
    }

    /** A 2-char (below threshold) non-blank name yields {@code NAME_TOO_SHORT} WARNING. */
    @Test
    void validate_short_name_returns_nameTooShort_warning() {
        List<ValidationMessage> messages = validator.validate("AB", "validName");
        assertThat(messages)
                .extracting(ValidationMessage::severity, ValidationMessage::type, ValidationMessage::field)
                .containsExactly(tuple(Severity.WARNING, MessageType.NAME_TOO_SHORT, "Boat.name"));
    }

    /** Whitespace padding does not lift a 2-char name above the short threshold. */
    @Test
    void validate_short_name_with_padding_still_warns() {
        // "  AB  " trims to "AB" → 2 chars → WARNING.
        List<ValidationMessage> messages = validator.validate("  AB  ", "validName");
        assertThat(messages)
                .extracting(ValidationMessage::type)
                .containsExactly(MessageType.NAME_TOO_SHORT);
    }

    /** A name at the threshold ({@value SyntacticValidator#NAME_SHORT_THRESHOLD}) does NOT warn. */
    @Test
    void validate_name_at_threshold_returns_empty() {
        String atThreshold = "x".repeat(SyntacticValidator.NAME_SHORT_THRESHOLD);
        assertThat(validator.validate(atThreshold, "validName")).isEmpty();
    }

    /** An over-256-char description yields {@code SIZE_EXCEEDED} on {@code Boat.description}. */
    @Test
    void validate_oversize_description_returns_sizeExceeded() {
        String over = "x".repeat(SyntacticValidator.DESCRIPTION_MAX_LENGTH + 1);
        List<ValidationMessage> messages = validator.validate("validName", over);
        assertThat(messages)
                .extracting(ValidationMessage::severity, ValidationMessage::type, ValidationMessage::field)
                .containsExactly(tuple(Severity.ERROR, MessageType.SIZE_EXCEEDED, "Boat.description"));
    }
}
