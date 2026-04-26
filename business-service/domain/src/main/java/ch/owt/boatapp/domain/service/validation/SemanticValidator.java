package ch.owt.boatapp.domain.service.validation;

import ch.owt.boatapp.domain.model.validation.MessageType;
import ch.owt.boatapp.domain.model.validation.Severity;
import ch.owt.boatapp.domain.model.validation.ValidationMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Domain-side validator for business rules that go beyond simple syntactic
 * checks (e.g. uniqueness of {@code Boat.name}, state-dependent rules,
 * blacklisted names).
 *
 * <p>Currently encodes a single demonstration rule: a boat {@code name} that
 * contains the substring {@code "FORBIDDEN"} (case-insensitive) is rejected
 * with {@link MessageType#INVALID_FORMAT}. The rule exists to give the
 * pipeline a deterministic, end-to-end test fixture for the
 * {@code SemanticValidator → BoatDomainService → ValidationFailureException
 * → HTTP 422} path. Real business rules (uniqueness, cross-aggregate
 * invariants) will be added here as the model grows.
 *
 * <p>Pure-Java — only {@code java.util.*} and the pure-Java validation types
 * are imported. ArchUnit forbids any {@code jakarta.*} or
 * {@code org.springframework.*} import in this package.
 */
public class SemanticValidator {

    private static final String FORBIDDEN_TOKEN = "FORBIDDEN";

    /**
     * Validate the semantic constraints of a boat's {@code name} and
     * {@code description} fields.
     *
     * <p>Currently:
     * <ul>
     *   <li>{@code name} containing the substring {@code "FORBIDDEN"}
     *       (case-insensitive) → one {@link Severity#ERROR} message of
     *       type {@link MessageType#INVALID_FORMAT} on field
     *       {@code "Boat.name"}.</li>
     *   <li>All other inputs (including {@code null}) → no messages.</li>
     * </ul>
     *
     * @param name        the proposed name (may be {@code null})
     * @param description the proposed description (may be {@code null})
     * @return an unmodifiable list of validation findings; empty when the
     *         input passes every semantic rule
     */
    public List<ValidationMessage> validate(String name, String description) {
        List<ValidationMessage> messages = new ArrayList<>();
        if (name != null && name.toUpperCase(Locale.ROOT).contains(FORBIDDEN_TOKEN)) {
            messages.add(new ValidationMessage(
                    Severity.ERROR, MessageType.INVALID_FORMAT, "Boat.name"));
        }
        return Collections.unmodifiableList(messages);
    }
}
