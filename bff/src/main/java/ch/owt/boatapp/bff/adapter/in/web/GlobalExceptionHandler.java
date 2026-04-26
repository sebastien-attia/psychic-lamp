package ch.owt.boatapp.bff.adapter.in.web;

import ch.owt.boatapp.bff.adapter.in.web.dto.generated.Severity;
import ch.owt.boatapp.bff.adapter.in.web.dto.generated.ValidationMessageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.metadata.ConstraintDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * Centralized exception → RFC 9457 {@link ProblemDetail} mapper for the BFF.
 *
 * <p>Since the SCG migration the BFF only handles errors raised by its own
 * locally-served endpoints ({@code /api/me}, {@code /.well-known/jwks.json},
 * {@code /api/logout}, {@code /actuator/**}). Upstream errors from the
 * Business Service no longer pass through this advice — Spring Cloud Gateway
 * forwards the Business Service's RFC 9457 response back to the client
 * unchanged (the Business Service already emits the registry-defined
 * {@code type} URIs, populated {@code messages}, etc.). Upstream 5xx without
 * an RFC 9457 body is rewritten to a 502 {@code upstream-failure} envelope
 * by a dedicated SCG response filter (see {@code ScgUpstreamFailureFilter}),
 * NOT by this advice.
 *
 * <p>Reachability of the validation handlers is narrow now:
 * {@code spring-boot-starter-validation} was removed from the BFF along with
 * the SPA-edge {@code @Valid} contracts, so {@link MethodArgumentNotValidException}
 * and {@link ConstraintViolationException} are not raised by anything on the
 * current BFF surface. They are kept for shape-symmetry with the Business
 * Service and would only fire if a future BFF-local endpoint re-introduces
 * Bean Validation. Today, only {@link HttpMessageNotReadableException},
 * {@link MissingRequestHeaderException}, {@link AuthenticationException},
 * {@link NoResourceFoundException} and the {@link Exception} fallback are
 * actually reachable from BFF-local endpoints ({@code /api/me},
 * {@code /api/logout}).
 *
 * <p>Every response carries {@code Content-Type: application/problem+json}
 * and {@code Content-Language}. The required SLF4J {@link Logger} field
 * (per the BFF ArchUnit rule) backs every handler; {@code WARN} for 4xx,
 * {@code ERROR} (with stack trace) for 5xx.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final MessageSource messageSource;

    /**
     * @param messageSource Spring's i18n source — resolves application codes
     *                      (e.g. {@code field.required}) into the localized
     *                      {@code message} field of {@link ValidationMessageResponse}
     *                      and the {@code detail} field of the problem
     */
    public GlobalExceptionHandler(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    // ── Class 1: locally raised — Bean Validation on the body ───────────────

    /**
     * @param ex  the body-validation failure raised by Spring MVC after
     *            {@code @Valid @RequestBody} rejected the deserialized body
     * @param req the inbound request — used for {@code instance} and logging
     * @return RFC 9457 400 with one entry in {@code messages} per offending field
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpServletRequest req) {
        Locale locale = LocaleContextHolder.getLocale();
        List<ValidationMessageResponse> messages = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fieldErrorToWire(fe, locale))
                .toList();
        ProblemDetail problem = baseProblem(HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION,
                "Request validation failed",
                "One or more fields failed validation.", req);
        problem.setProperty("messages", messages);
        log.warn("400 validation at {} {} — {} field error(s)",
                req.getMethod(), req.getRequestURI(), messages.size());
        return responseFor(problem, locale);
    }

    // ── Class 1: locally raised — Bean Validation on path / query ───────────

    /**
     * @param ex  the constraint-violation failure raised by Spring's method
     *            validation interceptor for a {@code @PathVariable} or
     *            {@code @RequestParam} that violated a Jakarta constraint
     * @param req the inbound request — used for {@code instance} and logging
     * @return RFC 9457 400 with one entry in {@code messages} per violation
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest req) {
        Locale locale = LocaleContextHolder.getLocale();
        List<ValidationMessageResponse> messages = ex.getConstraintViolations().stream()
                .map(cv -> violationToWire(cv, locale))
                .toList();
        ProblemDetail problem = baseProblem(HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION,
                "Request validation failed",
                "One or more parameters failed validation.", req);
        problem.setProperty("messages", messages);
        log.warn("400 constraint violation at {} {} — {} violation(s)",
                req.getMethod(), req.getRequestURI(), messages.size());
        return responseFor(problem, locale);
    }

    // ── Class 1: locally raised — malformed JSON body ───────────────────────

    /**
     * @param ex  the deserialization failure raised by Spring MVC when the
     *            inbound body could not be parsed into the request DTO
     * @param req the inbound request — used for {@code instance} and logging
     * @return RFC 9457 400 with a single {@code messages} entry of code
     *         {@code request.body.malformed}
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleMessageNotReadable(
            HttpMessageNotReadableException ex, HttpServletRequest req) {
        Locale locale = LocaleContextHolder.getLocale();
        String code = "request.body.malformed";
        String message = messageSource.getMessage(code, new Object[]{""}, code, locale);
        ProblemDetail problem = baseProblem(HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION,
                "Malformed request body", message, req);
        problem.setProperty("messages", List.of(
                new ValidationMessageResponse(Severity.ERROR, code, message)));
        log.warn("400 malformed body at {} {}", req.getMethod(), req.getRequestURI());
        return responseFor(problem, locale);
    }

    // ── Class 1: locally raised — If-Match header missing ───────────────────

    /**
     * @param ex  the missing-header exception raised when the inbound
     *            request omitted a required header (e.g. {@code If-Match})
     * @param req the inbound request — used for {@code instance} and logging
     * @return RFC 9457 428 with no {@code messages}
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ProblemDetail> handleMissingHeader(
            MissingRequestHeaderException ex, HttpServletRequest req) {
        Locale locale = LocaleContextHolder.getLocale();
        String code = "request.header.if-match.required";
        String detail = messageSource.getMessage(code, new Object[]{ex.getHeaderName()}, code, locale);
        ProblemDetail problem = baseProblem(HttpStatus.PRECONDITION_REQUIRED,
                ProblemTypes.PRECONDITION_REQUIRED,
                ex.getHeaderName() + " header required", detail, req);
        log.warn("428 missing header {} at {} {}",
                ex.getHeaderName(), req.getMethod(), req.getRequestURI());
        return responseFor(problem, locale);
    }

    // ── 401 from a downstream-call AuthenticationException ──────────────────

    /**
     * Convert an {@link AuthenticationException} raised inside a request
     * handler (typically by a Spring Security collaborator that the SCG
     * TokenRelay filter or its peers expose to the controller layer) into the
     * same RFC 9457 401 envelope that {@code RestAuthenticationEntryPoint}
     * emits for missing-session calls.
     *
     * <p>Without this handler the exception would fall through to
     * {@link #handleFallback(Exception, HttpServletRequest)} and surface as
     * 500, leaking an OAuth2 stack-trace fragment to the SPA.
     *
     * @param ex  the auth exception raised inside a request handler (NOT by
     *            the security filter chain — those go to
     *            {@code RestAuthenticationEntryPoint})
     * @param req the inbound request — used for {@code instance} and logging
     * @return RFC 9457 401 with no {@code messages}
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthenticationException(
            AuthenticationException ex, HttpServletRequest req) {
        Locale locale = LocaleContextHolder.getLocale();
        String code = "auth.required";
        String detail = messageSource.getMessage(code, new Object[0], code, locale);
        ProblemDetail problem = baseProblem(HttpStatus.UNAUTHORIZED, ProblemTypes.AUTH_REQUIRED,
                "Authentication required", detail, req);
        log.warn("401 from interceptor at {} {} — {}",
                req.getMethod(), req.getRequestURI(), ex.getMessage());
        return responseFor(problem, locale);
    }

    // ── 404 for stray static-resource probes (e.g. Chrome DevTools, .well-known) ─

    /**
     * Convert Spring MVC's {@link NoResourceFoundException} (raised when a
     * request fails to match any controller and there is no matching static
     * resource) into a clean RFC 9457 404 instead of letting it fall through
     * to {@link #handleFallback(Exception, HttpServletRequest)} as a 500.
     *
     * <p>Common offender: Chromium-based browsers probe
     * {@code /.well-known/appspecific/com.chrome.devtools.json} on every page
     * load. Mapping it here keeps the access logs honest (no spurious 500s)
     * and avoids flooding the error log with stack traces for what is, by
     * definition, a routine miss.
     *
     * @param ex  the no-resource exception raised by Spring MVC
     * @param req the inbound request — used for {@code instance} and logging
     * @return RFC 9457 404 with no {@code messages}
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetail> handleNoResourceFound(
            NoResourceFoundException ex, HttpServletRequest req) {
        Locale locale = LocaleContextHolder.getLocale();
        String code = "not.found.resource";
        String detail = messageSource.getMessage(code, new Object[0], code, locale);
        ProblemDetail problem = baseProblem(HttpStatus.NOT_FOUND, ProblemTypes.NOT_FOUND,
                "Resource not found", detail, req);
        log.debug("404 no static resource at {} {} — {}",
                req.getMethod(), req.getRequestURI(), ex.getMessage());
        return responseFor(problem, locale);
    }

    // ── 500 fallback ────────────────────────────────────────────────────────

    /**
     * @param ex  any uncaught exception not handled above — by definition a
     *            BFF-side bug
     * @param req the inbound request — used for {@code instance} and logging
     * @return RFC 9457 500 with no {@code messages} and no leakage of
     *         {@code ex.getMessage()} to the wire
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleFallback(Exception ex, HttpServletRequest req) {
        Locale locale = LocaleContextHolder.getLocale();
        String code = "internal.error";
        String detail = messageSource.getMessage(code, new Object[]{}, code, locale);
        ProblemDetail problem = baseProblem(HttpStatus.INTERNAL_SERVER_ERROR, ProblemTypes.INTERNAL,
                "Internal server error", detail, req);
        log.error("500 unhandled exception at {} {}", req.getMethod(), req.getRequestURI(), ex);
        return responseFor(problem, locale);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private ProblemDetail baseProblem(HttpStatus status, URI type, String title, String detail,
                                      HttpServletRequest req) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(type);
        problem.setTitle(title);
        problem.setDetail(detail);
        problem.setInstance(URI.create(req.getRequestURI()));
        return problem;
    }

    private ResponseEntity<ProblemDetail> responseFor(ProblemDetail problem, Locale locale) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        headers.set(HttpHeaders.CONTENT_LANGUAGE, locale.toLanguageTag());
        // HttpStatusCode.valueOf accepts any valid HTTP code (including non-standard
        // ones); it never throws, so the advice chain stays robust even if a future
        // handler sets an unusual status.
        return new ResponseEntity<>(problem, headers, HttpStatusCode.valueOf(problem.getStatus()));
    }

    private ValidationMessageResponse fieldErrorToWire(FieldError fe, Locale locale) {
        String code = JakartaCodeTranslator.toApplicationCode(fe.getCode());
        String field = fe.getField();
        String message = messageSource.getMessage(code, new Object[]{field}, code, locale);
        ValidationMessageResponse out = new ValidationMessageResponse(Severity.ERROR, code, message);
        out.setField(field);
        return out;
    }

    private ValidationMessageResponse violationToWire(ConstraintViolation<?> cv, Locale locale) {
        ConstraintDescriptor<?> descriptor = cv.getConstraintDescriptor();
        String jakartaName = descriptor != null && descriptor.getAnnotation() != null
                ? descriptor.getAnnotation().annotationType().getSimpleName()
                : null;
        String code = JakartaCodeTranslator.toApplicationCode(jakartaName);
        String field = lastPathSegment(cv.getPropertyPath().toString());
        String message = messageSource.getMessage(code, new Object[]{field}, code, locale);
        ValidationMessageResponse out = new ValidationMessageResponse(Severity.ERROR, code, message);
        out.setField(field);
        return out;
    }

    private static String lastPathSegment(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot + 1) : path;
    }
}
