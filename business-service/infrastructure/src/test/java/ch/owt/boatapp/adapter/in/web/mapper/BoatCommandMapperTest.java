package ch.owt.boatapp.adapter.in.web.mapper;

import ch.owt.boatapp.adapter.in.web.dto.generated.BoatCreateRequest;
import ch.owt.boatapp.adapter.in.web.dto.generated.BoatUpdateRequest;
import ch.owt.boatapp.application.port.in.CreateBoatCommand;
import ch.owt.boatapp.application.port.in.DeleteBoatCommand;
import ch.owt.boatapp.application.port.in.GetBoatQuery;
import ch.owt.boatapp.application.port.in.ListBoatsQuery;
import ch.owt.boatapp.application.port.in.UpdateBoatCommand;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-Java unit tests for {@link BoatCommandMapper}: pin DTO → command/query
 * conversion and the {@code sort} parsing rules (which include a
 * defensive default and a Locale.ROOT lower-casing tolerance for the
 * direction string).
 */
class BoatCommandMapperTest {

    private static final UUID BOAT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    /** {@code toCreateCommand}: copies name/description and wraps userId in {@code UserId}. */
    @Test
    void toCreateCommand_copiesFieldsAndWrapsUserId() {
        BoatCreateRequest dto = new BoatCreateRequest("Argo");
        dto.setDescription("trireme");

        CreateBoatCommand cmd = BoatCommandMapper.toCreateCommand(dto, USER_ID);

        assertThat(cmd.name()).isEqualTo("Argo");
        assertThat(cmd.description()).isEqualTo("trireme");
        assertThat(cmd.performedBy().value()).isEqualTo(USER_ID);
    }

    /** {@code toUpdateCommand}: assembles all five fields including {@code expectedVersion}. */
    @Test
    void toUpdateCommand_carriesPathIdAndIfMatchAndDtoFields() {
        BoatUpdateRequest dto = new BoatUpdateRequest("Argo II");
        dto.setDescription("rebuilt");

        UpdateBoatCommand cmd = BoatCommandMapper.toUpdateCommand(BOAT_ID, 7L, dto, USER_ID);

        assertThat(cmd.id().value()).isEqualTo(BOAT_ID);
        assertThat(cmd.name()).isEqualTo("Argo II");
        assertThat(cmd.description()).isEqualTo("rebuilt");
        assertThat(cmd.expectedVersion()).isEqualTo(7L);
        assertThat(cmd.performedBy().value()).isEqualTo(USER_ID);
    }

    /** {@code toGetQuery}: wraps the path id in {@code BoatId}. */
    @Test
    void toGetQuery_wrapsPathIdInBoatId() {
        GetBoatQuery query = BoatCommandMapper.toGetQuery(BOAT_ID);

        assertThat(query.id().value()).isEqualTo(BOAT_ID);
    }

    /** {@code toDeleteCommand}: wraps both ids in their value objects. */
    @Test
    void toDeleteCommand_wrapsIdsInValueObjects() {
        DeleteBoatCommand cmd = BoatCommandMapper.toDeleteCommand(BOAT_ID, USER_ID);

        assertThat(cmd.id().value()).isEqualTo(BOAT_ID);
        assertThat(cmd.performedBy().value()).isEqualTo(USER_ID);
    }

    // -- toListQuery: sort parsing -------------------------------------

    /** Defaults: {@code sortBy=createdAt}, {@code sortDir=desc} when sort is null. */
    @Test
    void toListQuery_nullSort_appliesDefaults() {
        ListBoatsQuery q = BoatCommandMapper.toListQuery(0, 10, null, null);

        assertThat(q.page()).isZero();
        assertThat(q.size()).isEqualTo(10);
        assertThat(q.sortBy()).isEqualTo("createdAt");
        assertThat(q.sortDir()).isEqualTo("desc");
        assertThat(q.search()).isNull();
    }

    /** Blank sort → defaults (defensive). */
    @Test
    void toListQuery_blankSort_appliesDefaults() {
        ListBoatsQuery q = BoatCommandMapper.toListQuery(0, 10, "   ", null);

        assertThat(q.sortBy()).isEqualTo("createdAt");
        assertThat(q.sortDir()).isEqualTo("desc");
    }

    /** Field only (no comma) → that field, default desc. */
    @Test
    void toListQuery_fieldOnly_keepsDefaultDir() {
        ListBoatsQuery q = BoatCommandMapper.toListQuery(0, 10, "name", null);

        assertThat(q.sortBy()).isEqualTo("name");
        assertThat(q.sortDir()).isEqualTo("desc");
    }

    /** {@code field,asc} → field, asc. */
    @Test
    void toListQuery_fieldComma_asc_parsesBoth() {
        ListBoatsQuery q = BoatCommandMapper.toListQuery(0, 10, "name,asc", null);

        assertThat(q.sortBy()).isEqualTo("name");
        assertThat(q.sortDir()).isEqualTo("asc");
    }

    /** Direction is case-insensitive (Locale.ROOT lower-case). */
    @Test
    void toListQuery_uppercaseDirection_isAccepted() {
        ListBoatsQuery q = BoatCommandMapper.toListQuery(0, 10, "name,ASC", null);

        assertThat(q.sortDir()).isEqualTo("asc");
    }

    /** Whitespace around the separator is tolerated. */
    @Test
    void toListQuery_whitespaceAroundSeparator_isTolerated() {
        ListBoatsQuery q = BoatCommandMapper.toListQuery(0, 10, "  name  ,  asc  ", null);

        assertThat(q.sortBy()).isEqualTo("name");
        assertThat(q.sortDir()).isEqualTo("asc");
    }

    /** Unknown direction → falls back to default {@code desc}. */
    @Test
    void toListQuery_unknownDirection_fallsBackToDesc() {
        ListBoatsQuery q = BoatCommandMapper.toListQuery(0, 10, "name,sideways", null);

        assertThat(q.sortBy()).isEqualTo("name");
        assertThat(q.sortDir()).isEqualTo("desc");
    }

    /** Empty field segment → falls back to default {@code createdAt}. */
    @Test
    void toListQuery_emptyField_fallsBackToCreatedAt() {
        ListBoatsQuery q = BoatCommandMapper.toListQuery(0, 10, ",asc", null);

        assertThat(q.sortBy()).isEqualTo("createdAt");
        assertThat(q.sortDir()).isEqualTo("asc");
    }

    /** {@code search} is passed through verbatim. */
    @Test
    void toListQuery_passesSearchThrough() {
        ListBoatsQuery q = BoatCommandMapper.toListQuery(2, 50, "name,asc", "alpha");

        assertThat(q.search()).isEqualTo("alpha");
    }
}
