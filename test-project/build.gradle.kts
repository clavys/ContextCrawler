plugins {
    id("java")
}

group = "com.testproject"
version = "1.0.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework:spring-context:6.1.6")
    implementation("org.springframework.data:spring-data-commons:3.3.0")
    implementation("jakarta.annotation:jakarta.annotation-api:2.1.1")
    implementation("jakarta.persistence:jakarta.persistence-api:3.1.0")

    compileOnly("org.projectlombok:lombok:1.18.32")
    annotationProcessor("org.projectlombok:lombok:1.18.32")
}
