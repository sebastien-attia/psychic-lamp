package ch.owt.boatapp.adapter.out.persistence.mapper;

import ch.owt.boatapp.adapter.out.persistence.entity.AppUserJpaEntity;
import ch.owt.boatapp.domain.model.AppUser;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-Java unit tests for {@link AppUserPersistenceMapper}: pin field-by-field
 * equivalence between domain and JPA representation.
 */
class AppUserPersistenceMapperTest {

    private final AppUserPersistenceMapper mapper = new AppUserPersistenceMapper();

    private static final UUID ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final OffsetDateTime LOGIN_AT =
            OffsetDateTime.of(2026, 4, 1, 12, 0, 0, 0, ZoneOffset.UTC);

    /** Entity → Domain: copies every component verbatim. */
    @Test
    void toDomain_copiesEveryField() {
        AppUserJpaEntity entity = new AppUserJpaEntity(
                ID, "kc-sub", "alice", "alice@x.test",
                "Alice", "A", LOGIN_AT, LOGIN_AT);

        AppUser domain = mapper.toDomain(entity);

        assertThat(domain.id()).isEqualTo(ID);
        assertThat(domain.keycloakId()).isEqualTo("kc-sub");
        assertThat(domain.username()).isEqualTo("alice");
        assertThat(domain.email()).isEqualTo("alice@x.test");
        assertThat(domain.firstName()).isEqualTo("Alice");
        assertThat(domain.lastName()).isEqualTo("A");
        assertThat(domain.firstLogin()).isEqualTo(LOGIN_AT);
        assertThat(domain.lastLogin()).isEqualTo(LOGIN_AT);
    }

    /** Domain → Entity: copies every component verbatim. */
    @Test
    void toJpaEntity_copiesEveryField() {
        AppUser domain = new AppUser(
                ID, "kc-sub", "alice", "alice@x.test",
                "Alice", "A", LOGIN_AT, LOGIN_AT);

        AppUserJpaEntity entity = mapper.toJpaEntity(domain);

        assertThat(entity.getId()).isEqualTo(ID);
        assertThat(entity.getKeycloakId()).isEqualTo("kc-sub");
        assertThat(entity.getUsername()).isEqualTo("alice");
        assertThat(entity.getEmail()).isEqualTo("alice@x.test");
        assertThat(entity.getFirstName()).isEqualTo("Alice");
        assertThat(entity.getLastName()).isEqualTo("A");
        assertThat(entity.getFirstLogin()).isEqualTo(LOGIN_AT);
        assertThat(entity.getLastLogin()).isEqualTo(LOGIN_AT);
    }

    /** Round-trip preserves equality, including nullable {@code firstName}/{@code lastName}. */
    @Test
    void roundTrip_handlesNullableNameFields() {
        AppUser original = new AppUser(
                ID, "kc-sub", "bob", "bob@x.test",
                null, null, LOGIN_AT, LOGIN_AT);

        AppUser round = mapper.toDomain(mapper.toJpaEntity(original));

        assertThat(round).isEqualTo(original);
    }

    /** {@code toDomain(null)} → {@code null}. */
    @Test
    void toDomain_nullEntity_returnsNull() {
        assertThat(mapper.toDomain(null)).isNull();
    }

    /** {@code toJpaEntity(null)} → {@code null}. */
    @Test
    void toJpaEntity_nullUser_returnsNull() {
        assertThat(mapper.toJpaEntity(null)).isNull();
    }
}
