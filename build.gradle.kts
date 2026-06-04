import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    alias(libs.plugins.kotlin)
    alias(libs.plugins.intellijPlatform)
}

group = "com.contextcrawler"
version = "1.1.7"

// Set the JVM language level used to build the project.
kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// -- Source set d'intégration PSI -----------------------------------------------
// On sépare les tests d'intégration sandbox (JUnit 4 + LightJavaCodeInsightFixtureTestCase)
// des tests unitaires Kotlin pur (JUnit 5). Raison : `testFramework(Platform)`
// embarque un LauncherSessionListener JUnit 5 incompatible avec un classpath
// JUnit 5 standard — voir étape 1 où il faisait planter `./gradlew test`.
//
// La task integrationTest utilisera useJUnit() (runner JUnit 4) : le
// launcher JUnit 5 n'est jamais initialisé, donc le LauncherSessionListener
// IntelliJ ne pose pas problème, même s'il est sur le classpath.
val integrationTest: SourceSet by sourceSets.creating {
    // On hérite intégralement du classpath du source set `test` standard
    // (Kotlin stdlib, IntelliJ Platform, JUnit 5 — tout l'outillage compile).
    // Côté integrationTestImplementation, on n'ajoute que ce qui est SPÉCIFIQUE :
    // - testFramework(Platform) (LightJavaCodeInsightFixtureTestCase)
    // - JUnit 4 (IntelliJ TestCase est JUnit 4 / 3.8 compat)
    compileClasspath += sourceSets["test"].compileClasspath +
        sourceSets["main"].output + sourceSets["test"].output
    runtimeClasspath += sourceSets["test"].runtimeClasspath +
        sourceSets["main"].output + sourceSets["test"].output
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))

        // Add plugin dependencies for compilation here:
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")

        // Test framework — uniquement pour integrationTest, pour éviter de
        // polluer le classpath JUnit 5 du source set test standard.
        testFramework(TestFrameworkType.Platform, configurationName = "integrationTestImplementation")
    }

    // YAML parsing pour `.contextextractor.yml` (étape 6 / YamlProjectConfigSource).
    // Dépendance explicite plutôt que de compter sur celle embarquée par la plateforme
    // IntelliJ — la plateforme peut la repackager / la masquer suivant la version.
    implementation("org.yaml:snakeyaml:2.3")

    // Tests pur Kotlin (core/, fakes) — JUnit 5.
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Tests d'intégration PSI — JUnit 4 + IntelliJ TestCase.
    // Kotlin stdlib explicite : l'héritage par classpath += depuis test ne
    // suffit pas pour le runtime classpath (compileClasspath OK, pas runtime).
    "integrationTestImplementation"(kotlin("stdlib"))
    "integrationTestImplementation"("junit:junit:4.13.2")
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<Test>("integrationTest") {
    description = "Tests d'intégration PSI (LightJavaCodeInsightFixtureTestCase, JUnit 4)."
    group = "verification"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    useJUnit()
    shouldRunAfter(tasks.test)
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            // Pas de borne haute : sans cela l'IntelliJ Platform Gradle Plugin
            // pose `until-build=261.*` et le plugin refuserait de se charger sur
            // toute version d'IDE postérieure à 2026.1. API plate-forme utilisée
            // stable ⇒ ouverture de la borne haute assumée pour la V1.
            untilBuild = provider { null }
        }

        changeNotes = """
            Initial version
        """.trimIndent()
    }
}

tasks {
    wrapper {
        gradleVersion = providers.gradleProperty("gradleVersion").get()
    }
    buildSearchableOptions {
        enabled = false
    }
}

