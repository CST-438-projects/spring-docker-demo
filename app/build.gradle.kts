plugins {
    java
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "edu.csumb.cst438"
version = "0.0.1-SNAPSHOT"

java {
    // Compile and run on Java 21 (LTS). If no JDK 21 is installed, the foojay
    // plugin in settings.gradle.kts downloads one on the first build.
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// A fixed jar name so the Dockerfile can COPY it without a glob or a version number.
tasks.bootJar {
    archiveFileName = "app.jar"
}
