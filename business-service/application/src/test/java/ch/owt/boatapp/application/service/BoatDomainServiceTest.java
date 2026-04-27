package ch.owt.boatapp.application.service;

import ch.owt.boatapp.application.port.in.CreateBoatCommand;
import ch.owt.boatapp.application.port.in.DeleteBoatCommand;
import ch.owt.boatapp.application.port.in.GetBoatQuery;
import ch.owt.boatapp.application.port.in.ListBoatsQuery;
import ch.owt.boatapp.application.port.in.UpdateBoatCommand;
import ch.owt.boatapp.application.port.out.BoatAuditRepositoryPort;
import ch.owt.boatapp.application.port.out.BoatRepositoryPort;
import ch.owt.boatapp.domain.exception.BoatNotFoundException;
import ch.owt.boatapp.domain.exception.ConcurrentModificationException;
import ch.owt.boatapp.domain.model.AuditAction;
import ch.owt.boatapp.domain.model.Boat;
import ch.owt.boatapp.domain.model.BoatAudit;
import ch.owt.boatapp.domain.model.BoatId;
import ch.owt.boatapp.domain.model.PageResult;
import ch.owt.boatapp.domain.model.ServiceResponse;
import ch.owt.boatapp.domain.model.UserId;
import ch.owt.boatapp.domain.model.validation.MessageType;
import ch.owt.boatapp.domain.model.validation.Severity;
import ch.owt.boatapp.domain.model.validation.ValidationMessage;
import ch.owt.boatapp.domain.service.validation.SemanticValidator;
import ch.owt.boatapp.domain.service.validation.SyntacticValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure-Java unit tests for {@link BoatDomainService}: pins the orchestration
 * contract that the integration tests cover only end-to-end.
 *
 * <p>Each test mocks every outbound port and validator so the service's
 * decisions (validate-before-load, audit-then-save vs. audit-after-save,
 * version comparison, exception choice) are observable in isolation.
 */
@ExtendWith(MockitoExtension.class)
class BoatDomainServiceTest {

    @Mock private BoatRepositoryPort boatRepository;
    @Mock private BoatAuditRepositoryPort boatAuditRepository;
    @Mock private SyntacticValidator syntacticValidator;
    @Mock private SemanticValidator semanticValidator;

    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-04-26T10:00:00Z"), ZoneOffset.UTC);

    private BoatDomainService service;

    private static final UserId PERFORMED_BY = new UserId(UUID.randomUUID());
    private static final BoatId BOAT_ID = new BoatId(UUID.randomUUID());

    @BeforeEach
    void setUp() {
        service = new BoatDomainService(
                boatRepository, boatAuditRepository, syntacticValidator, semanticValidator, fixedClock);
    }

    // -- listBoats --------------------------------------------------------

    /** Blank search → delegates to {@code findAll} (no LIKE query). */
    @Test
    void listBoats_blankSearch_delegatesToFindAll() {
        ListBoatsQuery query = new ListBoatsQuery(0, 10, "name", "asc", "  ");
        PageResult<Boat> page = new PageResult<>(List.of(), 0L, 0, 10, 0);
        when(boatRepository.findAll(0, 10, "name", "asc")).thenReturn(page);

        assertThat(service.listBoats(query)).isSameAs(page);
        verify(boatRepository, never()).search(any(), anyInt(), anyInt(), any(), any());
    }

    /** Non-blank search → delegates to {@code search}. */
    @Test
    void listBoats_withSearch_delegatesToSearch() {
        ListBoatsQuery query = new ListBoatsQuery(0, 10, "name", "asc", "alpha");
        PageResult<Boat> page = new PageResult<>(List.of(), 0L, 0, 10, 0);
        when(boatRepository.search("alpha", 0, 10, "name", "asc")).thenReturn(page);

        assertThat(service.listBoats(query)).isSameAs(page);
        verify(boatRepository, never()).findAll(anyInt(), anyInt(), any(), any());
    }

    // -- getBoat ---------------------------------------------------------

    /** Found → returns the loaded boat. */
    @Test
    void getBoat_found_returnsBoat() {
        Boat existing = boat(BOAT_ID.value(), "Argo", 0L);
        when(boatRepository.findById(BOAT_ID.value())).thenReturn(Optional.of(existing));

        assertThat(service.getBoat(new GetBoatQuery(BOAT_ID))).isSameAs(existing);
    }

