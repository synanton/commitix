package io.synanton.commitix.content;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * Verifies that commitix-content stays a thin client: no Spring, Jackson, S3,
 * gRPC, or JDBC may leak into the adapter boundary. The HTTP client is
 * {@code java.net.http}; JSON handling is hand-rolled against the versioned
 * Content Service contract.
 */
class ContentBoundaryTest {

    private static final JavaClasses CONTENT_CLASSES = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("io.synanton.commitix.content");

    @Test
    void shouldNotDependOnSpring() {
        noClasses()
            .should().dependOnClassesThat().resideInAPackage("org.springframework..")
            .check(CONTENT_CLASSES);
    }

    @Test
    void shouldNotDependOnJackson() {
        noClasses()
            .should().dependOnClassesThat().resideInAPackage("com.fasterxml..")
            .check(CONTENT_CLASSES);
    }

    @Test
    void shouldNotDependOnS3() {
        noClasses()
            .should().dependOnClassesThat().resideInAnyPackage("software.amazon..", "com.amazonaws..")
            .check(CONTENT_CLASSES);
    }

    @Test
    void shouldNotDependOnGrpc() {
        noClasses()
            .should().dependOnClassesThat().resideInAnyPackage("io.grpc..", "com.google.protobuf..")
            .check(CONTENT_CLASSES);
    }

    @Test
    void shouldNotDependOnJdbc() {
        noClasses()
            .should().dependOnClassesThat().resideInAnyPackage("java.sql..", "javax.sql..")
            .check(CONTENT_CLASSES);
    }
}
