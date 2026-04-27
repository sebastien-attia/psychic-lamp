package ch.owt.boatapp.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.Entity;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * In-module architecture rules for the infrastructure layer adapters.
 *
 * <p>Pin the inbound / outbound boundaries the cross-cutting bootstrap rule
 * cannot cheaply express:
 *
 * <ul>
 *   <li>{@code adapter.in.web} must NOT depend on {@code adapter.out..} — the
 *       web adapter talks to the application port, never directly to the
 *       persistence adapter, so the two adapters are independently swappable;</li>
 *   <li>{@code adapter.out.persistence} must NOT depend on
 *       {@code adapter.in.web..} — the same isolation in the other direction;</li>
 *   <li>web controllers depend on the application's inbound port / service
 *       facade only — never on a concrete repository adapter or JPA entity;</li>
 *   <li>JPA entities (and {@code @Repository} classes) stay confined to
 *       {@code adapter.out.persistence..};</li>
 *   <li>{@code @RestController} stays confined to {@code adapter.in.web..}.</li>
 * </ul>
 *
 * <p>The cross-cutting {@code BusinessServiceArchitectureTest} in the
 * bootstrap module re-runs equivalent rules over the whole classpath; this
 * file provides fast in-module feedback on every {@code ./mvnw -pl infrastructure test}.
 */
@AnalyzeClasses(
        packages = "ch.owt.boatapp",
        importOptions = ImportOption.DoNotIncludeTests.class)
class InfrastructureLayerArchTest {

    /** Inbound web adapter must not reach into the outbound persistence adapter. */
    @ArchTest
    static final ArchRule inbound_web_must_not_depend_on_outbound_persistence =
            noClasses().that().resideInAPackage("ch.owt.boatapp.adapter.in.web..")
                    .should().dependOnClassesThat().resideInAPackage("ch.owt.boatapp.adapter.out..")
                    .as("adapter.in.web must talk to application ports — never directly to adapter.out.*");

    /** Outbound persistence adapter must not depend on the inbound web adapter. */
    @ArchTest
    static final ArchRule outbound_persistence_must_not_depend_on_inbound_web =
            noClasses().that().resideInAPackage("ch.owt.boatapp.adapter.out.persistence..")
                    .should().dependOnClassesThat().resideInAPackage("ch.owt.boatapp.adapter.in..")
                    .as("adapter.out.persistence must not depend on adapter.in.* — the layers are independently swappable");

    /** JPA entities live only in the persistence adapter. */
    @ArchTest
    static final ArchRule jpa_entities_only_in_persistence_entity_package =
            classes().that().areAnnotatedWith(Entity.class)
                    .should().resideInAPackage("ch.owt.boatapp.adapter.out.persistence.entity..")
                    .as("@Entity classes must live in adapter.out.persistence.entity..");

    /** {@code @RestController} only in the web adapter. */
    @ArchTest
    static final ArchRule rest_controllers_only_in_inbound_web =
            classes().that().areAnnotatedWith(RestController.class)
                    .should().resideInAPackage("ch.owt.boatapp.adapter.in.web..")
                    .as("@RestController classes must live in adapter.in.web..");

    /** Domain layer must not be polluted by JPA — defense-in-depth on top of the Maven graph. */
    @ArchTest
    static final ArchRule domain_must_not_depend_on_jakarta_persistence =
            noClasses().that().resideInAPackage("ch.owt.boatapp.domain..")
                    .should().dependOnClassesThat().resideInAPackage("jakarta.persistence..")
                    .as("Domain must not reference jakarta.persistence — JPA is an adapter concern");
}
