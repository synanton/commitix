package io.synanton.commitix.jdbc;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * Verifies that commitix-jdbc has no Spring or serializer dependencies.
 */
class JdbcBoundaryTest {

    private static final JavaClasses JDBC_CLASSES = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("io.synanton.commitix.jdbc");

    @Test
    void shouldNotDependOnSpring() {
        noClasses()
            .should().dependOnClassesThat().resideInAPackage("org.springframework..")
            .check(JDBC_CLASSES);
    }

    @Test
    void shouldNotDependOnJackson() {
        noClasses()
            .should().dependOnClassesThat().resideInAPackage("com.fasterxml..")
            .check(JDBC_CLASSES);
    }
}
