package ch.owt.boatapp.bff.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;
import java.net.URL;
import java.util.Enumeration;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

/**
 * Architecture invariants for the BFF.
 *
 * <p>Since the SCG migration the BFF is a Spring Cloud Gateway: routes are
 * declarative (in {@code application-routes.yml}) and the only Java code is
 * the OAuth2 / session / CSRF / JWKS plumbing plus the BFF-local endpoints
 * ({@code /api/me}, {@code /api/logout}, {@code /.well-known/jwks.json}).
 * These rules:
 *
 * <ul>
 *   <li>Forbid JPA and {@code @Transactional} (the BFF still has no domain
 *       layer of its own — Spring Session JDBC is configuration only).</li>
 *   <li>Forbid the re-introduction of an outbound HTTP client into the BFF
 *       Java code — proxying must stay in SCG configuration. The deleted
 *       {@code adapter.out.client.generated} package must NOT come back.</li>
 *   <li>Require {@code application-routes.yml} to be on the classpath, so a
 *       refactor that accidentally drops it is caught at build time, not at
 *       first request.</li>
 *   <li>Require {@link RestControllerAdvice} to declare an SLF4J logger that
 *       every {@link ExceptionHandler} actually uses.</li>
 * </ul>
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
     * The deleted outbound HTTP-Interface client must not come back. Proxying
     * to the Business Service is owned by Spring Cloud Gateway's TokenRelay
     * filter (declared in {@code application-routes.yml}), not by Java code.
     */
    @ArchTest
    static final ArchRule no_outbound_client_package =
            noClasses().that().resideInAPackage("ch.owt.boatapp.bff..")
                    .should().resideInAPackage("..bff.adapter.out.client..")
                    .as("BFF must not (re-)introduce an outbound HTTP client — proxying lives in SCG config");

    /**
     * Forbid the BFF code from constructing a {@link org.springframework.web.client.RestClient}
     * or a {@link org.springframework.web.client.RestTemplate} — those are
     * the historical paths to a hand-rolled outbound client. The Keycloak
     * server-side logout uses {@code RestClient} as well; that single
     * legitimate use is exempted by name. Any new dependency on these classes
     * must be discussed.
     */
    @ArchTest
    static final ArchRule no_resttemplate_in_bff =
            noClasses().that().resideInAPackage("ch.owt.boatapp.bff..")
                    .and().haveSimpleNameNotEndingWith("KeycloakServerSideLogoutHandler")
                    .should().dependOnClassesThat().haveFullyQualifiedName("org.springframework.web.client.RestTemplate")
                    .as("RestTemplate must not appear in the BFF — proxying to BS is owned by SCG config");

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
     * The SCG route table is the source of truth for all proxying. If
     * {@code application-routes.yml} is accidentally deleted or moved during
     * a refactor, the BFF would silently stop forwarding {@code /api/v1/boats/**}
     * to the upstream Business Service. Anchor a build-time check here.
     */
    @ArchTest
    static final ArchRule scg_route_table_must_exist =
            classes().should(routeYamlOnClasspath())
                    .because("application-routes.yml must be on the classpath — it is the SCG route table");

    private static ArchCondition<com.tngtech.archunit.core.domain.JavaClass> routeYamlOnClasspath() {
        return new ArchCondition<>("have application-routes.yml on the classpath") {
            private boolean checked;
            private boolean present;

            @Override
            public void check(com.tngtech.archunit.core.domain.JavaClass item, ConditionEvents events) {
                if (!checked) {
                    checked = true;
                    try {
                        Enumeration<URL> resources = getClass().getClassLoader()
                                .getResources("application-routes.yml");
                        present = resources.hasMoreElements();
                    } catch (IOException ex) {
                        present = false;
                    }
                    if (!present) {
                        events.add(SimpleConditionEvent.violated(item,
                                "application-routes.yml not found on the classpath"));
                    }
                }
            }
        };
    }
}
