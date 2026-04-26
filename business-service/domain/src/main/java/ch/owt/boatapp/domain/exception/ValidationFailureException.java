package ch.owt.boatapp.domain.exception;

import ch.owt.boatapp.domain.model.validation.ValidationMessage;

import java.util.Collections;
import java.util.List;

/**
 * Bridge exception between the domain's {@code ServiceResponse} failure mode
 * and the REST adapter's HTTP 422 response.
 *
 * <p>Thrown by {@code BoatApplicationService} when a use-case returns a
 * {@code ServiceResponse} with {@code hasErrors() == true}. The web adapter's
 * {@code GlobalExceptionHandler} maps it to HTTP 422 with the problem type
 * {@code https://boatapp.owt.ch/problems/validation} and populates
 * {@code ProblemDetail.messages[]} from {@link #getMessages()}.
 *
 * <p>Pure-Java unchecked exception — no Spring, no Jakarta. Being a
 * {@link RuntimeException} it triggers Spring's default {@code @Transactional}
 * rollback so audit-row inserts roll back together with the failed mutation.
 */
public class ValidationFailureException extends RuntimeException {

    private final List<ValidationMessage> messages;

    /**
     * @param messages the validation findings carried by the failing
     *                 {@code ServiceResponse} (never {@code null}; defensively
     *                 stored as an unmodifiable copy)
     */
    public ValidationFailureException(List<ValidationMessage> messages) {
        super("Domain validation failed: " + messages.size() + " message(s)");
        this.messages = Collections.unmodifiableList(messages);
    }

    /** @return the validation findings (unmodifiable, never {@code null}) */
    public List<ValidationMessage> getMessages() {
        return messages;
    }
}
