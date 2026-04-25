package ch.owt.boatapp.bff.adapter.in.web;

import org.springframework.web.bind.annotation.RestController;

/**
 * BFF-only authentication endpoints — login status, current user
 * ({@code /api/me} from the OAuth2 session), CSRF token bootstrap.
 *
 * <p>Not proxied to the Business Service: the user profile is read from the
 * BFF's {@code OidcUser} session attribute. Endpoint bodies are added in
 * step 02a4.
 */
@RestController
public class AuthController {
}
