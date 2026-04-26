package ch.owt.boatapp.domain.model.validation;

/**
 * A single validation finding, produced by domain validators or by adapter
 * translation of Jakarta Bean Validation errors.
 *
 * <p>The {@code field} is a dotted path identifying the offending property
 * (e.g. {@code "Boat.name"}, {@code "Boat.description"}); {@code null} for
 * cross-field or global findings.
 *
 * @param severity severity of this finding
 * @param type     kind of finding — provides the wire-safe application code
 * @param field    dotted field path, or {@code null} for global findings
 */
public record ValidationMessage(Severity severity, MessageType type, String field) {
}
