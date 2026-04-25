package ch.owt.boatapp.domain.service.validation;

import ch.owt.boatapp.domain.model.validation.ValidationMessage;

import java.util.List;

/**
 * Domain-side validator for business rules that go beyond simple syntactic
 * checks (e.g. uniqueness of {@code Boat.name}, state-dependent rules).
 *
 * <p>Currently a placeholder: returns no findings. The contract is fixed so
 * future rules can be added without touching call sites in
 * {@code BoatDomainService}. Any rule added here surfaces as HTTP 422
 * via the bridge layer's {@code ValidationFailureException} translation.
 *
 * <p>Pure-Java — only {@code java.util.*} and the pure-Java validation types
 * are imported.
 */
public class SemanticValidator {

    /**
     * Validate the semantic constraints of a boat's {@code name} and
     * {@code description} fields.
     *
     * <p>Currently always returns an empty list — no semantic rules defined yet.
     *
     * @param name        the proposed name
     * @param description the proposed description (may be {@code null})
     * @return an empty unmodifiable list (placeholder for future rules)
     */
    public List<ValidationMessage> validate(String name, String description) {
        return List.of();
    }
}
