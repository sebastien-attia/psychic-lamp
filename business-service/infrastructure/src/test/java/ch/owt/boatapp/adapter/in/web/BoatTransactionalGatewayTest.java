package ch.owt.boatapp.adapter.in.web;

import ch.owt.boatapp.application.port.in.CreateBoatCommand;
import ch.owt.boatapp.application.port.in.DeleteBoatCommand;
import ch.owt.boatapp.application.port.in.GetBoatQuery;
import ch.owt.boatapp.application.port.in.ListBoatsQuery;
import ch.owt.boatapp.application.port.in.ManageBoatsUseCase;
import ch.owt.boatapp.application.port.in.UpdateBoatCommand;
import ch.owt.boatapp.domain.exception.ValidationFailureException;
import ch.owt.boatapp.domain.model.Boat;
import ch.owt.boatapp.domain.model.BoatId;
import ch.owt.boatapp.domain.model.PageResult;
import ch.owt.boatapp.domain.model.ServiceResponse;
import ch.owt.boatapp.domain.model.UserId;
import ch.owt.boatapp.domain.model.validation.MessageType;
import ch.owt.boatapp.domain.model.validation.Severity;
import ch.owt.boatapp.domain.model.validation.ValidationMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the {@code @Transactional} gateway {@link BoatTransactionalGateway}.
 *
 * <p>Exercises the only logic this class owns: translating a domain
 * {@link ServiceResponse} into either a returned domain object (success) or
 * a {@link ValidationFailureException} (failure). Read paths and delete are
 * pure delegation. Transaction semantics themselves are exercised by the
 * integration suite (this is a unit test — no Spring context).
 */
@ExtendWith(MockitoExtension.class)
class BoatTransactionalGatewayTest {

    @Mock private ManageBoatsUseCase manageBoatsUseCase;

    private BoatTransactionalGateway gateway;

    private static final UserId PERFORMED_BY = new UserId(UUID.randomUUID());
    private static final BoatId BOAT_ID = new BoatId(UUID.randomUUID());

    @BeforeEach
    void setUp() {
        gateway = new BoatTransactionalGateway(manageBoatsUseCase);
    }

    // -- read paths: pure delegation ------------------------------------

    @Test
    void listBoats_delegatesToUseCase() {
        ListBoatsQuery query = new ListBoatsQuery(0, 10, "name", "asc", null);
        PageResult<Boat> page = new PageResult<>(List.of(), 0L, 0, 10, 0);
        when(manageBoatsUseCase.listBoats(query)).thenReturn(page);

        assertThat(gateway.listBoats(query)).isSameAs(page);
    }

    @Test
    void getBoat_delegatesToUseCase() {
        GetBoatQuery query = new GetBoatQuery(BOAT_ID);
        Boat boat = sampleBoat();
        when(manageBoatsUseCase.getBoat(query)).thenReturn(boat);

        assertThat(gateway.getBoat(query)).isSameAs(boat);
    }

    // -- createBoat -----------------------------------------------------

    /** Use-case returns success → gateway returns the envelope (data + empty advisories). */
    @Test
    void createBoat_success_returnsEnvelope() {
        CreateBoatCommand cmd = new CreateBoatCommand("Argo", "trireme", PERFORMED_BY);
        Boat created = sampleBoat();
        when(manageBoatsUseCase.createBoat(cmd)).thenReturn(ServiceResponse.success(created));

        ServiceResponse<Boat> result = gateway.createBoat(cmd);

        assertThat(result.data()).isSameAs(created);
        assertThat(result.messages()).isEmpty();
    }

    /** Success with WARNING/INFO advisories → gateway propagates them on the success envelope. */
    @Test
    void createBoat_successWithAdvisories_propagatesMessages() {
        CreateBoatCommand cmd = new CreateBoatCommand("Argo", "trireme", PERFORMED_BY);
        Boat created = sampleBoat();
        ValidationMessage warn = new ValidationMessage(
                Severity.WARNING, MessageType.INVALID_FORMAT, "Boat.description");
        when(manageBoatsUseCase.createBoat(cmd)).thenReturn(ServiceResponse.success(created, List.of(warn)));

        ServiceResponse<Boat> result = gateway.createBoat(cmd);

        assertThat(result.data()).isSameAs(created);
        assertThat(result.messages()).containsExactly(warn);
    }

    /** Use-case returns failure → gateway throws {@link ValidationFailureException}. */
    @Test
    void createBoat_failure_throwsValidationFailureException() {
        CreateBoatCommand cmd = new CreateBoatCommand(null, null, PERFORMED_BY);
        ValidationMessage err = new ValidationMessage(
                Severity.ERROR, MessageType.CANNOT_BE_BLANK, "Boat.name");
        when(manageBoatsUseCase.createBoat(cmd)).thenReturn(ServiceResponse.failure(List.of(err)));

        assertThatThrownBy(() -> gateway.createBoat(cmd))
                .isInstanceOf(ValidationFailureException.class)
                .satisfies(e -> {
                    ValidationFailureException vfe = (ValidationFailureException) e;
                    assertThat(vfe.getMessages()).containsExactly(err);
                });
    }

    // -- updateBoat -----------------------------------------------------

    @Test
    void updateBoat_success_returnsEnvelope() {
        UpdateBoatCommand cmd = new UpdateBoatCommand(BOAT_ID, "n", "d", 0L, PERFORMED_BY);
        Boat updated = sampleBoat();
        when(manageBoatsUseCase.updateBoat(cmd)).thenReturn(ServiceResponse.success(updated));

        ServiceResponse<Boat> result = gateway.updateBoat(cmd);

        assertThat(result.data()).isSameAs(updated);
        assertThat(result.messages()).isEmpty();
    }

    @Test
    void updateBoat_successWithAdvisories_propagatesMessages() {
        UpdateBoatCommand cmd = new UpdateBoatCommand(BOAT_ID, "n", "d", 0L, PERFORMED_BY);
        Boat updated = sampleBoat();
        ValidationMessage info = new ValidationMessage(
                Severity.INFO, MessageType.INVALID_FORMAT, "Boat.description");
        when(manageBoatsUseCase.updateBoat(cmd)).thenReturn(ServiceResponse.success(updated, List.of(info)));

        ServiceResponse<Boat> result = gateway.updateBoat(cmd);

        assertThat(result.data()).isSameAs(updated);
        assertThat(result.messages()).containsExactly(info);
    }

    @Test
    void updateBoat_failure_throwsValidationFailureException() {
        UpdateBoatCommand cmd = new UpdateBoatCommand(BOAT_ID, null, null, 0L, PERFORMED_BY);
        ValidationMessage err = new ValidationMessage(
                Severity.ERROR, MessageType.CANNOT_BE_BLANK, "Boat.name");
        when(manageBoatsUseCase.updateBoat(cmd)).thenReturn(ServiceResponse.failure(List.of(err)));

        assertThatThrownBy(() -> gateway.updateBoat(cmd))
                .isInstanceOf(ValidationFailureException.class);
    }

    // -- deleteBoat -----------------------------------------------------

    @Test
    void deleteBoat_delegatesToUseCase() {
        DeleteBoatCommand cmd = new DeleteBoatCommand(BOAT_ID, PERFORMED_BY);

        gateway.deleteBoat(cmd);

        verify(manageBoatsUseCase).deleteBoat(cmd);
    }

    // -- helpers --------------------------------------------------------

    private Boat sampleBoat() {
        return new Boat(BOAT_ID.value(), "Argo", "trireme", OffsetDateTime.now(), 0L);
    }
}
