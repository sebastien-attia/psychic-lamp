package ch.owt.boatapp.infrastructure.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bridge between the inbound REST adapter and the pure-Java domain
 * {@code ManageBoatsUseCase}.
 *
 * <p>This is the only layer that owns transactions: each public method runs
 * inside a {@code @Transactional} boundary, audit-log appends and boat
 * mutations commit together. The domain itself is transaction-unaware.
 *
 * <p>Method bodies are added in step 02a3.
 */
@Service
@Transactional
public class BoatApplicationService {
}
