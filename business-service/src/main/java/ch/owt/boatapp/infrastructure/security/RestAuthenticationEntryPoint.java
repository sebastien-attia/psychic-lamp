package ch.owt.boatapp.infrastructure.security;

import ch.owt.boatapp.adapter.in.web.ProblemTypes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;

/**
 * Spring Security entry point that emits an RFC 9457 {@link ProblemDetail}
 * envelope for unauthenticated requests, in place of the default
 * {@code BearerTokenAuthenticationEntryPoint} (which would write an empty
 * 401 body and a {@code WWW-Authenticate} header only).
 *
 * <p>Per {@code .claude/rules/validation-and-errors.md}, 401 responses are
 * produced by the Spring Security entry point — NOT by
 * {@code GlobalExceptionHandler} — because the security filter chain commits
 * the response before the {@code @ControllerAdvice} chain is reached.
 *
 * <p>The wire shape matches every other 4xx response: {@code type} from the
 * registry, {@code instance} = request URI, localized {@code detail} via
 * {@code messages.properties}, {@code Content-Type:
 * application/problem+json}, {@code Content-Language} negotiated via
 * {@code Accept-Language}.
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(RestAuthenticationEntryPoint.class);

    private final ObjectMapper objectMapper;
    private final MessageSource messageSource;

    /**
     * @param objectMapper  Jackson 3 mapper used to serialize the
     *                      {@link ProblemDetail} response body
     * @param messageSource i18n source for the {@code auth.required}
     *                      application code; resolved against
     *                      {@code LocaleContextHolder.getLocale()}
     */
    public RestAuthenticationEntryPoint(ObjectMapper objectMapper, MessageSource messageSource) {
        this.objectMapper = objectMapper;
        this.messageSource = messageSource;
    }

    /**
     * Commit a 401 RFC 9457 response on behalf of the security filter chain.
     *
     * @param request       the inbound request — used for {@code instance}
     *                      and access logging
     * @param response      the response to populate (status, headers, body)
     * @param authException the security exception that triggered the entry
     *                      point; the message is logged but never leaked to
     *                      the wire
     * @throws IOException if the response writer cannot be flushed
     */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        Locale locale = LocaleContextHolder.getLocale();
        String detail = messageSource.getMessage(
                "auth.required", new Object[0], "Authentication required", locale);

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        problem.setType(ProblemTypes.AUTH_REQUIRED);
        problem.setTitle("Authentication required");
        problem.setDetail(detail);
        problem.setInstance(URI.create(request.getRequestURI()));

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader(HttpHeaders.CONTENT_LANGUAGE, locale.toLanguageTag());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, wwwAuthenticate(authException));
        response.getWriter().write(objectMapper.writeValueAsString(problem));

        log.warn("401 unauthenticated at {} {} — {}",
                request.getMethod(), request.getRequestURI(), authException.getMessage());
    }

    /**
     * Build the {@code WWW-Authenticate} header value per RFC 6750 §3.
     *
     * <p>Standard clients (and the BFF's {@code RestClient}) inspect this
     * header to decide whether the request needs a fresh token vs other
     * remediation. Carrying the {@code error} parameter when available
     * surfaces JWT-validation failures (expired, malformed) without leaking
     * the full exception message into the response body.
     */
    private static String wwwAuthenticate(AuthenticationException authException) {
        if (authException instanceof OAuth2AuthenticationException oae && oae.getError() != null) {
            return "Bearer error=\"" + oae.getError().getErrorCode() + "\"";
        }
        return "Bearer";
    }
}
