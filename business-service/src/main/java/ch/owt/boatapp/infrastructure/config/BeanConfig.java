package ch.owt.boatapp.infrastructure.config;

import ch.owt.boatapp.domain.port.in.GetUserUseCase;
import ch.owt.boatapp.domain.port.in.ManageBoatsUseCase;
import ch.owt.boatapp.domain.port.out.AppUserRepositoryPort;
import ch.owt.boatapp.domain.port.out.BoatAuditRepositoryPort;
import ch.owt.boatapp.domain.port.out.BoatRepositoryPort;
import ch.owt.boatapp.domain.service.BoatDomainService;
import ch.owt.boatapp.domain.service.UserDomainService;
import ch.owt.boatapp.domain.service.validation.SemanticValidator;
import ch.owt.boatapp.domain.service.validation.SyntacticValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Wires the pure-Java domain services as Spring beans, injecting their
 * outbound-port collaborators.
 *
 * <p>The domain itself carries no Spring annotations — this is the only
 * place where {@code BoatDomainService} and {@code UserDomainService} become
 * managed components. Bean methods return the inbound-port interfaces so
 * the bridge layer depends on the contracts rather than the implementations.
 */
@Configuration
public class BeanConfig {

    /**
     * @return a UTC clock used by domain services for timestamping
     *         {@code createdAt}, {@code performedAt}, {@code firstLogin} and
     *         {@code lastLogin}; overridable in tests
     */
    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }

    /**
     * @return the syntactic, defense-in-depth validator used by
     *         {@link BoatDomainService}
     */
    @Bean
    public SyntacticValidator syntacticValidator() {
        return new SyntacticValidator();
    }

    /**
     * @return the semantic (business-rule) validator used by
     *         {@link BoatDomainService}; placeholder until rules are added
     */
    @Bean
    public SemanticValidator semanticValidator() {
        return new SemanticValidator();
    }

    /**
     * Wire the boat use-case implementation.
     *
     * @param boatRepository      boat persistence outbound port
     * @param boatAuditRepository audit-row persistence outbound port (INSERT-ONLY)
     * @param syntacticValidator  domain-side syntactic validator
     * @param semanticValidator   domain-side semantic validator
     * @param clock               UTC clock for timestamping
     * @return the boat use-case bean (interface return type — bridge depends on the contract)
     */
    @Bean
    public ManageBoatsUseCase manageBoatsUseCase(BoatRepositoryPort boatRepository,
                                                 BoatAuditRepositoryPort boatAuditRepository,
                                                 SyntacticValidator syntacticValidator,
                                                 SemanticValidator semanticValidator,
                                                 Clock clock) {
        return new BoatDomainService(boatRepository, boatAuditRepository,
                syntacticValidator, semanticValidator, clock);
    }

    /**
     * Wire the user use-case implementation.
     *
     * @param appUserRepository app-user persistence outbound port
     * @param clock             UTC clock for {@code firstLogin} / {@code lastLogin}
     * @return the user use-case bean (interface return type)
     */
    @Bean
    public GetUserUseCase getUserUseCase(AppUserRepositoryPort appUserRepository, Clock clock) {
        return new UserDomainService(appUserRepository, clock);
    }
}
