package ch.owt.boatapp.bff.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

/**
 * Architecture invariants for the BFF.
 *
 * <p>The BFF is a thin proxy: no domain layer, no JPA, no transactions.
 * These rules pin the layering (controller → service → client),
 * forbid persistence and transaction management, and require the
 * {@link RestControllerAdvice} to declare an SLF4J logger that every
 * {@link ExceptionHandler} actually uses.
 */
@AnalyzeClasses(
        packages = "ch.owt.boatapp.bff",
        importOptions = ImportOption.DoNotIncludeTests.class)
class BffArchitectureTest {

    /** The BFF has no database of its own — JPA must never appear. */
    @ArchTest
    static final ArchRule no_jpa_in_bff =
            noClasses().that().resideInAPackage("ch.owt.boatapp.bff..")
                    .should().dependOnClassesThat().resideInAPackage("jakarta.persistence..")
                    .as("BFF must never import JPA — it has no database access of its own");

    /** Spring Session JDBC is configuration only; no application class is transactional. */
    @ArchTest
    static final ArchRule no_transactional_in_bff =
            noClasses().that().resideInAPackage("ch.owt.boatapp.bff..")
                    .should().beAnnotatedWith(Transactional.class)
                    .as("@Transactional is forbidden in BFF — BFF has no database transactions");

    /** Same rule at method level. */
    @ArchTest
    static final ArchRule no_transactional_methods_in_bff =
            noMethods().that().areDeclaredInClassesThat().resideInAPackage("ch.owt.boatapp.bff..")
                    .should().beAnnotatedWith(Transactional.class)
                    .as("@Transactional methods are forbidden in BFF");

    /**
     * The BusinessServiceClient is a Spring HTTP Interface — proxied at
     * runtime via {@code HttpServiceProxyFactory}. It must be an interface,
     * never a concrete class.
     */
    @ArchTest
    static final ArchRule business_service_client_must_be_interface =
            classes().that().resideInAPackage("..bff.adapter.out.client..")
                    .and().haveSimpleNameEndingWith("Client")
                    .should().beInterfaces()
                    .as("BusinessServiceClient must be a Spring HTTP Interface (interface, not class)");

    /**
     * Controllers depend on the orchestrator service, never on the outbound
     * client directly. Enforces the controller → service → client chain.
     */
    @ArchTest
    static final ArchRule bff_controllers_do_not_depend_on_outbound_client =
            noClasses().that().resideInAPackage("..bff.adapter.in.web..")
                    .should().dependOnClassesThat().resideInAPackage("..bff.adapter.out.client..")
                    .as("BFF controllers must go through infrastructure.service, not call adapter.out.client directly");

    /** BFF application services follow the {@code *BffService} naming. */
    @ArchTest
    static final ArchRule bff_services_suffixed_BffService =
            classes().that().resideInAPackage("..bff.infrastructure.service..")
                    .and().areAnnotatedWith(Service.class)
                    .should().haveSimpleNameEndingWith("BffService")
                    .as("BFF application services must be suffixed BffService");

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
}
