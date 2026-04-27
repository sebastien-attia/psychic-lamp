package ch.owt.boatapp.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.Entity;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

/**
 * Architecture invariants for the Business Service.
 *
 * <p>The Maven multi-module layout (domain → application → infrastructure →
 * bootstrap jars) physically enforces several rules at compile time — most
 * notably "domain has no Spring/Jakarta imports" (the deps simply aren't on
 * domain's classpath). The rules below capture invariants that Maven cannot
 * enforce: annotation placement, naming conventions, logger discipline, and
 * the single-bootstrap-class invariant.
 *
 * <p>The {@link #domain_must_not_import_jakarta_validation} rule is also
 * grepped for verbatim by {@code ai-scripts/checks/02a5/run.sh} — its name
 * is part of the verification gate. Do not rename it.
 */
@AnalyzeClasses(
        packages = "ch.owt.boatapp",
        importOptions = ImportOption.DoNotIncludeTests.class)
class BusinessServiceArchitectureTest {

    /**
     * Jakarta Bean Validation is an adapter concern. Domain classes encode
     * invariants in compact constructors (e.g. {@code BoatId},
     * {@code UserId}) and in {@code SyntacticValidator} /
     * {@code SemanticValidator} — never via {@code @NotNull} / {@code @Size}.
     *
     * <p>Defense-in-depth on top of Maven: domain.jar has no Jakarta deps so
     * the import would not compile, but this rule documents the intent.
     */
    @ArchTest
    static final ArchRule domain_must_not_import_jakarta_validation =
            noClasses().that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAPackage("jakarta.validation..")
                    .as("Domain packages must not import jakarta.validation — Bean Validation is an adapter concern");

    /**
     * Inbound port package contains use-case interfaces and Command/Query
     * records — never concrete classes or enums.
     */
    @ArchTest
    static final ArchRule application_inbound_ports_are_interfaces_or_records =
            classes().that().resideInAPackage("..application.port.in..")
                    .should().beInterfaces().orShould().beRecords()
                    .as("application.port.in contains only interfaces (use cases) and records (Command/Query)");

    /** Outbound ports are pure interfaces. */
    @ArchTest
    static final ArchRule application_outbound_ports_are_interfaces =
            classes().that().resideInAPackage("..application.port.out..")
                    .should().beInterfaces()
                    .as("application.port.out contains only repository-port interfaces");

    /**
     * The Business Service is a stateless OAuth2 resource server. It must
     * never accidentally pull in the OAuth2 client SDK (used by the BFF).
     */
    @ArchTest
    static final ArchRule business_service_must_not_import_oauth2_client =
            noClasses().that().resideInAPackage("ch.owt.boatapp..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("org.springframework.security.oauth2.client..")
                    .as("Business Service uses oauth2-resource-server only — never oauth2-client");

    /** Transactional boundaries belong on application services, never on controllers. */
    @ArchTest
    static final ArchRule transactional_forbidden_on_controllers =
            noClasses().that().areAnnotatedWith(RestController.class)
                    .or().areAnnotatedWith(Controller.class)
                    .should().beAnnotatedWith(Transactional.class)
                    .because("@Transactional must never appear on @RestController/@Controller classes");

    /** Same rule at method scope, in case future code adds method-level @Transactional. */
    @ArchTest
    static final ArchRule transactional_forbidden_on_controller_methods =
            noMethods().that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
                    .should().beAnnotatedWith(Transactional.class)
                    .because("@Transactional must never appear on controller methods");

    /** Controllers in the web adapter follow the {@code *Controller} naming convention. */
    @ArchTest
    static final ArchRule controllers_suffixed_Controller =
            classes().that().areAnnotatedWith(RestController.class)
                    .should().haveSimpleNameEndingWith("Controller")
                    .as("@RestController classes must be suffixed Controller");

    /**
     * {@code @Service}-annotated beans live only in {@code adapter.in.web} —
     * the transactional gateway between HTTP and the use-case port. The
     * {@code application} module is pure Java with zero Spring deps, so its
     * use-case implementations ({@code BoatDomainService},
     * {@code UserDomainService}) are wired by {@code BeanConfig} and carry
     * no stereotype. Other adapters and infrastructure use
     * {@code @Repository}, {@code @Component} or {@code @Configuration}
     * instead.
     */
    @ArchTest
    static final ArchRule services_only_in_adapter_in_web =
            classes().that().areAnnotatedWith(Service.class)
                    .should().resideInAPackage("..adapter.in.web..")
                    .as("@Service classes must reside in ..adapter.in.web.. (transactional gateway)");

    /** Persistence adapters live only in {@code adapter.out.persistence}. */
    @ArchTest
    static final ArchRule repository_adapters_in_persistence_adapter =
            classes().that().areAnnotatedWith(Repository.class)
                    .should().resideInAPackage("..adapter.out.persistence..")
                    .as("@Repository classes must reside in ..adapter.out.persistence..");

    /** JPA entities live only in {@code adapter.out.persistence.entity}. */
    @ArchTest
    static final ArchRule jpa_entities_only_in_persistence_entity =
            classes().that().areAnnotatedWith(Entity.class)
                    .should().resideInAPackage("..adapter.out.persistence.entity..")
                    .as("@Entity classes must reside in ..adapter.out.persistence.entity..");

    /** Every {@code @RestControllerAdvice} declares an SLF4J Logger field. */
    @ArchTest
    static final ArchRule controller_advice_must_have_logger =
            classes().that().areAnnotatedWith(RestControllerAdvice.class)
                    .should(LoggerArchConditions.haveAFieldOfType(org.slf4j.Logger.class))
                    .because("@RestControllerAdvice must declare a private static final SLF4J Logger");

    /** Every {@code @ExceptionHandler} method emits at least one log call. */
    @ArchTest
    static final ArchRule exception_handlers_must_call_logger =
            methods().that().areAnnotatedWith(ExceptionHandler.class)
                    .should(LoggerArchConditions.callALoggerMethod())
                    .because("Every @ExceptionHandler must emit a log record");

    /**
     * Exactly one {@code @SpringBootApplication} class lives at the root of
     * {@code ch.owt.boatapp} — the bootstrap module. Multiple application
     * classes (or one in a sub-package) would silently change the
     * component-scan base and break the multi-module wiring.
     */
    @ArchTest
    static final ArchRule single_spring_boot_application_at_root =
            classes().that().areAnnotatedWith(SpringBootApplication.class)
                    .should().resideInAPackage("ch.owt.boatapp")
                    .as("@SpringBootApplication must live at the ch.owt.boatapp root (bootstrap module)");
}
