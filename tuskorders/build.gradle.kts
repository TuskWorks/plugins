plugins {
    java
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

group = "io.github.tuskworks"
version = "0.1.0"
description = "SMP-style buy orders: players post what they want, others deliver and get paid instantly"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io") {
        content { includeGroup("com.github.MilkBowl") }
    }
}

// Compile against the oldest supported API so nothing newer than 1.21.4 sneaks in;
// 26.x compatibility is covered by running real servers.
val paperApi = "io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT"

dependencies {
    compileOnly(paperApi)
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1") { isTransitive = false }

    testImplementation(paperApi)
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

tasks {
    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release = 21
        options.compilerArgs.add("-Xlint:all,-processing,-serial")
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
        archiveBaseName = "TuskOrders"
        // Only API calls, no server internals: tells Paper it can skip remapping the jar.
        manifest.attributes("paperweight-mappings-namespace" to "mojang")
    }

    // ./gradlew :tuskorders:runServer -PmcVersion=26.3
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
