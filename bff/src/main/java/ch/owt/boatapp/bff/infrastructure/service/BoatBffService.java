package ch.owt.boatapp.bff.infrastructure.service;

import org.springframework.stereotype.Service;

/**
 * Thin orchestration layer between the BFF web adapter and the generated
 * {@code BusinessServiceClient} HTTP-Interface client.
 *
 * <p>Has NO {@code @Transactional} annotation and NO domain logic — it only
 * forwards requests, with the OAuth2 {@code access_token} attached as a
 * {@code Bearer} header via the BFF's {@code RestClient} interceptor. ArchUnit
 * forbids {@code @Transactional} anywhere in the BFF.
 *
 * <p>Method bodies are added in step 02a3 once the generated client interface
 * is on the classpath.
 */
@Service
public class BoatBffService {
}
