package ch.owt.boatapp.domain.model;

/**
 * Pure-Java domain model for an authenticated application user, synced from
 * the JWT claims (sub, preferred_username, email, given_name, family_name)
 * on every request.
 *
 * <p>Lives in {@code domain.model} — no Spring, no Jakarta. Fields are added
 * in step 02a3.
 */
public class AppUser {
}
