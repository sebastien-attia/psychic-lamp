package ch.owt.boatapp.application.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * In-module architecture rules for the {@code application} layer.
 *
 * <p>The application layer owns use-case orchestration as pure Java. The
 * Maven module declares only {@code business-service-domain} and
 * {@code slf4j-api} on its classpath — no Spring, no Jakarta. The
 * {@code @Transactional} boundary lives in the
 * {@code adapter.in.web.BoatTransactionalGateway} so the {@code application}
 * module never needs {@code spring-tx}/{@code spring-context}.
 *
 * <ul>
 *   <li>{@code application..} (whole module) is framework-agnostic — no
 *       Spring, no Jakarta;</li>
 *   <li>outbound ports are interfaces;</li>
 *   <li>inbound port types are interfaces (use cases) or records
 *       (Command/Query carriers).</li>
 * </ul>
 *
 * <p>The cross-cutting {@code BusinessServiceArchitectureTest} in the
 * bootstrap module re-runs equivalent rules over the whole classpath.
 */
@AnalyzeClasses(
        packages = "ch.owt.boatapp.application",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ApplicationLayerArchTest {

    /**
     * The whole {@code application} module stays framework-agnostic — no
     * Spring, no Jakarta. Defense-in-depth on top of Maven: the module's
     * compile classpath has neither dependency, so the import would not
     * compile, but this rule documents the intent. Test-scope classes are
     * deliberately excluded ({@link ImportOption.DoNotIncludeTests}) — test
     * classes may legitimately depend on ArchUnit and JUnit, neither of
     * which is allowed in production.
     */
    @ArchTest
    static final ArchRule application_must_not_depend_on_spring_or_jakarta =
            noClasses().that().resideInAPackage("ch.owt.boatapp.application..")
                    .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta..")
                    .as("application module is pure Java; transaction boundaries live in adapter.in.web");

    /** Outbound ports are pure interfaces. */
    @ArchTest
    static final ArchRule outbound_ports_are_interfaces =
            classes().that().resideInAPackage("ch.owt.boatapp.application.port.out..")
                    .should().beInterfaces()
                    .as("application.port.out.* contains only repository-port interfaces");

    /** Inbound ports are use-case interfaces or Command/Query records. */
    @ArchTest
    static final ArchRule inbound_ports_are_interfaces_or_records =
            classes().that().resideInAPackage("ch.owt.boatapp.application.port.in..")
                    .should().beInterfaces().orShould().beRecords()
                    .as("application.port.in.* contains only use-case interfaces and Command/Query records");
}
