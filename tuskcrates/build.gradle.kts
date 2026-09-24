plugins {
    java
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

group = "io.github.tuskworks"
version = "0.1.0"
description = "Modern crates plugin for Paper & Folia"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

// Compile against the 1.21.4 API so a single jar runs on 1.21.4 through 26.x.
// -PpaperApi=26.2.build.128-stable checks the sources against a newer API (needs Java 25).
val paperApi = providers.gradleProperty("paperApi").getOrElse("1.21.4-R0.1-SNAPSHOT")
val javaRelease = if (paperApi.startsWith("1.")) 21 else 25

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperApi")
    testImplementation("io.papermc.paper:paper-api:$paperApi")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(javaRelease)
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release = javaRelease
        options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:removal"))
    }

    processResources {
        val props = mapOf("version" to project.version, "description" to project.description)
        inputs.properties(props)
        filesMatching("plugin.yml") { expand(props) }
    }

    test {
        useJUnitPlatform()
    }

    jar {
        archiveBaseName = "TuskCrates"
        // Only API calls, no server internals: tells Paper it can skip remapping the jar.
        manifest.attributes("paperweight-mappings-namespace" to "mojang")
    }

    // ./gradlew :tuskcrates:runServer -PmcVersion=26.3
    runServer {
        val mc = providers.gradleProperty("mcVersion").getOrElse("1.21.11")
        minecraftVersion(mc)
        val javaMajor = if (mc.startsWith("1.")) 21 else 25
        javaLauncher = project.javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(javaMajor)
        }
        runDirectory = layout.projectDirectory.dir("run/$mc")
        jvmArgs("-Dcom.mojang.eula.agree=true")
    }
}
