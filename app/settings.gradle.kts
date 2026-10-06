// Lets Gradle download a matching JDK (here, 21) if the machine does not have one.
// This is the same plugin start.spring.io adds to every generated project.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "hello-api"