    /** Not found → throws {@link BoatNotFoundException} carrying the id. */
    @Test
    void getBoat_missing_throwsBoatNotFound() {
        when(boatRepository.findById(BOAT_ID.value())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getBoat(new GetBoatQuery(BOAT_ID)))
                .isInstanceOf(BoatNotFoundException.class)
                .extracting(e -> ((BoatNotFoundException) e).getBoatId())
                .isEqualTo(BOAT_ID);
    }

    // -- createBoat ------------------------------------------------------

    /** Valid input → saves the boat THEN appends a CREATED audit row. */
    @Test
    void createBoat_valid_savesAndAuditsInOrder() {
        when(syntacticValidator.validate("Argo", "trireme")).thenReturn(List.of());
        when(semanticValidator.validate("Argo", "trireme")).thenReturn(List.of());
        Boat persisted = boat(UUID.randomUUID(), "Argo", 0L);
        when(boatRepository.save(any(Boat.class))).thenReturn(persisted);

        ServiceResponse<Boat> result = service.createBoat(
                new CreateBoatCommand("Argo", "trireme", PERFORMED_BY));

        assertThat(result.hasErrors()).isFalse();
        assertThat(result.data()).isSameAs(persisted);

        InOrder ordered = inOrder(boatRepository, boatAuditRepository);
        ordered.verify(boatRepository).save(any(Boat.class));
        ArgumentCaptor<BoatAudit> auditCaptor = ArgumentCaptor.forClass(BoatAudit.class);
        ordered.verify(boatAuditRepository).save(auditCaptor.capture());
        BoatAudit audit = auditCaptor.getValue();
        assertThat(audit.action()).isEqualTo(AuditAction.CREATED);
        assertThat(audit.boatId()).isEqualTo(persisted.id());
        assertThat(audit.performedByUserId()).isEqualTo(PERFORMED_BY.value());
    }

