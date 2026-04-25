package ch.owt.boatapp.domain.service;

import ch.owt.boatapp.domain.port.in.ManageBoatsUseCase;

/**
 * Pure-Java implementation of {@link ManageBoatsUseCase}.
 *
 * <p>Receives outbound-port collaborators via constructor injection (wired by
 * {@code BeanConfig} in {@code infrastructure.config}). Carries no Spring
 * annotations: ArchUnit forbids {@code @Service}, {@code @Component},
 * {@code @Transactional} in {@code domain.*}.
 *
 * <p>Method bodies (and the constructor itself) are added in step 02a3.
 */
public final class BoatDomainService implements ManageBoatsUseCase {
}
