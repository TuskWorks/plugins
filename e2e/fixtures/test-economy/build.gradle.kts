// Test-only stand-in for Vault + an economy plugin, used by e2e scenarios.
// Never published: it answers to the name "Vault" so real plugins' depend: [Vault] resolves.
plugins {
    java
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io") {
        content { includeGroup("com.github.MilkBowl") }
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    implementation("com.github.MilkBowl:VaultAPI:1.7.1") { isTransitive = false }
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

tasks {
    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release = 21
    }

    jar {
        archiveBaseName = "TestEconomy"
        // Bundle the Vault API classes, as the real Vault plugin does
        from(configurations.runtimeClasspath.map { cp -> cp.map { zipTree(it) } }) {
            exclude("META-INF/**", "plugin.yml")
        }
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
}