    /** Validator failure → returns {@code failure}, NEVER touches the repository. */
    @Test
    void createBoat_invalid_returnsFailureWithoutPersisting() {
        ValidationMessage err = new ValidationMessage(
                Severity.ERROR, MessageType.CANNOT_BE_BLANK, "Boat.name");
        when(syntacticValidator.validate(null, null)).thenReturn(List.of(err));
        when(semanticValidator.validate(null, null)).thenReturn(List.of());

        ServiceResponse<Boat> result = service.createBoat(
                new CreateBoatCommand(null, null, PERFORMED_BY));

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.messages()).containsExactly(err);
        verifyNoInteractions(boatRepository, boatAuditRepository);
    }

    /** Both validators flag → both messages carried through (concatenated, not deduped). */
    @Test
    void createBoat_collectsMessagesFromBothValidators() {
        ValidationMessage syntactic = new ValidationMessage(
                Severity.ERROR, MessageType.SIZE_EXCEEDED, "Boat.name");
        ValidationMessage semantic = new ValidationMessage(
                Severity.ERROR, MessageType.INVALID_FORMAT, "Boat.name");
        when(syntacticValidator.validate("X", "y")).thenReturn(List.of(syntactic));
        when(semanticValidator.validate("X", "y")).thenReturn(List.of(semantic));

        ServiceResponse<Boat> result = service.createBoat(
                new CreateBoatCommand("X", "y", PERFORMED_BY));

        assertThat(result.messages()).containsExactly(syntactic, semantic);
        verifyNoInteractions(boatRepository, boatAuditRepository);
    }

    /**
     * WARNING/INFO findings are non-blocking advisories: the boat MUST still
     * be persisted and audited. Pins the fix for the "non-empty messages
     * blocked the operation" bug.
     */
    @Test
    void createBoat_warningOnly_persistsAndAudits() {
        ValidationMessage warn = new ValidationMessage(
                Severity.WARNING, MessageType.INVALID_FORMAT, "Boat.description");
        ValidationMessage info = new ValidationMessage(
                Severity.INFO, MessageType.INVALID_FORMAT, "Boat.name");
        when(syntacticValidator.validate("Argo", "trireme")).thenReturn(List.of(warn));
        when(semanticValidator.validate("Argo", "trireme")).thenReturn(List.of(info));
        Boat persisted = boat(UUID.randomUUID(), "Argo", 0L);
        when(boatRepository.save(any(Boat.class))).thenReturn(persisted);

        ServiceResponse<Boat> result = service.createBoat(
                new CreateBoatCommand("Argo", "trireme", PERFORMED_BY));

        assertThat(result.hasErrors()).isFalse();
        assertThat(result.data()).isSameAs(persisted);
        assertThat(result.messages()).containsExactlyInAnyOrder(warn, info);
        verify(boatRepository).save(any(Boat.class));
        verify(boatAuditRepository).save(any(BoatAudit.class));
    }

    /** Mixed INFO + ERROR → still a failure; ERROR is what blocks the operation. */
    @Test
    void createBoat_infoPlusError_returnsFailureWithoutPersisting() {
        ValidationMessage info = new ValidationMessage(
                Severity.INFO, MessageType.INVALID_FORMAT, "Boat.description");
        ValidationMessage error = new ValidationMessage(
                Severity.ERROR, MessageType.CANNOT_BE_BLANK, "Boat.name");
        when(syntacticValidator.validate(null, "d")).thenReturn(List.of(error));
        when(semanticValidator.validate(null, "d")).thenReturn(List.of(info));

        ServiceResponse<Boat> result = service.createBoat(
                new CreateBoatCommand(null, "d", PERFORMED_BY));

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.messages()).containsExactlyInAnyOrder(error, info);
        verifyNoInteractions(boatRepository, boatAuditRepository);
    }

    // -- updateBoat ------------------------------------------------------

    /**
     * Validation runs BEFORE the load: a 422 must be reachable even when the
     * id would 404. (Documented as a deliberate ordering choice in the
     * service Javadoc.)
     */
    @Test
    void updateBoat_invalidInput_short_circuitsBeforeLoad() {
        ValidationMessage err = new ValidationMessage(
                Severity.ERROR, MessageType.CANNOT_BE_BLANK, "Boat.name");
        when(syntacticValidator.validate(null, null)).thenReturn(List.of(err));
        when(semanticValidator.validate(null, null)).thenReturn(List.of());

        ServiceResponse<Boat> result = service.updateBoat(
                new UpdateBoatCommand(BOAT_ID, null, null, 0L, PERFORMED_BY));

        assertThat(result.hasErrors()).isTrue();
        verifyNoInteractions(boatRepository, boatAuditRepository);
    }

    /** Boat missing → {@link BoatNotFoundException} after validation passes. */
    @Test
    void updateBoat_notFound_throwsBoatNotFound() {
        when(syntacticValidator.validate("n", "d")).thenReturn(List.of());
        when(semanticValidator.validate("n", "d")).thenReturn(List.of());
        when(boatRepository.findById(BOAT_ID.value())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateBoat(
                new UpdateBoatCommand(BOAT_ID, "n", "d", 0L, PERFORMED_BY)))
                .isInstanceOf(BoatNotFoundException.class);
        verify(boatRepository, never()).save(any());
        verifyNoInteractions(boatAuditRepository);
    }

    /** expectedVersion mismatch → {@link ConcurrentModificationException}. */
    @Test
    void updateBoat_versionMismatch_throwsConcurrentModification() {
        when(syntacticValidator.validate("n", "d")).thenReturn(List.of());
        when(semanticValidator.validate("n", "d")).thenReturn(List.of());
        Boat existing = boat(BOAT_ID.value(), "old", 5L);
        when(boatRepository.findById(BOAT_ID.value())).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.updateBoat(
                new UpdateBoatCommand(BOAT_ID, "n", "d", 1L, PERFORMED_BY)))
                .isInstanceOf(ConcurrentModificationException.class)
                .satisfies(e -> {
                    ConcurrentModificationException cme = (ConcurrentModificationException) e;
                    assertThat(cme.getExpectedVersion()).isEqualTo(1L);
                    assertThat(cme.getActualVersion()).isEqualTo(5L);
                    assertThat(cme.getBoatId()).isEqualTo(BOAT_ID);
                });
        verify(boatRepository, never()).save(any());
        verifyNoInteractions(boatAuditRepository);
    }

    /** Happy path: save THEN audit, with id and createdAt carried over. */
    @Test
    void updateBoat_valid_savesAndAuditsInOrder() {
        when(syntacticValidator.validate("Argo II", "d")).thenReturn(List.of());
        when(semanticValidator.validate("Argo II", "d")).thenReturn(List.of());
        Boat existing = boat(BOAT_ID.value(), "Argo", 3L);
        when(boatRepository.findById(BOAT_ID.value())).thenReturn(Optional.of(existing));
        Boat persisted = boat(BOAT_ID.value(), "Argo II", 4L);
        when(boatRepository.save(any(Boat.class))).thenReturn(persisted);

        ServiceResponse<Boat> result = service.updateBoat(
                new UpdateBoatCommand(BOAT_ID, "Argo II", "d", 3L, PERFORMED_BY));

        assertThat(result.data()).isSameAs(persisted);
        ArgumentCaptor<Boat> savedCaptor = ArgumentCaptor.forClass(Boat.class);
        verify(boatRepository).save(savedCaptor.capture());
        Boat sentToSave = savedCaptor.getValue();
        assertThat(sentToSave.id()).isEqualTo(BOAT_ID.value());
        assertThat(sentToSave.createdAt()).isEqualTo(existing.createdAt());
        assertThat(sentToSave.version()).isEqualTo(3L);

        ArgumentCaptor<BoatAudit> auditCaptor = ArgumentCaptor.forClass(BoatAudit.class);
        verify(boatAuditRepository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo(AuditAction.UPDATED);
    }

    /** WARNING/INFO findings on update do not block the persist + audit path. */
    @Test
    void updateBoat_warningOnly_persistsAndAudits() {
        ValidationMessage warn = new ValidationMessage(
                Severity.WARNING, MessageType.INVALID_FORMAT, "Boat.description");
        when(syntacticValidator.validate("Argo II", "d")).thenReturn(List.of(warn));
        when(semanticValidator.validate("Argo II", "d")).thenReturn(List.of());
        Boat existing = boat(BOAT_ID.value(), "Argo", 3L);
        when(boatRepository.findById(BOAT_ID.value())).thenReturn(Optional.of(existing));
        Boat persisted = boat(BOAT_ID.value(), "Argo II", 4L);
        when(boatRepository.save(any(Boat.class))).thenReturn(persisted);

        ServiceResponse<Boat> result = service.updateBoat(
                new UpdateBoatCommand(BOAT_ID, "Argo II", "d", 3L, PERFORMED_BY));

        assertThat(result.hasErrors()).isFalse();
        assertThat(result.data()).isSameAs(persisted);
        assertThat(result.messages()).containsExactly(warn);
        verify(boatRepository).save(any(Boat.class));
        verify(boatAuditRepository).save(any(BoatAudit.class));
    }

    /** Mixed INFO + ERROR on update → failure short-circuits before the load. */
    @Test
    void updateBoat_infoPlusError_returnsFailureWithoutLoading() {
        ValidationMessage info = new ValidationMessage(
                Severity.INFO, MessageType.INVALID_FORMAT, "Boat.description");
        ValidationMessage error = new ValidationMessage(
                Severity.ERROR, MessageType.CANNOT_BE_BLANK, "Boat.name");
        when(syntacticValidator.validate(null, "d")).thenReturn(List.of(error));
        when(semanticValidator.validate(null, "d")).thenReturn(List.of(info));

        ServiceResponse<Boat> result = service.updateBoat(
                new UpdateBoatCommand(BOAT_ID, null, "d", 0L, PERFORMED_BY));

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.messages()).containsExactlyInAnyOrder(error, info);
        verifyNoInteractions(boatRepository, boatAuditRepository);
    }

    // -- deleteBoat ------------------------------------------------------

    /** Boat missing → {@link BoatNotFoundException}, no audit, no delete. */
    @Test
    void deleteBoat_notFound_throwsBoatNotFound() {
        when(boatRepository.findById(BOAT_ID.value())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteBoat(new DeleteBoatCommand(BOAT_ID, PERFORMED_BY)))
                .isInstanceOf(BoatNotFoundException.class);
        verify(boatRepository, never()).deleteById(any());
        verifyNoInteractions(boatAuditRepository);
    }

    /**
     * Happy path: audit row written BEFORE the delete (so we still have the
     * AppUser FK target row visible inside the same transaction).
     */
    @Test
    void deleteBoat_valid_auditsThenDeletes() {
        Boat existing = boat(BOAT_ID.value(), "RIP", 2L);
        when(boatRepository.findById(BOAT_ID.value())).thenReturn(Optional.of(existing));

        service.deleteBoat(new DeleteBoatCommand(BOAT_ID, PERFORMED_BY));

        InOrder ordered = inOrder(boatAuditRepository, boatRepository);
        ArgumentCaptor<BoatAudit> auditCaptor = ArgumentCaptor.forClass(BoatAudit.class);
        ordered.verify(boatAuditRepository).save(auditCaptor.capture());
        ordered.verify(boatRepository).deleteById(BOAT_ID.value());
        BoatAudit audit = auditCaptor.getValue();
        assertThat(audit.action()).isEqualTo(AuditAction.DELETED);
        assertThat(audit.name()).isEqualTo("RIP");
        assertThat(audit.version()).isEqualTo(2L);
    }

    // -- helpers ---------------------------------------------------------

    private Boat boat(UUID id, String name, Long version) {
        return new Boat(id, name, "any", java.time.OffsetDateTime.now(fixedClock), version);
    }
}
