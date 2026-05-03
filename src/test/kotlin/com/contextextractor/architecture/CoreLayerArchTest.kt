package com.contextextractor.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import org.junit.jupiter.api.Test

class CoreLayerArchTest {

    private val importedClasses = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("com.contextextractor")

    // Invariant n°1 d'ARCHITECTURE.md : core/ ne dépend jamais de PSI/IntelliJ.
    @Test
    fun `core has no IntelliJ dependencies`() {
        classes()
            .that().resideInAPackage("..core..")
            .should().onlyDependOnClassesThat()
            .resideOutsideOfPackages("com.intellij..")
            .check(importedClasses)
    }
}
