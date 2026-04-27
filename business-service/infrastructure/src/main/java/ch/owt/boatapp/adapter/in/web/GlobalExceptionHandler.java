package ch.owt.boatapp.adapter.in.web;

import ch.owt.boatapp.adapter.in.web.dto.generated.Severity;
import ch.owt.boatapp.adapter.in.web.dto.generated.ValidationMessageResponse;
import ch.owt.boatapp.adapter.in.web.mapper.BoatWebMapper;
import ch.owt.boatapp.domain.exception.BoatNotFoundException;
import ch.owt.boatapp.domain.exception.ConcurrentModificationException;
import ch.owt.boatapp.domain.exception.ValidationFailureException;
import jakarta.persistence.OptimisticLockException;
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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Global REST exception handler. Translates every uncaught exception thrown
 * from a {@code @RestController} into an RFC 9457 {@link ProblemDetail}
 * response with the {@code application/problem+json} media type and a
 * negotiated {@code Content-Language} header.
 *
 * <p>Every response carries a populated {@code type} from
 * {@link ProblemTypes} (never {@code about:blank}) and a populated
 * {@code instance} (the request URI). Multi-error problems (400 syntactic,
 * 422 semantic) populate the {@code messages} extension member with one
 * entry per finding; other statuses leave it absent.
 *
 * <p>Every handler logs at {@code WARN} (4xx) or {@code ERROR} (5xx) with
 * the HTTP method, path and authenticated user id (or {@code dev-user} when
 * anonymous). Stack traces are only emitted for the 5xx fallback — never for
 * client errors.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String DEV_USER = "dev-user";

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

    // ── 400 Bad Request — body-level Bean Validation ────────────────────────

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
                "One or more fields failed validation.",
                req);
        problem.setProperty("messages", messages);
        log.warn("400 validation at {} {} user={} — {} field error(s)",
                req.getMethod(), req.getRequestURI(), currentUserId(), messages.size());
        return responseFor(problem, locale);
    }

    // ── 400 Bad Request — path / query parameter Bean Validation ────────────

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
                "One or more parameters failed validation.",
                req);
        problem.setProperty("messages", messages);
        log.warn("400 constraint violation at {} {} user={} — {} violation(s)",
                req.getMethod(), req.getRequestURI(), currentUserId(), messages.size());
        return responseFor(problem, locale);
    }

    // ── 400 Bad Request — malformed JSON body ───────────────────────────────

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
        log.warn("400 malformed body at {} {} user={}",
                req.getMethod(), req.getRequestURI(), currentUserId());
        return responseFor(problem, locale);
    }

    // ── 400 Bad Request — malformed If-Match header ─────────────────────────

    /**
     * @param ex  the typed exception thrown by the boat controller when the
     *            inbound {@code If-Match} header is not a bare integer
     * @param req the inbound request — used for {@code instance} and logging
     * @return RFC 9457 400 with a single {@code messages} entry of code
     *         {@code field.format.invalid}
     *
     * <p>Note: only this typed exception is mapped to 400; a generic
     * {@link IllegalArgumentException} is intentionally left to the
     * fallback handler so domain value-object invariant failures
     * (e.g. {@code BoatId} compact-constructor rejections) and stray
     * library {@code IAE}s surface as 500 — they indicate server-side
     * bugs, not client errors.
     */
    @ExceptionHandler(MalformedIfMatchException.class)
    public ResponseEntity<ProblemDetail> handleMalformedIfMatch(
            MalformedIfMatchException ex, HttpServletRequest req) {
        Locale locale = LocaleContextHolder.getLocale();
        String code = "field.format.invalid";
        String message = messageSource.getMessage(code, new Object[]{"If-Match"}, code, locale);
        ProblemDetail problem = baseProblem(HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION,
                "Request validation failed", message, req);
        ValidationMessageResponse entry = new ValidationMessageResponse(Severity.ERROR, code, message);
        entry.setField("If-Match");
        problem.setProperty("messages", List.of(entry));
        log.warn("400 malformed If-Match at {} {} user={}",
                req.getMethod(), req.getRequestURI(), currentUserId());
        return responseFor(problem, locale);
    }

    // ── 422 Unprocessable Entity — domain validation failure ────────────────

    /**
     * @param ex  the gateway exception thrown by
     *            {@code BoatTransactionalGateway} when the use-case returned
     *            a {@code ServiceResponse} with {@code hasErrors() == true}
     * @param req the inbound request — used for {@code instance} and logging
     * @return RFC 9457 422 with one entry in {@code messages} per domain finding
     */
    @ExceptionHandler(ValidationFailureException.class)
    public ResponseEntity<ProblemDetail> handleValidationFailure(
            ValidationFailureException ex, HttpServletRequest req) {
        Locale locale = LocaleContextHolder.getLocale();
        List<ValidationMessageResponse> messages = BoatWebMapper.toWire(ex.getMessages(), messageSource, locale);
        ProblemDetail problem = baseProblem(HttpStatus.UNPROCESSABLE_ENTITY, ProblemTypes.VALIDATION,
                "Business rule violated",
                "One or more domain rules were violated.",
                req);
        problem.setProperty("messages", messages);
        log.warn("422 domain validation at {} {} user={} — {} message(s)",
                req.getMethod(), req.getRequestURI(), currentUserId(), messages.size());
        return responseFor(problem, locale);
    }

    // ── 404 Not Found ────────────────────────────────────────────────────────

    /**
     * @param ex  the not-found exception thrown by the domain when the boat
     *            with the requested id does not exist
     * @param req the inbound request — used for {@code instance} and logging
     * @return RFC 9457 404 with no {@code messages}
     */
    @ExceptionHandler(BoatNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleBoatNotFound(
            BoatNotFoundException ex, HttpServletRequest req) {
        Locale locale = LocaleContextHolder.getLocale();
        ProblemDetail problem = baseProblem(HttpStatus.NOT_FOUND, ProblemTypes.NOT_FOUND,
                "Boat not found",
                "No boat exists with id " + ex.getBoatId().value() + ".",
                req);
        log.warn("404 boat not found at {} {} user={} id={}",
                req.getMethod(), req.getRequestURI(), currentUserId(), ex.getBoatId().value());
        return responseFor(problem, locale);
    }

    // ── 409 Conflict — optimistic-lock mismatch ──────────────────────────────

    /**
     * @param ex  either the domain {@link ConcurrentModificationException}
     *            (raised by the explicit version check in the use-case) or a
     *            JPA {@link OptimisticLockException} (raised at flush as a
     *            defense-in-depth guard)
     * @param req the inbound request — used for {@code instance} and logging
     * @return RFC 9457 409 with no {@code messages}
     */
    @ExceptionHandler({ConcurrentModificationException.class, OptimisticLockException.class})
    public ResponseEntity<ProblemDetail> handleConcurrentModification(
            Exception ex, HttpServletRequest req) {
        Locale locale = LocaleContextHolder.getLocale();
        ProblemDetail problem = baseProblem(HttpStatus.CONFLICT, ProblemTypes.CONCURRENCY_CONFLICT,
                "Concurrent modification",
                "The boat was modified by another request — reload and retry.",
                req);
        log.warn("409 concurrency conflict at {} {} user={} — {}",
                req.getMethod(), req.getRequestURI(), currentUserId(), ex.getMessage());
        return responseFor(problem, locale);
    }

    // ── 428 Precondition Required — If-Match missing ────────────────────────

    /**
     * @param ex  the missing-header exception raised when a required request
     *            header (e.g. {@code If-Match}) was not supplied
     * @param req the inbound request — used for {@code instance} and logging
     * @return RFC 9457 428 with no {@code messages}
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ProblemDetail> handleMissingHeader(
            MissingRequestHeaderException ex, HttpServletRequest req) {
        Locale locale = LocaleContextHolder.getLocale();
        String code = "request.header.if-match.required";
        String detail = messageSource.getMessage(code, new Object[]{ex.getHeaderName()}, code, locale);
        ProblemDetail problem = baseProblem(HttpStatus.PRECONDITION_REQUIRED, ProblemTypes.PRECONDITION_REQUIRED,
                ex.getHeaderName() + " header required", detail, req);
        log.warn("428 missing header {} at {} {} user={}",
                ex.getHeaderName(), req.getMethod(), req.getRequestURI(), currentUserId());
        return responseFor(problem, locale);
    }

    // ── 500 Internal Server Error — fallback ────────────────────────────────

    /**
     * @param ex  any uncaught exception not handled above — by definition a
     *            server-side bug or an integration failure
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
        log.error("500 unhandled exception at {} {} user={}",
                req.getMethod(), req.getRequestURI(), currentUserId(), ex);
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

    private static String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            String sub = jwt.getToken().getSubject();
            return sub != null ? sub : DEV_USER;
        }
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            try {
                return UUID.fromString(auth.getName()).toString();
            } catch (IllegalArgumentException ignored) {
                return auth.getName();
            }
        }
        return DEV_USER;
    }
}
