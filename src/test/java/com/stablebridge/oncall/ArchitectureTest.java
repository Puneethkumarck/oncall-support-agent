package com.stablebridge.oncall;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureTest {

    private static JavaClasses importedClasses;

    @BeforeAll
    static void setup() {
        importedClasses = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.stablebridge.oncall");
    }

    @Test
    @DisplayName("Domain must not depend on infrastructure")
    void domainMustNotDependOnInfrastructure() {
        noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure..")
            .check(importedClasses);
    }

    @Test
    @DisplayName("Domain must not depend on agent layer")
    void domainMustNotDependOnAgent() {
        noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAPackage("..agent..")
            .check(importedClasses);
    }

    @Test
    @DisplayName("Domain must not depend on application layer")
    void domainMustNotDependOnApplication() {
        noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAPackage("..application..")
            .check(importedClasses);
    }

    @Test
    @DisplayName("Domain must not depend on shell layer")
    void domainMustNotDependOnShell() {
        noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAPackage("..shell..")
            .check(importedClasses);
    }

    @Test
    @DisplayName("Domain must not use Spring annotations")
    void domainMustNotUseSpringAnnotations() {
        noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework..")
            .check(importedClasses);
    }

    @Test
    @DisplayName("Infrastructure must not depend on agent layer")
    void infrastructureMustNotDependOnAgent() {
        noClasses()
            .that().resideInAPackage("..infrastructure..")
            .should().dependOnClassesThat().resideInAPackage("..agent..")
            .allowEmptyShould(true)
            .check(importedClasses);
    }

    @Test
    @DisplayName("Domain model classes must be records, enums, or exceptions")
    void domainModelClassesMustBeRecordsEnumsOrExceptions() {
        classes()
            .that().resideInAPackage("..domain.model..")
            .should().beRecords()
            .orShould().beEnums()
            .orShould().beAssignableTo(RuntimeException.class)
            .check(importedClasses);
    }
}
