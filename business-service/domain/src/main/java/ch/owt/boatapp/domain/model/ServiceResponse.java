package ch.owt.boatapp.domain.model;

import ch.owt.boatapp.domain.model.validation.Severity;
import ch.owt.boatapp.domain.model.validation.ValidationMessage;

import java.util.Collections;
import java.util.List;

/**
 * Generic envelope returned by use-case operations that may either succeed
 * with a result or fail with a list of {@link ValidationMessage}s.
 *
 * <p>Used for create / update flows where the caller (the bridge layer in
 * {@code infrastructure.service}) translates a failure into a
 * {@code ValidationFailureException} → HTTP 422. Read / delete paths throw
 * domain exceptions directly and do not need this envelope.
 *
 * <p>Pure-Java — only {@code java.util.*} and the pure-Java validation types
 * are imported. The {@link #messages} list is unmodifiable.
 *
 * @param <T> the type of the result on success
 */
public final class ServiceResponse<T> {

    private final T data;
    private final List<ValidationMessage> messages;

    private ServiceResponse(T data, List<ValidationMessage> messages) {
        this.data = data;
        this.messages = Collections.unmodifiableList(messages);
    }

    /**
     * Build a successful response carrying {@code data} and no messages.
     *
     * @param data the result value (may be {@code null} if the operation has none)
     * @param <T>  the result type
     * @return a success response with an empty messages list
     */
    public static <T> ServiceResponse<T> success(T data) {
        return new ServiceResponse<>(data, List.of());
    }

    /**
     * Build a failure response carrying validation messages and no data.
     *
     * @param messages the validation findings (never {@code null})
     * @param <T>      the would-be result type
     * @return a failure response with {@code data == null}
     */
    public static <T> ServiceResponse<T> failure(List<ValidationMessage> messages) {
        return new ServiceResponse<>(null, messages);
    }

    /** @return the result value on success, or {@code null} on failure */
    public T data() {
        return data;
    }

    /** @return the validation findings (unmodifiable, never {@code null}; empty on success) */
    public List<ValidationMessage> messages() {
        return messages;
    }

    /**
     * @return {@code true} if any of the {@link #messages} carries
     *         {@link Severity#ERROR ERROR} severity
     */
    public boolean hasErrors() {
        return messages.stream().anyMatch(m -> m.severity() == Severity.ERROR);
    }
}
