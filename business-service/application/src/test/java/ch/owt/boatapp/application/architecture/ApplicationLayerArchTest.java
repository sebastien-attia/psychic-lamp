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
 * <p>The application layer owns use-case orchestration. It depends on the
 * domain jar plus a narrow Spring slice ({@code spring-context},
 * {@code spring-tx}); web and JPA starters are deliberately absent in the
 * Maven graph. These rules pin the rules ArchUnit can express on top of that:
 *
 * <ul>
 *   <li>{@code application.port..} stays pure — no Spring, no Jakarta;</li>
 *   <li>outbound ports are interfaces;</li>
 *   <li>inbound port types are interfaces (use cases) or records
 *       (Command/Query carriers);</li>
 *   <li>Spring annotations stay confined to {@code application.service..}.</li>
 * </ul>
 *
 * <p>The cross-cutting {@code BusinessServiceArchitectureTest} in the
 * bootstrap module re-runs equivalent rules over the whole classpath.
 */
@AnalyzeClasses(
        packages = "ch.owt.boatapp.application",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ApplicationLayerArchTest {

    /** Ports stay framework-agnostic — Spring imports are only allowed in the service package. */
    @ArchTest
    static final ArchRule ports_must_not_depend_on_spring =
            noClasses().that().resideInAPackage("ch.owt.boatapp.application.port..")
                    .should().dependOnClassesThat().resideInAPackage("org.springframework..")
                    .as("application.port.* must remain framework-agnostic");

    /** Ports stay framework-agnostic — no Jakarta either (validation, persistence, anything). */
    @ArchTest
    static final ArchRule ports_must_not_depend_on_jakarta =
            noClasses().that().resideInAPackage("ch.owt.boatapp.application.port..")
                    .should().dependOnClassesThat().resideInAPackage("jakarta..")
                    .as("application.port.* must remain framework-agnostic");

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
