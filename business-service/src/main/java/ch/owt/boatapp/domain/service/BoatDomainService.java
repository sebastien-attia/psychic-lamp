package ch.owt.boatapp.domain.service;

import ch.owt.boatapp.domain.exception.BoatNotFoundException;
import ch.owt.boatapp.domain.exception.ConcurrentModificationException;
import ch.owt.boatapp.domain.model.AuditAction;
import ch.owt.boatapp.domain.model.Boat;
import ch.owt.boatapp.domain.model.BoatAudit;
import ch.owt.boatapp.domain.model.PageResult;
import ch.owt.boatapp.domain.model.ServiceResponse;
import ch.owt.boatapp.domain.model.validation.Severity;
import ch.owt.boatapp.domain.model.validation.ValidationMessage;
import ch.owt.boatapp.domain.port.in.CreateBoatCommand;
import ch.owt.boatapp.domain.port.in.DeleteBoatCommand;
import ch.owt.boatapp.domain.port.in.GetBoatQuery;
import ch.owt.boatapp.domain.port.in.ListBoatsQuery;
import ch.owt.boatapp.domain.port.in.ManageBoatsUseCase;
import ch.owt.boatapp.domain.port.in.UpdateBoatCommand;
import ch.owt.boatapp.domain.port.out.BoatAuditRepositoryPort;
import ch.owt.boatapp.domain.port.out.BoatRepositoryPort;
import ch.owt.boatapp.domain.service.validation.SemanticValidator;
import ch.owt.boatapp.domain.service.validation.SyntacticValidator;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Pure-Java implementation of {@link ManageBoatsUseCase}.
 *
 * <p>Receives outbound-port collaborators via constructor injection (wired by
 * {@code BeanConfig} in {@code infrastructure.config}). Carries no Spring
 * annotations: ArchUnit forbids {@code @Service}, {@code @Component},
 * {@code @Transactional} in {@code domain.*}.
 *
 * <p>Domain models are immutable records, so updates rebuild a fresh
 * {@link Boat} via its canonical constructor rather than mutating in place.
 *
 * <p>Transactional atomicity (boat mutation + audit append) is provided by
 * the bridge layer ({@code BoatApplicationService} in {@code infrastructure.service},
 * added in step 02a3); this class invokes the audit append inline so the
 * domain remains framework-free.
 *
 * <p><b>Severity-aware persistence:</b> create/update fail (return a
 * {@link ServiceResponse#failure(List) failure} envelope, no persistence)
 * only when validation produced at least one {@link Severity#ERROR ERROR}
 * message. {@link Severity#WARNING WARNING} and {@link Severity#INFO INFO}
 * messages are advisory: the boat is persisted and the messages ride along
 * in the {@link ServiceResponse#success(Object, List) success} envelope so
 * the caller can surface them in a 2xx response body.
 */
public final class BoatDomainService implements ManageBoatsUseCase {

    private final BoatRepositoryPort boatRepository;
    private final BoatAuditRepositoryPort boatAuditRepository;
    private final SyntacticValidator syntacticValidator;
    private final SemanticValidator semanticValidator;
    private final Clock clock;

    /**
     * @param boatRepository      outbound port for {@link Boat} persistence
     * @param boatAuditRepository outbound port for {@link BoatAudit} appends (INSERT-ONLY)
     * @param syntacticValidator  defense-in-depth syntactic validator
     * @param semanticValidator   business-rule validator (placeholder today)
     * @param clock               clock used for {@code createdAt} and
     *                            {@code performedAt} timestamps (UTC in production)
     */
    public BoatDomainService(BoatRepositoryPort boatRepository,
                             BoatAuditRepositoryPort boatAuditRepository,
                             SyntacticValidator syntacticValidator,
                             SemanticValidator semanticValidator,
                             Clock clock) {
        this.boatRepository = boatRepository;
        this.boatAuditRepository = boatAuditRepository;
        this.syntacticValidator = syntacticValidator;
        this.semanticValidator = semanticValidator;
        this.clock = clock;
    }

    @Override
    public PageResult<Boat> listBoats(ListBoatsQuery query) {
        if (query.search() != null && !query.search().isBlank()) {
            return boatRepository.search(query.search(), query.page(), query.size(),
                    query.sortBy(), query.sortDir());
        }
        return boatRepository.findAll(query.page(), query.size(), query.sortBy(), query.sortDir());
    }

    @Override
    public Boat getBoat(GetBoatQuery query) {
        return boatRepository.findById(query.id().value())
                .orElseThrow(() -> new BoatNotFoundException(query.id()));
    }

    @Override
    public ServiceResponse<Boat> createBoat(CreateBoatCommand command) {
        List<ValidationMessage> messages = collectValidationMessages(command.name(), command.description());
        if (hasError(messages)) {
            return ServiceResponse.failure(messages);
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        Boat newBoat = new Boat(UUID.randomUUID(), command.name(), command.description(), now, null);
        Boat saved = boatRepository.save(newBoat);
        appendAudit(saved, AuditAction.CREATED, command.performedBy().value(), now);
        return ServiceResponse.success(saved, messages);
    }

    @Override
    public ServiceResponse<Boat> updateBoat(UpdateBoatCommand command) {
        // Validate before loading: a 422 response on malformed input must be
        // reachable even when the id happens not to resolve (which would
        // otherwise short-circuit to 404 and hide the validation findings).
        List<ValidationMessage> messages = collectValidationMessages(command.name(), command.description());
        if (hasError(messages)) {
            return ServiceResponse.failure(messages);
        }

        Boat existing = boatRepository.findById(command.id().value())
                .orElseThrow(() -> new BoatNotFoundException(command.id()));

        // Explicit domain-level optimistic-lock check: the domain owns the
        // version invariant rather than relying on Hibernate to detect the
        // mismatch at flush. JPA's @Version remains as defense-in-depth at
        // the persistence layer (the web adapter maps both exceptions to
        // HTTP 409).
        if (!Objects.equals(existing.version(), command.expectedVersion())) {
            throw new ConcurrentModificationException(command.id(),
                    command.expectedVersion(), existing.version());
        }

        // Records are immutable: rebuild rather than mutate. `id` and
        // `createdAt` are carried over from the loaded record; `version` is
        // the caller-supplied expectedVersion (== existing.version() at this
        // point) so Hibernate sees the same value it persisted.
        Boat updated = new Boat(existing.id(), command.name(), command.description(),
                existing.createdAt(), command.expectedVersion());
        Boat saved = boatRepository.save(updated);
        OffsetDateTime now = OffsetDateTime.now(clock);
        appendAudit(saved, AuditAction.UPDATED, command.performedBy().value(), now);
        return ServiceResponse.success(saved, messages);
    }

    @Override
    public void deleteBoat(DeleteBoatCommand command) {
        Boat existing = boatRepository.findById(command.id().value())
                .orElseThrow(() -> new BoatNotFoundException(command.id()));
        OffsetDateTime now = OffsetDateTime.now(clock);
        appendAudit(existing, AuditAction.DELETED, command.performedBy().value(), now);
        boatRepository.deleteById(command.id().value());
    }

    private List<ValidationMessage> collectValidationMessages(String name, String description) {
        List<ValidationMessage> messages = new ArrayList<>();
        messages.addAll(syntacticValidator.validate(name, description));
        messages.addAll(semanticValidator.validate(name, description));
        return messages;
    }

    private static boolean hasError(List<ValidationMessage> messages) {
        // Persistence is gated on ERROR severity only — WARNING and INFO are
        // advisory and ride along with the success envelope so the caller can
        // surface them in a 2xx response body.
        return messages.stream().anyMatch(m -> m.severity() == Severity.ERROR);
    }

    private void appendAudit(Boat boat, AuditAction action, UUID performedByUserId, OffsetDateTime performedAt) {
        BoatAudit audit = new BoatAudit(
                null, boat.id(), action, boat.name(), boat.description(),
                boat.version(), performedByUserId, performedAt
        );
        boatAuditRepository.save(audit);
    }
}
