package ch.owt.boatapp.adapter.in.web;

import ch.owt.boatapp.adapter.in.web.dto.generated.ValidationMessageResponse;
import ch.owt.boatapp.domain.exception.BoatNotFoundException;
import ch.owt.boatapp.domain.exception.ConcurrentModificationException;
import ch.owt.boatapp.domain.exception.ValidationFailureException;
import ch.owt.boatapp.domain.model.BoatId;
import ch.owt.boatapp.domain.model.validation.MessageType;
import ch.owt.boatapp.domain.model.validation.Severity;
import ch.owt.boatapp.domain.model.validation.ValidationMessage;
import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure-Java unit tests for {@link GlobalExceptionHandler}: each handler is
 * driven directly with a hand-crafted exception and a mock
 * {@link HttpServletRequest}, asserting the RFC 9457 envelope (status, type,
 * instance, headers, messages, never {@code about:blank}) without booting
 * Spring MVC. The integration test {@code ValidationAndErrorsIntegrationTest}
 * still covers the wired-up flow end-to-end.
 *
 * <p>Each handler that consults the {@code SecurityContext} (for the
 * {@code currentUserId()} log field) is fed an empty context — the test
 * focuses on the response, not the log.
 */
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private HttpServletRequest request;
    private StaticMessageSource messageSource;

    @BeforeEach
    void setUp() {
        messageSource = new StaticMessageSource();
        // Seed the handful of codes the handlers translate at runtime.
        messageSource.addMessage("request.body.malformed", Locale.ENGLISH, "Body unreadable");
        messageSource.addMessage("field.format.invalid", Locale.ENGLISH, "Field {0} format invalid");
        messageSource.addMessage("request.header.if-match.required", Locale.ENGLISH, "Header {0} required");
        messageSource.addMessage("internal.error", Locale.ENGLISH, "Internal error");

        handler = new GlobalExceptionHandler(messageSource);
        request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/v1/boats/test");
    }

    /** {@code HttpMessageNotReadableException} → 400 with {@code request.body.malformed}. */
    @Test
    void messageNotReadable_returns400_withMalformedCode() {
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException("bad", null, null);

        ResponseEntity<ProblemDetail> response = handler.handleMessageNotReadable(ex, request);

        assertCommonEnvelope(response, HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION);
        assertHasOneMessageWithCode(response, "request.body.malformed");
    }

    /** {@code MalformedIfMatchException} → 400 with {@code field.format.invalid} on {@code If-Match}. */
    @Test
    void malformedIfMatch_returns400_withFormatInvalidOnIfMatch() {
        MalformedIfMatchException ex = new MalformedIfMatchException("not-a-number", new NumberFormatException());

        ResponseEntity<ProblemDetail> response = handler.handleMalformedIfMatch(ex, request);

        assertCommonEnvelope(response, HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION);
        ValidationMessageResponse first = firstMessage(response);
        assertThat(first.getCode()).isEqualTo("field.format.invalid");
        assertThat(first.getField()).isEqualTo("If-Match");
    }

    /** {@link ValidationFailureException} → 422 with one message per domain finding. */
    @Test
    void domainValidation_returns422_withWireMessages() {
        ValidationMessage finding = new ValidationMessage(
                Severity.ERROR, MessageType.INVALID_FORMAT, "Boat.name");
        ValidationFailureException ex = new ValidationFailureException(List.of(finding));

        ResponseEntity<ProblemDetail> response = handler.handleValidationFailure(ex, request);

        assertCommonEnvelope(response, HttpStatus.UNPROCESSABLE_ENTITY, ProblemTypes.VALIDATION);
        assertHasOneMessageWithCode(response, "field.format.invalid");
    }

    /** {@link BoatNotFoundException} → 404 with type {@code .../not-found}, no messages. */
    @Test
    void boatNotFound_returns404_withoutMessages() {
        BoatId id = new BoatId(UUID.randomUUID());
        BoatNotFoundException ex = new BoatNotFoundException(id);

        ResponseEntity<ProblemDetail> response = handler.handleBoatNotFound(ex, request);

        assertCommonEnvelope(response, HttpStatus.NOT_FOUND, ProblemTypes.NOT_FOUND);
        // ProblemDetail.getProperties() returns null when no extension members
        // were set — that is the expected shape for 404 (no `messages[]`).
        assertThat(response.getBody().getProperties()).isNullOrEmpty();
        assertThat(response.getBody().getDetail()).contains(id.value().toString());
    }

    /** Domain {@link ConcurrentModificationException} → 409 with type {@code .../concurrency-conflict}. */
    @Test
    void domainConcurrentModification_returns409() {
        ConcurrentModificationException ex = new ConcurrentModificationException(
                new BoatId(UUID.randomUUID()), 1L, 5L);

        ResponseEntity<ProblemDetail> response = handler.handleConcurrentModification(ex, request);

        assertCommonEnvelope(response, HttpStatus.CONFLICT, ProblemTypes.CONCURRENCY_CONFLICT);
    }

    /** JPA {@link OptimisticLockException} → 409 (defense-in-depth fallback). */
    @Test
    void jpaOptimisticLock_returns409() {
        OptimisticLockException ex = new OptimisticLockException("stale");

        ResponseEntity<ProblemDetail> response = handler.handleConcurrentModification(ex, request);

        assertCommonEnvelope(response, HttpStatus.CONFLICT, ProblemTypes.CONCURRENCY_CONFLICT);
    }

    /** {@link MissingRequestHeaderException} for {@code If-Match} → 428. */
    @Test
    void missingIfMatchHeader_returns428() {
        MissingRequestHeaderException ex = mock(MissingRequestHeaderException.class);
        when(ex.getHeaderName()).thenReturn("If-Match");

        ResponseEntity<ProblemDetail> response = handler.handleMissingHeader(ex, request);

        assertCommonEnvelope(response, HttpStatus.PRECONDITION_REQUIRED, ProblemTypes.PRECONDITION_REQUIRED);
        // Title is built from the header name verbatim — assert on it rather than on
        // the detail, which goes through MessageSource and depends on the request
        // locale resolved by LocaleContextHolder.
        assertThat(response.getBody().getTitle()).contains("If-Match");
    }

    /**
     * Fallback handler: 500 with {@code .../internal}, no leakage of the
     * underlying message or stack-trace tokens to the wire.
     */
    @Test
    void unhandledException_returns500_withoutLeakingStack() {
        RuntimeException ex = new RuntimeException("synthetic explosion");

        ResponseEntity<ProblemDetail> response = handler.handleFallback(ex, request);

        assertCommonEnvelope(response, HttpStatus.INTERNAL_SERVER_ERROR, ProblemTypes.INTERNAL);
        ProblemDetail body = response.getBody();
        assertThat(body.getDetail())
                .as("500 detail must not leak the underlying exception message")
                .doesNotContain("synthetic explosion")
                .doesNotContain("RuntimeException");
    }

    // -- helpers --------------------------------------------------------

    private void assertCommonEnvelope(ResponseEntity<ProblemDetail> response,
                                      HttpStatus expectedStatus,
                                      java.net.URI expectedType) {
        // Compare by numeric value: Spring 6.2 renamed UNPROCESSABLE_ENTITY →
        // UNPROCESSABLE_CONTENT, which made the legacy enum constant a different
        // instance from the one returned by HttpStatusCode.valueOf(422). The
        // wire status (the integer 422) is what the contract guarantees.
        assertThat(response.getStatusCode().value()).isEqualTo(expectedStatus.value());
        ProblemDetail body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getStatus()).isEqualTo(expectedStatus.value());
        assertThat(body.getType()).isEqualTo(expectedType);
        assertThat(body.getInstance()).isEqualTo(java.net.URI.create("/api/v1/boats/test"));

        HttpHeaders headers = response.getHeaders();
        assertThat(headers.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(headers.getFirst(HttpHeaders.CONTENT_LANGUAGE)).isNotBlank();

        // Regression guard: every problem must use a registered type URI.
        assertThat(body.getType().toString())
                .as("Problem type must come from the registry — never about:blank")
                .doesNotContain("about:blank");
    }

    private void assertHasOneMessageWithCode(ResponseEntity<ProblemDetail> response, String code) {
        ValidationMessageResponse first = firstMessage(response);
        assertThat(first.getCode()).isEqualTo(code);
    }

    /**
     * Cast and return the first {@code messages[]} entry — type-safe access to
     * the wire DTO instead of substring-matching {@code toString()}, which is
     * owned by the OpenAPI generator and could change shape on a generator
     * upgrade.
     */
    @SuppressWarnings("unchecked")
    private ValidationMessageResponse firstMessage(ResponseEntity<ProblemDetail> response) {
        Object messagesObj = response.getBody().getProperties().get("messages");
        assertThat(messagesObj).isInstanceOf(List.class);
        List<Object> messages = (List<Object>) messagesObj;
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0)).isInstanceOf(ValidationMessageResponse.class);
        return (ValidationMessageResponse) messages.get(0);
    }
}
