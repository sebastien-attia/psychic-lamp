package ch.owt.boatapp.bff.infrastructure.web;

import ch.owt.boatapp.bff.adapter.in.web.ProblemTypes;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Profile;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;

/**
 * Servlet filter that rewrites Spring Cloud Gateway upstream 5xx responses
 * lacking an RFC 9457 body into the BFF's standard
 * {@code application/problem+json} 502 envelope (type
 * {@code .../upstream-failure}).
 *
 * <p>Scope:
 * <ul>
 *   <li>Only fires for requests routed through SCG (path prefix
 *       {@code /api/v1/}). BFF-local endpoints
 *       ({@code /api/me}, {@code /actuator/**}, {@code /.well-known/jwks.json})
 *       remain handled by {@code GlobalExceptionHandler}'s
 *       {@code @ExceptionHandler}s.</li>
 *   <li>Only rewrites 5xx responses. Upstream 4xx is passed through
 *       byte-identical, because the Business Service already emits its own
 *       RFC 9457 envelope for those statuses (see the project's
 *       {@code .claude/rules/validation-and-errors.md}).</li>
 *   <li>If the upstream response is already
 *       {@code application/problem+json} we leave the body alone — the BS
 *       may legitimately emit a 5xx envelope (e.g. an internal failure
 *       documented in the Problem Type registry); rewriting it would lose
 *       fidelity.</li>
 * </ul>
 *
 * <p>The filter is gated on {@code @Profile("!dev")} because the dev
 * profile does not start the BFF and the SCG routes are not loaded — there
 * is nothing to wrap.
 */
@Component
@Profile("!dev")
public class ScgUpstreamFailureFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ScgUpstreamFailureFilter.class);

    private static final String SCG_ROUTE_PREFIX = "/api/v1/";

    private final ObjectMapper objectMapper;
    private final MessageSource messageSource;

    /**
     * @param objectMapper  Jackson 3 mapper used to serialize the rewritten
     *                      {@link ProblemDetail} body
     * @param messageSource i18n source for the {@code upstream.failure}
     *                      application code; resolved against
     *                      {@code LocaleContextHolder.getLocale()}
     */
    public ScgUpstreamFailureFilter(ObjectMapper objectMapper, MessageSource messageSource) {
        this.objectMapper = objectMapper;
        this.messageSource = messageSource;
    }

    /**
     * Wrap the response, let SCG (or any other handler) write into it, then
     * inspect the committed status. If the status is 5xx and the body is not
     * {@code application/problem+json}, replace the response with a 502
     * RFC 9457 envelope. Otherwise copy the buffered body to the original
     * response untouched.
     *
     * @param request  the inbound HTTP request
     * @param response the outbound HTTP response (wrapped for inspection)
     * @param chain    the rest of the filter chain
     * @throws ServletException if the chain throws
     * @throws IOException      if response writing fails
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!isScgRouted(request)) {
            chain.doFilter(request, response);
            return;
        }

        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
        chain.doFilter(request, wrapped);

        int status = wrapped.getStatus();
        String contentType = wrapped.getContentType();
        boolean upstreamFailure = status >= 500
                && (contentType == null || !contentType.startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE));

        if (upstreamFailure) {
            log.warn("Rewriting upstream {} to 502 upstream-failure at {} {}",
                    status, request.getMethod(), request.getRequestURI());
            writeUpstreamFailureEnvelope(request, response);
        } else {
            wrapped.copyBodyToResponse();
        }
    }

    private boolean isScgRouted(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri != null && uri.startsWith(SCG_ROUTE_PREFIX);
    }

    private void writeUpstreamFailureEnvelope(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        Locale locale = LocaleContextHolder.getLocale();
        String detail = messageSource.getMessage(
                "upstream.failure", new Object[0],
                "The upstream service returned an unexpected error.", locale);

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_GATEWAY);
        problem.setType(ProblemTypes.UPSTREAM_FAILURE);
        problem.setTitle("Upstream service failed");
        problem.setDetail(detail);
        problem.setInstance(URI.create(request.getRequestURI()));

        response.reset();
        response.setStatus(HttpStatus.BAD_GATEWAY.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader(HttpHeaders.CONTENT_LANGUAGE, locale.toLanguageTag());
        response.getWriter().write(objectMapper.writeValueAsString(problem));
    }
}
