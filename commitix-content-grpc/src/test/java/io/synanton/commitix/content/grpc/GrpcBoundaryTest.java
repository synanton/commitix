package io.synanton.commitix.content.grpc;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * Verifies that commitix-content-grpc stays a thin gRPC client: io.grpc and
 * protobuf are the only infrastructure allowed. No Spring, Jackson, S3, or JDBC
 * may leak into the adapter boundary.
 */
class GrpcBoundaryTest {

    private static final JavaClasses GRPC_CLASSES = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("io.synanton.commitix.content.grpc");

    @Test
    void shouldNotDependOnSpring() {
        noClasses()
            .should().dependOnClassesThat().resideInAPackage("org.springframework..")
            .check(GRPC_CLASSES);
    }

    @Test
    void shouldNotDependOnJackson() {
        noClasses()
            .should().dependOnClassesThat().resideInAPackage("com.fasterxml..")
            .check(GRPC_CLASSES);
    }

    @Test
    void shouldNotDependOnS3() {
        noClasses()
            .should().dependOnClassesThat().resideInAnyPackage("software.amazon..", "com.amazonaws..")
            .check(GRPC_CLASSES);
    }

    @Test
    void shouldNotDependOnJdbc() {
        noClasses()
            .should().dependOnClassesThat().resideInAnyPackage("java.sql..", "javax.sql..")
            .check(GRPC_CLASSES);
    }
}
