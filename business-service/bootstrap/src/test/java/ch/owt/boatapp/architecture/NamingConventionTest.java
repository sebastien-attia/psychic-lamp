package ch.owt.boatapp.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * Naming-convention rules for the Business Service. Mirrors the suffixes
 * documented in {@code .claude/rules/business-service-java.md} and
 * {@code CLAUDE.md > Conventions} so a misnamed class fails the build.
 *
 * <p>Suffixes after the multi-module / canonical-Hombergs refactor:
 * <ul>
 *   <li>{@code @RestController} → {@code *Controller}</li>
 *   <li>{@code application.service.@Service} → {@code *ApplicationService}</li>
 *   <li>{@code adapter.out.persistence.@Repository} → {@code *RepositoryAdapter}</li>
 *   <li>{@code @jakarta.persistence.Entity} → {@code *JpaEntity}</li>
 *   <li>{@code application.port.in} records ending {@code Command} or
 *       {@code Query} must be records</li>
 * </ul>
 */
@AnalyzeClasses(
        packages = "ch.owt.boatapp",
        importOptions = ImportOption.DoNotIncludeTests.class)
class NamingConventionTest {

    /** {@code @RestController} classes are suffixed {@code Controller}. */
    @ArchTest
    static final ArchRule controllers_suffixed_Controller =
            classes().that().areAnnotatedWith(RestController.class)
                    .should().haveSimpleNameEndingWith("Controller");

    /**
     * Spring-managed application services (annotated {@code @Service}) end
     * with {@code ApplicationService} to mark them as transactional bridges
     * over the use-case implementations.
     */
    @ArchTest
    static final ArchRule application_services_suffixed_ApplicationService =
            classes().that().areAnnotatedWith(Service.class)
                    .and().resideInAPackage("..application.service..")
                    .should().haveSimpleNameEndingWith("ApplicationService");

    /** Persistence adapters are suffixed {@code RepositoryAdapter}. */
    @ArchTest
    static final ArchRule repository_adapters_suffixed_RepositoryAdapter =
            classes().that().areAnnotatedWith(Repository.class)
                    .should().haveSimpleNameEndingWith("RepositoryAdapter");

    /** JPA entities are suffixed {@code JpaEntity} to keep them distinct from domain models. */
    @ArchTest
    static final ArchRule jpa_entities_suffixed_JpaEntity =
            classes().that().areAnnotatedWith(jakarta.persistence.Entity.class)
                    .should().haveSimpleNameEndingWith("JpaEntity");

    /** {@code Command} types in {@code application.port.in} are immutable input records. */
    @ArchTest
    static final ArchRule commands_in_port_in_are_records =
            classes().that().resideInAPackage("..application.port.in..")
                    .and().haveSimpleNameEndingWith("Command")
                    .should().beRecords()
                    .as("Command classes in application.port.in must be records (immutable inputs)");

    /** {@code Query} types in {@code application.port.in} are immutable input records. */
    @ArchTest
    static final ArchRule queries_in_port_in_are_records =
            classes().that().resideInAPackage("..application.port.in..")
                    .and().haveSimpleNameEndingWith("Query")
                    .should().beRecords()
                    .as("Query classes in application.port.in must be records (immutable inputs)");
}
