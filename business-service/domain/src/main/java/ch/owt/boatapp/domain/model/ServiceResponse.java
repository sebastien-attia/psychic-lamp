package ch.owt.boatapp.domain.model;

import ch.owt.boatapp.domain.model.validation.Severity;
import ch.owt.boatapp.domain.model.validation.ValidationMessage;

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
        // List.copyOf gives a truly unmodifiable, defensively-copied list and
        // fast-fails on null elements, so callers cannot mutate the envelope's
        // messages by retaining a reference to the original list.
        this.messages = List.copyOf(messages);
    }

    /**
     * Build a successful response carrying {@code data} and no messages.
     *
     * @param data the result value (may be {@code null} if the operation has none)
     * @param <T>  the result type
     * @return a success response with an empty messages list
     */
    public static <T> ServiceResponse<T> success(T data) {
        return success(data, List.of());
    }

    /**
     * Build a successful response carrying {@code data} and any non-blocking
     * advisories (INFO/WARNING messages) the use case wants to surface on a
     * 2xx response. Throws if any {@link Severity#ERROR ERROR} entry is
     * present — those must go through {@link #failure(List)} so the bridge
     * translates them to a 422.
     *
     * @param data     the result value (may be {@code null})
     * @param messages the advisories to carry (never {@code null}; may be empty)
     * @param <T>      the result type
     * @return a success response carrying both data and advisories
     * @throws IllegalArgumentException if {@code messages} contains an
     *         {@link Severity#ERROR ERROR} entry
     */
    public static <T> ServiceResponse<T> success(T data, List<ValidationMessage> messages) {
        if (hasErrors(messages)) {
            throw new IllegalArgumentException(
                    "ServiceResponse.success(...) must not carry ERROR-severity messages — use failure(...)");
        }
        return new ServiceResponse<>(data, messages);
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
        return hasErrors(messages);
    }

    /**
     * Test whether a list of {@link ValidationMessage} contains a blocking
     * {@link Severity#ERROR ERROR} entry. Use cases call this to decide
     * whether validator output should short-circuit to a failure response,
     * so a list containing only {@link Severity#INFO INFO} or
     * {@link Severity#WARNING WARNING} messages does not block the operation.
     *
     * @param messages the messages to inspect (never {@code null})
     * @return {@code true} if at least one message carries ERROR severity
     */
    public static boolean hasErrors(List<ValidationMessage> messages) {
        return messages.stream().anyMatch(m -> m.severity() == Severity.ERROR);
    }
}
