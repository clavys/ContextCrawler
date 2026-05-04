package com.contextextractor.adapters.psi

import junit.framework.TestCase

// Tests d'intégration PSI — état Stop B documenté de l'étape 3.
//
// LightJavaCodeInsightFixtureTestCase n'est pas accessible depuis ce source set
// custom : `intellijIdea(...)` injecte les JARs de l'IDE (dont testFramework.jar
// qui porte cette classe) via des transforms d'artefact attachées au source
// set `test` standard, et ces transforms ne se propagent pas via le simple
// `compileClasspath += sourceSets["test"].compileClasspath`.
//
// Diagnostic confirmé en inspectant le compileClasspath effectif :
//   integrationTestCompileClasspath contient bien :
//     - test-framework, test-framework-common, test-framework-core (Maven)
//     - idea_rt.jar (transformé pour ce source set)
//   mais PAS testFramework.jar du bundle IDE (où vit LightJavaCodeInsightFixtureTestCase).
//
// La résolution propre requiert une option du plugin v2 qui ne semble pas
// publique en API stable (pas de `configurationName` sur intellijIdea() /
// bundledPlugin()). Ce câblage est reporté à une session dédiée.
//
// En attendant : la validation de JavaPsiIntrospector se fait manuellement via
// `./gradlew runIde` (ouvrir test-project/ et déclencher l'action plugin —
// workflow décrit dans test-project/README.md). Les 4 tests prêts sont en
// pseudo-code dans le commentaire ci-dessous, à transposer une fois le wiring
// résolu.
//
// 1. resolveClass("com.demo.OrderService") → ClassDescriptor avec @Service
// 2. listFields(orderService) → ClassField(name="repository", type=...,
//    visibility="private", declaredIn="com.demo.OrderService")
// 3. listSuperClasses(orderService) → [AbstractCacheService] (sans Object)
// 4. listAnnotations(OnClass("com.demo.OrderService")) → [@Service fqn]
class JavaPsiIntrospectorTest : TestCase() {

    fun `test PsiTypeMapper class is on the integration test classpath`() {
        // Sanity check : le classpath de compilation atteint bien les classes
        // de l'adapter PSI. C'est le maximum testable hors sandbox IntelliJ
        // pour ce source set tant que le wiring testFramework.jar n'est pas fait.
        assertNotNull("PsiTypeMapper must be reachable", PsiTypeMapper)
    }
}
