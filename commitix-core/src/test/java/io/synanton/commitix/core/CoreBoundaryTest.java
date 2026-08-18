package io.synanton.commitix.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * Verifies that commitix-core has zero infrastructure dependencies.
 * Cloaking: the business layer must not know what is underneath the adapter boundary.
 */
class CoreBoundaryTest {

    private static final JavaClasses CORE_CLASSES = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("io.synanton.commitix.core");

    @Test
    void shouldNotDependOnJdbc() {
        noClasses()
            .should().dependOnClassesThat().resideInAnyPackage("java.sql..", "javax.sql..")
            .check(CORE_CLASSES);
    }

    @Test
    void shouldNotDependOnSpring() {
        noClasses()
            .should().dependOnClassesThat().resideInAPackage("org.springframework..")
            .check(CORE_CLASSES);
    }

    @Test
    void shouldNotDependOnJackson() {
        noClasses()
            .should().dependOnClassesThat().resideInAPackage("com.fasterxml..")
            .check(CORE_CLASSES);
    }

    @Test
    void shouldNotDependOnFlyway() {
        noClasses()
            .should().dependOnClassesThat().resideInAPackage("org.flywaydb..")
            .check(CORE_CLASSES);
    }

    @Test
    void shouldNotDependOnHikari() {
        noClasses()
            .should().dependOnClassesThat().resideInAPackage("com.zaxxer..")
            .check(CORE_CLASSES);
    }

    @Test
    void shouldNotHaveDomainDependingOnAdapters() {
        noClasses()
            .that().resideInAPackage("io.synanton.commitix.core.domain..")
            .should().dependOnClassesThat().resideInAPackage("io.synanton.commitix.core.internal..")
            .check(CORE_CLASSES);
    }
}
