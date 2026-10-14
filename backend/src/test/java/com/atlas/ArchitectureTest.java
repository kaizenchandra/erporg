package com.atlas;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.*;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

class ArchitectureTest {
  static final ArchRule DOMAIN =
      noClasses()
          .that()
          .resideInAPackage("..domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework..",
              "jakarta..",
              "java.sql..",
              "..application..",
              "..adapter..",
              "..configuration..");

  static final ArchRule ADAPTER_POLICY =
      noClasses()
          .that()
          .resideInAPackage("..adapter.out..")
          .should()
          .callMethod(com.atlas.modules.corehr.domain.model.Actor.class, "role")
          .orShould()
          .callMethod(com.atlas.modules.corehr.domain.model.Actor.class, "hr")
          .orShould()
          .callMethod(
              com.atlas.modules.corehr.domain.model.Actor.class,
              "canRead",
              com.atlas.modules.corehr.domain.model.Employee.class)
          .orShould()
          .callMethod(
              com.atlas.modules.corehr.domain.model.Actor.class,
              "canChange",
              com.atlas.modules.corehr.domain.model.Employee.class,
              String.class);

  @Test
  void cleanBoundaries() {
    var classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.atlas");
    DOMAIN.check(classes);
    ADAPTER_POLICY.check(classes);
    noClasses()
        .that()
        .resideInAPackage("..application..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.springframework..", "jakarta..", "java.sql..", "..adapter..", "..configuration..")
        .check(classes);
    noClasses()
        .that()
        .resideInAPackage("..adapter.in..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "..adapter.out..", "..application.usecase..", "org.springframework.jdbc..")
        .check(classes);
  }

  @Test
  void persistenceCannotDecideRolePolicy() {
    var invalid =
        new ClassFileImporter().importClasses(com.atlas.archfixture.adapter.out.BadPolicy.class);
    assertThrows(AssertionError.class, () -> ADAPTER_POLICY.check(invalid));
  }

  @Test
  void deliberatelyForbiddenDependencyFails() {
    var invalid =
        new ClassFileImporter().importClasses(com.atlas.archfixture.domain.BadDomain.class);
    assertThrows(AssertionError.class, () -> DOMAIN.check(invalid));
  }
}
