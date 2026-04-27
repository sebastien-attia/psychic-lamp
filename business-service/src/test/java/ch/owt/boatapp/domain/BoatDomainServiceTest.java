package ch.owt.boatapp.domain;

import ch.owt.boatapp.domain.model.AuditAction;
import ch.owt.boatapp.domain.model.Boat;
import ch.owt.boatapp.domain.model.BoatAudit;
import ch.owt.boatapp.domain.model.BoatId;
import ch.owt.boatapp.domain.model.ServiceResponse;
import ch.owt.boatapp.domain.model.UserId;
import ch.owt.boatapp.domain.model.validation.MessageType;
import ch.owt.boatapp.domain.model.validation.Severity;
import ch.owt.boatapp.domain.model.validation.ValidationMessage;
import ch.owt.boatapp.domain.port.in.CreateBoatCommand;
import ch.owt.boatapp.domain.port.in.UpdateBoatCommand;
import ch.owt.boatapp.domain.port.out.BoatAuditRepositoryPort;
import ch.owt.boatapp.domain.port.out.BoatRepositoryPort;
import ch.owt.boatapp.domain.service.BoatDomainService;
import ch.owt.boatapp.domain.service.validation.SemanticValidator;
import ch.owt.boatapp.domain.service.validation.SyntacticValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-JUnit unit tests for {@link BoatDomainService}, focused on the
 * severity-aware persistence gate.
 *
 * <p>Pins the contract that create/update fail (no persistence, no audit
 * append) only when validation produced at least one
 * {@link Severity#ERROR ERROR}-severity finding, and that
 * {@link Severity#WARNING WARNING} / {@link Severity#INFO INFO} findings
 * ride along on the success envelope without blocking the write. The
 * matching HTTP-layer assertion lives in
 * {@code BoatControllerIntegrationTest} and the matching error path in
 * {@code ValidationAndErrorsIntegrationTest} — the three tests together
 * lock the gate behaviour from domain to wire.
 */
class BoatDomainServiceTest {

    private static final UserId PERFORMER = new UserId(UUID.randomUUID());
    private static final OffsetDateTime FIXED_NOW =
            OffsetDateTime.of(2026, 4, 27, 12, 0, 0, 0, ZoneOffset.UTC);

    private BoatRepositoryPort boatRepository;
    private BoatAuditRepositoryPort boatAuditRepository;
    private BoatDomainService service;

    @BeforeEach
    void setUp() {
        boatRepository = mock(BoatRepositoryPort.class);
        boatAuditRepository = mock(BoatAuditRepositoryPort.class);
        Clock fixedClock = Clock.fixed(Instant.from(FIXED_NOW), ZoneOffset.UTC);
        service = new BoatDomainService(
                boatRepository,
                boatAuditRepository,
                new SyntacticValidator(),
                new SemanticValidator(),
                fixedClock);
        // save() is expected to echo the input (with a synthetic version) on
        // the success paths; pre-stubbing avoids each test repeating the
        // boilerplate. The persistence adapter is responsible for assigning
        // the real version in production.
        when(boatRepository.save(any())).thenAnswer(inv -> {
            Boat in = inv.getArgument(0);
            return new Boat(in.id(), in.name(), in.description(), in.createdAt(), 0L);
        });
    }

    /** Create with a clean payload → success with no advisory messages, repository + audit invoked. */
    @Test
    void createBoat_cleanInput_returnsSuccessWithNoMessages_andPersists() {
        CreateBoatCommand cmd = new CreateBoatCommand("Black Pearl", "A pirate ship", PERFORMER);

        ServiceResponse<Boat> response = service.createBoat(cmd);

        assertThat(response.hasErrors()).isFalse();
        assertThat(response.data()).isNotNull();
        assertThat(response.data().name()).isEqualTo("Black Pearl");
        assertThat(response.messages()).isEmpty();
        verify(boatRepository, times(1)).save(any(Boat.class));
        verify(boatAuditRepository, times(1)).save(any(BoatAudit.class));
    }

    /** Create with a 2-char name → success with one WARNING; persistence still happens. */
    @Test
    void createBoat_warningOnly_persistsAndReturnsWarning() {
        CreateBoatCommand cmd = new CreateBoatCommand("AB", "A trireme", PERFORMER);

        ServiceResponse<Boat> response = service.createBoat(cmd);

        assertThat(response.hasErrors()).isFalse();
        assertThat(response.data()).isNotNull();
        assertThat(response.messages()).hasSize(1);
        ValidationMessage warning = response.messages().get(0);
        assertThat(warning.severity()).isEqualTo(Severity.WARNING);
        assertThat(warning.type()).isEqualTo(MessageType.NAME_TOO_SHORT);
        assertThat(warning.field()).isEqualTo("Boat.name");
        verify(boatRepository, times(1)).save(any(Boat.class));
        // Audit must reflect the CREATED action; this is the contract the
        // BoatAudit table relies on for INSERT-ONLY history.
        verify(boatAuditRepository, times(1))
                .save(argThat(audit -> audit.action() == AuditAction.CREATED));
    }

    /** Create with a missing description → success with one INFO; persistence still happens. */
    @Test
    void createBoat_infoOnly_persistsAndReturnsInfo() {
        CreateBoatCommand cmd = new CreateBoatCommand("Black Pearl", null, PERFORMER);

        ServiceResponse<Boat> response = service.createBoat(cmd);

        assertThat(response.hasErrors()).isFalse();
        assertThat(response.data()).isNotNull();
        assertThat(response.messages())
                .extracting(ValidationMessage::severity, ValidationMessage::type, ValidationMessage::field)
                .containsExactly(tuple(Severity.INFO, MessageType.DESCRIPTION_MISSING, "Boat.description"));
        verify(boatRepository, times(1)).save(any(Boat.class));
        verify(boatAuditRepository, times(1)).save(any(BoatAudit.class));
    }

    /** Create with an ERROR-bearing name → failure, repository and audit untouched. */
    @Test
    void createBoat_errorOnly_returnsFailure_andSkipsPersistence() {
        CreateBoatCommand cmd = new CreateBoatCommand("FORBIDDEN-X", "A pirate ship", PERFORMER);

        ServiceResponse<Boat> response = service.createBoat(cmd);

        assertThat(response.hasErrors()).isTrue();
        assertThat(response.data()).isNull();
        assertThat(response.messages())
                .extracting(ValidationMessage::severity, ValidationMessage::type)
                .containsExactly(tuple(Severity.ERROR, MessageType.INVALID_FORMAT));
        verify(boatRepository, never()).save(any());
        verify(boatAuditRepository, never()).save(any());
    }

    /** Mixed ERROR+INFO → failure (error wins), all messages preserved, no persistence. */
    @Test
    void createBoat_errorAndInfoMixed_returnsFailure_withBothMessages() {
        // FORBIDDEN-X → ERROR, null description → INFO. The presence of the
        // INFO must not weaken the ERROR-blocks-persistence contract.
        CreateBoatCommand cmd = new CreateBoatCommand("FORBIDDEN-X", null, PERFORMER);

        ServiceResponse<Boat> response = service.createBoat(cmd);

        assertThat(response.hasErrors()).isTrue();
        assertThat(response.data()).isNull();
        assertThat(response.messages())
                .extracting(ValidationMessage::severity)
                .containsExactlyInAnyOrder(Severity.ERROR, Severity.INFO);
        verify(boatRepository, never()).save(any());
        verify(boatAuditRepository, never()).save(any());
    }

    /** Update with INFO-only on a fresh row → success with INFO; saveAndFlush + audit fire. */
    @Test
    void updateBoat_infoOnly_persistsAndReturnsInfo() {
        UUID id = UUID.randomUUID();
        Boat existing = new Boat(id, "Original", "old desc", FIXED_NOW.minusDays(1), 3L);
        when(boatRepository.findById(id)).thenReturn(Optional.of(existing));

        UpdateBoatCommand cmd = new UpdateBoatCommand(
                new BoatId(id), "Renamed", null, 3L, PERFORMER);

        ServiceResponse<Boat> response = service.updateBoat(cmd);

        assertThat(response.hasErrors()).isFalse();
        assertThat(response.data().name()).isEqualTo("Renamed");
        assertThat(response.messages())
                .extracting(ValidationMessage::severity, ValidationMessage::type)
                .containsExactly(tuple(Severity.INFO, MessageType.DESCRIPTION_MISSING));
        verify(boatRepository, times(1)).save(any(Boat.class));
        verify(boatAuditRepository, times(1))
                .save(argThat(audit -> audit.action() == AuditAction.UPDATED));
    }

    /** Update with ERROR → failure, no findById call (validate-before-load short-circuit). */
    @Test
    void updateBoat_errorOnly_returnsFailure_andSkipsLoad() {
        UpdateBoatCommand cmd = new UpdateBoatCommand(
                new BoatId(UUID.randomUUID()), "FORBIDDEN", "x", 0L, PERFORMER);

        ServiceResponse<Boat> response = service.updateBoat(cmd);

        assertThat(response.hasErrors()).isTrue();
        assertThat(response.data()).isNull();
        // The "validate before load" comment in BoatDomainService.updateBoat
        // is structural: it prevents a 404 from masking a 422. This pin
        // ensures the short-circuit is preserved.
        verify(boatRepository, never()).findById(any());
        verify(boatRepository, never()).save(any());
        verify(boatAuditRepository, never()).save(any());
    }

}
