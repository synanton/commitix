package io.synanton.commitix.runtime;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * Verifies module-level dependency boundaries for {@code commitix-runtime}.
 *
 * <p>Rules enforced:
 * <ul>
 *   <li>Runtime classes must not depend on the demo module.
 *   <li>Domain classes (pure logic) must not depend on Spring.
 *   <li>Runtime classes must not depend on Jackson directly in domain/config
 *       (only the adapter layer may use it).
 *   <li>Runtime adapter/out must not depend on Spring MVC (web layer).
 * </ul>
 */
class RuntimeBoundaryTest {

    private static final JavaClasses RUNTIME_CLASSES = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("io.synanton.commitix.runtime");

    private static final JavaClasses RUNTIME_DOMAIN_CLASSES = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("io.synanton.commitix.runtime.domain");

    @Test
    void runtimeShouldNotDependOnDemoModule() {
        noClasses()
            .should().dependOnClassesThat().resideInAPackage("io.synanton.commitix.demo..")
            .check(RUNTIME_CLASSES);
    }

    @Test
    void domainShouldNotDependOnSpring() {
        noClasses()
            .should().dependOnClassesThat().resideInAPackage("org.springframework..")
            .check(RUNTIME_DOMAIN_CLASSES);
    }

    @Test
    void domainShouldNotDependOnJackson() {
        noClasses()
            .should().dependOnClassesThat().resideInAPackage("com.fasterxml..")
            .check(RUNTIME_DOMAIN_CLASSES);
    }

    @Test
    void runtimeShouldNotDependOnSpringMvc() {
        noClasses()
            .should().dependOnClassesThat().resideInAPackage("org.springframework.web..")
            .check(RUNTIME_CLASSES);
    }

    @Test
    void runtimeShouldNotDependOnJdbcAdaptersDirectly() {
        // Runtime wires jdbc adapters via DI; it must not hard-code JDBC SQL constants
        noClasses()
            .should().dependOnClassesThat().resideInAPackage("io.synanton.commitix.jdbc.adapter.out.sql..")
            .check(RUNTIME_CLASSES);
    }
}
