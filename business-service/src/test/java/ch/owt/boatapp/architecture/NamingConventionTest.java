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
 * <p>Suffixes:
 * <ul>
 *   <li>{@code @RestController} → {@code *Controller}</li>
 *   <li>{@code domain.service.*} → {@code *DomainService}
 *       (with the {@code SyntacticValidator} / {@code SemanticValidator}
 *       carve-out under {@code domain.service.validation})</li>
 *   <li>{@code infrastructure.service.@Service} → {@code *ApplicationService}</li>
 *   <li>{@code adapter.out.persistence.@Repository} → {@code *RepositoryAdapter}</li>
 *   <li>{@code @jakarta.persistence.Entity} → {@code *JpaEntity}</li>
 *   <li>{@code domain.port.in} records ending {@code Command} or
 *       {@code Query} must be records</li>
 * </ul>
 */
@AnalyzeClasses(
        packages = "ch.owt.boatapp",
        importOptions = ImportOption.DoNotIncludeTests.class)
class NamingConventionTest {

    @ArchTest
    static final ArchRule controllers_suffixed_Controller =
            classes().that().areAnnotatedWith(RestController.class)
                    .should().haveSimpleNameEndingWith("Controller");

    @ArchTest
    static final ArchRule domain_services_suffixed_DomainService =
            classes().that().resideInAPackage("ch.owt.boatapp.domain.service")
                    .and().areNotInterfaces()
                    .should().haveSimpleNameEndingWith("DomainService")
                    .as("Concrete classes directly in domain.service must end with DomainService " +
                        "(validators belong in the domain.service.validation sub-package)");

    @ArchTest
    static final ArchRule application_services_suffixed_ApplicationService =
            classes().that().areAnnotatedWith(Service.class)
                    .and().resideInAPackage("..infrastructure.service..")
                    .should().haveSimpleNameEndingWith("ApplicationService");

    @ArchTest
    static final ArchRule repository_adapters_suffixed_RepositoryAdapter =
            classes().that().areAnnotatedWith(Repository.class)
                    .should().haveSimpleNameEndingWith("RepositoryAdapter");

    @ArchTest
    static final ArchRule jpa_entities_suffixed_JpaEntity =
            classes().that().areAnnotatedWith(jakarta.persistence.Entity.class)
                    .should().haveSimpleNameEndingWith("JpaEntity");

    @ArchTest
    static final ArchRule commands_in_port_in_are_records =
            classes().that().resideInAPackage("..domain.port.in..")
                    .and().haveSimpleNameEndingWith("Command")
                    .should().beRecords()
                    .as("Command classes in domain.port.in must be records (immutable inputs)");

    @ArchTest
    static final ArchRule queries_in_port_in_are_records =
            classes().that().resideInAPackage("..domain.port.in..")
                    .and().haveSimpleNameEndingWith("Query")
                    .should().beRecords()
                    .as("Query classes in domain.port.in must be records (immutable inputs)");
}
