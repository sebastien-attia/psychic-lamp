package ch.owt.boatapp.domain.model;

import ch.owt.boatapp.domain.model.validation.MessageType;
import ch.owt.boatapp.domain.model.validation.Severity;
import ch.owt.boatapp.domain.model.validation.ValidationMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the success/failure contract of {@link ServiceResponse} — the envelope
 * that the application service translates to either a use-case result or a
 * {@code ValidationFailureException} (HTTP 422). The {@link #messages()}
 * list MUST be unmodifiable so that downstream code (the bridge layer)
 * cannot mutate state across the domain boundary.
 */
class ServiceResponseTest {

    /** {@code success(data)} carries the data and an empty messages list. */
    @Test
    void success_carriesDataAndEmptyMessages() {
        ServiceResponse<String> response = ServiceResponse.success("ok");

        assertThat(response.data()).isEqualTo("ok");
        assertThat(response.messages()).isEmpty();
        assertThat(response.hasErrors()).isFalse();
    }

    /** {@code success(null)} is valid (used by void-return use-cases). */
    @Test
    void success_acceptsNullData() {
        ServiceResponse<Void> response = ServiceResponse.success(null);

        assertThat(response.data()).isNull();
        assertThat(response.messages()).isEmpty();
    }

    /** {@code failure(messages)} carries {@code data == null} and the supplied messages. */
    @Test
    void failure_carriesNullDataAndMessages() {
        ValidationMessage error = new ValidationMessage(
                Severity.ERROR, MessageType.CANNOT_BE_BLANK, "Boat.name");
        ServiceResponse<String> response = ServiceResponse.failure(List.of(error));

        assertThat(response.data()).isNull();
        assertThat(response.messages()).containsExactly(error);
        assertThat(response.hasErrors()).isTrue();
    }

    /** A WARNING-only response is not considered an error. */
    @Test
    void hasErrors_isFalseForWarningOnlyMessages() {
        ValidationMessage warn = new ValidationMessage(
                Severity.WARNING, MessageType.INVALID_FORMAT, "Boat.description");
        ServiceResponse<String> response = ServiceResponse.failure(List.of(warn));

        assertThat(response.hasErrors()).isFalse();
    }

    /** A mixed list with at least one ERROR makes {@code hasErrors()} true. */
    @Test
    void hasErrors_isTrueIfAnyMessageIsError() {
        ValidationMessage info = new ValidationMessage(Severity.INFO, MessageType.INVALID_FORMAT, "f");
        ValidationMessage error = new ValidationMessage(Severity.ERROR, MessageType.SIZE_EXCEEDED, "f");
        ServiceResponse<String> response = ServiceResponse.failure(List.of(info, error));

        assertThat(response.hasErrors()).isTrue();
    }

    /** The exposed messages list is unmodifiable — defensive copy semantics. */
    @Test
    void messagesList_isUnmodifiable() {
        ValidationMessage error = new ValidationMessage(Severity.ERROR, MessageType.CANNOT_BE_BLANK, "f");
        ServiceResponse<String> response = ServiceResponse.failure(List.of(error));

        assertThatThrownBy(() -> response.messages().add(error))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
