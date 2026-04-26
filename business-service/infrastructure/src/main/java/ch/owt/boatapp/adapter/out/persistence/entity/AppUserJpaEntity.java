package ch.owt.boatapp.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * JPA entity mirroring the {@code APP_USER} table.
 *
 * <p>Lives in {@code adapter.out.persistence.entity}. {@code keycloakId}
 * stores the JWT {@code sub} claim and is the upsert key (UNIQUE in the
 * database).
 */
@Entity
@Table(name = "app_user")
public class AppUserJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "keycloak_id", nullable = false, unique = true, length = 255)
    private String keycloakId;

    @Column(name = "username", nullable = false, length = 255)
    private String username;

    @Column(name = "email", nullable = false, length = 320)
    private String email;

    @Column(name = "first_name", length = 255)
    private String firstName;

    @Column(name = "last_name", length = 255)
    private String lastName;

    @Column(name = "first_login", nullable = false)
    private OffsetDateTime firstLogin;

    @Column(name = "last_login", nullable = false)
    private OffsetDateTime lastLogin;

    /** No-arg constructor required by JPA. */
    public AppUserJpaEntity() {
    }

    /**
     * All-args constructor for convenience in tests and adapters.
     *
     * @param id         row identifier (assigned by the domain on first sync)
     * @param keycloakId JWT {@code sub} claim — upsert key
     * @param username   {@code preferred_username} claim
     * @param email      {@code email} claim
     * @param firstName  {@code given_name} claim, may be {@code null}
     * @param lastName   {@code family_name} claim, may be {@code null}
     * @param firstLogin first-login timestamp in UTC
     * @param lastLogin  last-login timestamp in UTC
     */
    public AppUserJpaEntity(UUID id, String keycloakId, String username, String email,
                            String firstName, String lastName,
                            OffsetDateTime firstLogin, OffsetDateTime lastLogin) {
        this.id = id;
        this.keycloakId = keycloakId;
        this.username = username;
        this.email = email;
        this.firstName = firstName;
        this.lastName = lastName;
        this.firstLogin = firstLogin;
        this.lastLogin = lastLogin;
    }

    /** @return the row identifier */
    public UUID getId() {
        return id;
    }

    /** @param id the new row identifier */
    public void setId(UUID id) {
        this.id = id;
    }

    /** @return the JWT {@code sub} claim */
    public String getKeycloakId() {
        return keycloakId;
    }

    /** @param keycloakId the new {@code sub}-derived identifier */
    public void setKeycloakId(String keycloakId) {
        this.keycloakId = keycloakId;
    }

    /** @return the {@code preferred_username} claim */
    public String getUsername() {
        return username;
    }

    /** @param username the new username */
    public void setUsername(String username) {
        this.username = username;
    }

    /** @return the {@code email} claim */
    public String getEmail() {
        return email;
    }

    /** @param email the new email */
    public void setEmail(String email) {
        this.email = email;
    }

    /** @return the {@code given_name} claim, or {@code null} */
    public String getFirstName() {
        return firstName;
    }

    /** @param firstName the new first name */
    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    /** @return the {@code family_name} claim, or {@code null} */
    public String getLastName() {
        return lastName;
    }

    /** @param lastName the new last name */
    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    /** @return the first-login timestamp in UTC */
    public OffsetDateTime getFirstLogin() {
        return firstLogin;
    }

    /** @param firstLogin the new first-login timestamp */
    public void setFirstLogin(OffsetDateTime firstLogin) {
        this.firstLogin = firstLogin;
    }

    /** @return the last-login timestamp in UTC */
    public OffsetDateTime getLastLogin() {
        return lastLogin;
    }

    /** @param lastLogin the new last-login timestamp */
    public void setLastLogin(OffsetDateTime lastLogin) {
        this.lastLogin = lastLogin;
    }
}
