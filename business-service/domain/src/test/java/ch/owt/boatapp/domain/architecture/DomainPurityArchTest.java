package ch.owt.boatapp.domain.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * In-module purity guard for the {@code domain} layer.
 *
 * <p>The Maven dependency graph already physically prevents Spring / Jakarta /
 * Lombok / SLF4J from reaching the domain classpath — the {@code domain}
 * module declares zero such dependencies, so {@code import org.springframework.*}
 * inside this module would not even compile. These rules nonetheless run
 * here because:
 *
 * <ul>
 *   <li>they document the intent at the layer that owns it;</li>
 *   <li>they fail fast during {@code ./mvnw -pl domain test} without
 *       compiling or loading the rest of the reactor;</li>
 *   <li>they survive a future graph reshuffle (e.g. someone adding a
 *       {@code spring-context} dep "for one tiny annotation").</li>
 * </ul>
 *
 * <p>The cross-cutting {@code BusinessServiceArchitectureTest} in the
 * bootstrap module re-runs an equivalent rule over the whole classpath. If
 * either rule changes, both should change in lockstep.
 */
@AnalyzeClasses(
        packages = "ch.owt.boatapp.domain",
        importOptions = ImportOption.DoNotIncludeTests.class)
class DomainPurityArchTest {

    /**
     * Domain MUST NOT depend on Spring. The Maven graph would already break
     * the build, but this rule names the boundary explicitly.
     */
    @ArchTest
    static final ArchRule domain_must_not_depend_on_spring =
            noClasses().that().resideInAPackage("ch.owt.boatapp.domain..")
                    .should().dependOnClassesThat().resideInAPackage("org.springframework..")
                    .as("Domain must not depend on Spring — it is pure Java");

    /** Domain MUST NOT depend on Jakarta (validation, persistence, anything). */
    @ArchTest
    static final ArchRule domain_must_not_depend_on_jakarta =
            noClasses().that().resideInAPackage("ch.owt.boatapp.domain..")
                    .should().dependOnClassesThat().resideInAPackage("jakarta..")
                    .as("Domain must not depend on Jakarta — invariants live in compact constructors / validators");

    /**
     * Domain MUST NOT log via SLF4J. Logging is an adapter concern: the domain
     * either succeeds or throws, and the adapter decides what to record.
     */
    @ArchTest
    static final ArchRule domain_must_not_depend_on_slf4j =
            noClasses().that().resideInAPackage("ch.owt.boatapp.domain..")
                    .should().dependOnClassesThat().resideInAPackage("org.slf4j..")
                    .as("Domain must not log — logging is an adapter concern");

    /** Domain MUST NOT depend on Lombok. Records + plain Java only. */
    @ArchTest
    static final ArchRule domain_must_not_depend_on_lombok =
            noClasses().that().resideInAPackage("ch.owt.boatapp.domain..")
                    .should().dependOnClassesThat().resideInAPackage("lombok..")
                    .as("Domain must not depend on Lombok — records + plain Java only");
}
