package ch.owt.boatapp.domain;

import ch.owt.boatapp.domain.model.ServiceResponse;
import ch.owt.boatapp.domain.model.validation.MessageType;
import ch.owt.boatapp.domain.model.validation.Severity;
import ch.owt.boatapp.domain.model.validation.ValidationMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure-JUnit unit tests for {@link ServiceResponse}.
 *
 * <p>Pins the success-envelope invariant added when severity-aware
 * persistence shipped: {@link ServiceResponse#success(Object, List)} must
 * reject any input list that carries an {@link Severity#ERROR}-severity
 * entry, so a "success" envelope can never silently report
 * {@code hasErrors() == true}.
 */
class ServiceResponseTest {

    /** Success with a list of advisory messages is accepted as-is. */
    @Test
    void success_withAdvisoryMessages_isAccepted() {
        List<ValidationMessage> advisory = List.of(
                new ValidationMessage(Severity.WARNING, MessageType.NAME_TOO_SHORT, "Boat.name"),
                new ValidationMessage(Severity.INFO, MessageType.DESCRIPTION_MISSING, "Boat.description"));

        ServiceResponse<String> response = ServiceResponse.success("ok", advisory);

        assertThat(response.hasErrors()).isFalse();
        assertThat(response.messages()).hasSize(2);
    }

    /** Success rejects an ERROR-severity entry to preserve the envelope invariant. */
    @Test
    void success_withErrorMessage_throwsIllegalArgument() {
        List<ValidationMessage> withError = List.of(
                new ValidationMessage(Severity.ERROR, MessageType.INVALID_FORMAT, "Boat.name"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> ServiceResponse.success("ok", withError))
                .withMessageContaining("ERROR-severity");
    }

    /** Success rejects a list mixing ERROR with advisory entries. */
    @Test
    void success_withMixedSeverityIncludingError_throwsIllegalArgument() {
        List<ValidationMessage> mixed = List.of(
                new ValidationMessage(Severity.WARNING, MessageType.NAME_TOO_SHORT, "Boat.name"),
                new ValidationMessage(Severity.ERROR, MessageType.INVALID_FORMAT, "Boat.name"));

        assertThatThrownBy(() -> ServiceResponse.success("ok", mixed))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Mutating the source list after construction must NOT affect the envelope's view. */
    @Test
    void messagesList_isDefensiveCopy_notLiveView() {
        List<ValidationMessage> source = new ArrayList<>();
        source.add(new ValidationMessage(Severity.INFO, MessageType.DESCRIPTION_MISSING, "Boat.description"));

        ServiceResponse<String> response = ServiceResponse.success("ok", source);
        source.clear();

        assertThat(response.messages())
                .as("ServiceResponse.messages() must be a defensive copy, not a live view of the caller's list")
                .hasSize(1);
    }
}
