plugins {
    // Paper 26.x requires Java 25; lets Gradle provision the JDK for test servers
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "tuskworks-plugins"

include("tuskcrates")
