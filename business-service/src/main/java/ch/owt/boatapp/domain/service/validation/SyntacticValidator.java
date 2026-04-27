package ch.owt.boatapp.domain.service.validation;

import ch.owt.boatapp.domain.model.validation.MessageType;
import ch.owt.boatapp.domain.model.validation.Severity;
import ch.owt.boatapp.domain.model.validation.ValidationMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Domain-side defense-in-depth validator for boat input fields.
 *
 * <p>Defense-in-depth, NOT primary: the REST adapter's Jakarta Bean Validation
 * is the primary syntactic gate (HTTP 400). This validator still runs inside
 * {@code BoatDomainService} so the domain is self-protecting when invoked from
 * a non-REST adapter (CLI, queue consumer, integration test) where Bean
 * Validation never ran.
 *
 * <p>Pure-Java — only {@code java.util.*} and the pure-Java validation types
 * are imported.
 */
public class SyntacticValidator {

    /** Maximum allowed length of {@code Boat.name}. */
    public static final int NAME_MAX_LENGTH = 64;

    /** Maximum allowed length of {@code Boat.description}. */
    public static final int DESCRIPTION_MAX_LENGTH = 256;

    /**
     * Threshold below which a non-blank {@code Boat.name} is flagged with a
     * {@code WARNING} (advisory, not blocking). Names of fewer than this many
     * non-whitespace characters are persisted but accompanied by a
     * {@link MessageType#NAME_TOO_SHORT} message.
     */
    public static final int NAME_SHORT_THRESHOLD = 3;

    private static final String FIELD_NAME = "Boat.name";
    private static final String FIELD_DESCRIPTION = "Boat.description";

    /**
     * Validate the syntactic shape of a boat's {@code name} and
     * {@code description} fields.
     *
     * <p>Rules:
     * <ul>
     *   <li>{@code name} must not be {@code null} or whitespace-only
     *       ({@link MessageType#CANNOT_BE_BLANK}, ERROR).</li>
     *   <li>{@code name} must be at most {@value #NAME_MAX_LENGTH} characters
     *       ({@link MessageType#SIZE_EXCEEDED}, ERROR).</li>
     *   <li>{@code name}, if non-blank and shorter than
     *       {@value #NAME_SHORT_THRESHOLD} non-whitespace characters, is
     *       flagged with {@link MessageType#NAME_TOO_SHORT} (WARNING). The
     *       boat is still persisted; this is advisory.</li>
     *   <li>{@code description} (if non-null) must be at most
     *       {@value #DESCRIPTION_MAX_LENGTH} characters
     *       ({@link MessageType#SIZE_EXCEEDED}, ERROR).</li>
     * </ul>
     *
     * @param name        the proposed name
     * @param description the proposed description (may be {@code null})
     * @return an unmodifiable list of findings (empty if all checks pass)
     */
    public List<ValidationMessage> validate(String name, String description) {
        List<ValidationMessage> messages = new ArrayList<>();
        if (name == null || name.isBlank()) {
            messages.add(new ValidationMessage(Severity.ERROR, MessageType.CANNOT_BE_BLANK, FIELD_NAME));
        } else if (name.length() > NAME_MAX_LENGTH) {
            messages.add(new ValidationMessage(Severity.ERROR, MessageType.SIZE_EXCEEDED, FIELD_NAME));
        } else if (name.trim().length() < NAME_SHORT_THRESHOLD) {
            // Advisory: persisted with a warning so the caller can surface it
            // as a soft hint without rejecting the request.
            messages.add(new ValidationMessage(Severity.WARNING, MessageType.NAME_TOO_SHORT, FIELD_NAME));
        }
        if (description != null && description.length() > DESCRIPTION_MAX_LENGTH) {
            messages.add(new ValidationMessage(Severity.ERROR, MessageType.SIZE_EXCEEDED, FIELD_DESCRIPTION));
        }
        return Collections.unmodifiableList(messages);
    }
}
