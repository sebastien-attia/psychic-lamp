package ch.owt.boatapp.bff.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;

import java.util.function.Supplier;

/**
 * BREACH-safe CSRF handler tuned for single-page apps.
 *
 * <p>Composes (does not extend) two upstream handlers explicitly so the
 * intent of each branch is obvious to the next reader:
 * <ul>
 *   <li>{@link #xor} — {@link XorCsrfTokenRequestAttributeHandler}, used
 *       when <em>rendering</em> the {@code XSRF-TOKEN} cookie. The XOR mask
 *       defeats BREACH-style oracles that compress response bodies.</li>
 *   <li>{@link #plain} — {@link CsrfTokenRequestAttributeHandler}, used when
 *       <em>resolving</em> a token sent via the {@code X-XSRF-TOKEN} request
 *       header (the {@code fetch} / {@code axios} pattern). The SPA echoes
 *       the unmasked cookie value, so no XOR decoding is needed.</li>
 * </ul>
 *
 * <p>Form-field POSTs (used by server-rendered HTML pages) carry the
 * masked cookie value and are decoded by {@link #xor}. This matches the
 * Spring Security reference recipe for SPA CSRF — see "Single-Page
 * Applications" in the reference manual.
 */
public final class SpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {

    private final CsrfTokenRequestHandler plain = new CsrfTokenRequestAttributeHandler();
    private final CsrfTokenRequestHandler xor = new XorCsrfTokenRequestAttributeHandler();

    /**
     * Make the {@link CsrfToken} available as a request attribute and write
     * the XOR-masked value to the {@code XSRF-TOKEN} cookie.
     *
     * <p>Spring Security 6+ defaults the CSRF token to a deferred {@link
     * Supplier} so the token is only materialized when something reads the
     * request attribute. SPAs that talk to the API via {@code fetch} never
     * touch a server-rendered template and therefore never trigger that
     * read — the cookie is never written and the JavaScript client cannot
     * echo it on the next state-changing call. We force materialization by
     * calling {@code csrfToken.get()} here so {@link
     * org.springframework.security.web.csrf.CookieCsrfTokenRepository}
     * emits {@code Set-Cookie: XSRF-TOKEN} on every response.
     *
     * @param request   the inbound request — passed through to the XOR handler
     * @param response  the outbound response — receives the {@code Set-Cookie}
     * @param csrfToken supplier that materializes the deferred token; we
     *                  invoke it eagerly to drive cookie emission
     */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       Supplier<CsrfToken> csrfToken) {
        this.xor.handle(request, response, csrfToken);
        csrfToken.get();
    }

    /**
     * Resolve the inbound CSRF token value, choosing the decoder by location.
     *
     * <p>If the SPA sent {@code X-XSRF-TOKEN} we treat the value as plain
     * (unmasked cookie value echoed by the SPA). Otherwise we let the XOR
     * handler decode the masked form-field value.
     *
     * @param request   the inbound request
     * @param csrfToken the expected token (issued by the previous response)
     * @return the resolved token value, or {@code null} if no candidate is
     *         present
     */
    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        String headerValue = request.getHeader(csrfToken.getHeaderName());
        if (StringUtils.hasText(headerValue)) {
            return this.plain.resolveCsrfTokenValue(request, csrfToken);
        }
        return this.xor.resolveCsrfTokenValue(request, csrfToken);
    }
}
