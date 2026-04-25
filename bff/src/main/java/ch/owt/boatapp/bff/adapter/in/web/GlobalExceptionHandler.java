package ch.owt.boatapp.bff.adapter.in.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Centralized exception → RFC 9457 {@code ProblemDetail} mapper for the BFF.
 *
 * <p>Translates Bean Validation failures (400), missing {@code If-Match}
 * headers (428), upstream Business Service failures (4xx pass-through; 5xx
 * wrapped as 502 {@code upstream-failure}) and the catch-all 500. Handler
 * bodies and the {@code Problem-type} URI registry are wired in step 02a4.
 *
 * <p>The SLF4J {@link Logger} field below is required by the BFF ArchUnit
 * rule on {@code GlobalExceptionHandler} — it guarantees that every error
 * surfaced through this advice can be correlated with a server-side log
 * line. Do not remove it.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @SuppressWarnings("unused") // wired up by handler methods in step 02a4
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
}
