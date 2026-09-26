plugins {
    java
    id("io.papermc.hangar-publish-plugin")
}

group = "io.github.tuskworks"
version = "0.1.0"
description = "Lightweight clans & teams for SMP servers — chat, friendly fire, alliances, clan homes."

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/releases/") {
        content { includeGroup("me.clip") }
    }
    maven("https://jitpack.io") {
        content { includeGroup("com.github.MilkBowl") }
    }
}

// Compile against the oldest supported API so nothing newer than 1.21.4 sneaks in;
// 26.x compatibility is covered by the e2e suite.
// -PpaperApi=26.2.build.128-stable checks the sources against a newer API (needs Java 25).
val paperApiVersion = providers.gradleProperty("paperApi").getOrElse("1.21.4-R0.1-SNAPSHOT")
val paperApi = "io.papermc.paper:paper-api:$paperApiVersion"
val javaRelease = if (paperApiVersion.startsWith("1.")) 21 else 25

dependencies {
    compileOnly(paperApi)
    compileOnly("me.clip:placeholderapi:2.12.3")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1") { isTransitive = false }

    testImplementation(paperApi)
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(javaRelease)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = javaRelease
    options.compilerArgs.add("-Xlint:all,-processing,-serial")
}

tasks.processResources {
    val props = mapOf("version" to project.version, "description" to project.description)
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveBaseName = "TuskClans"
    // No NMS here, so tell Paper there is nothing to remap (faster first start on 1.21.x).
    manifest.attributes("paperweight-mappings-namespace" to "mojang")
    // GPL-3.0: every copy of the jar carries the license text
    from(rootProject.file("LICENSE"))
}

// Uploaded by .github/workflows/release.yml when a tuskclans-v<version> tag is pushed
hangarPublish {
    publications.register("plugin") {
        version = project.version as String
        id = "TuskClans"
        channel = "Release"
        changelog = providers.environmentVariable("HANGAR_CHANGELOG")
        apiKey = providers.environmentVariable("HANGAR_API_TOKEN")
        platforms {
            paper {
                jar = tasks.jar.flatMap { it.archiveFile }
                platformVersions = providers.gradleProperty("hangarPaperVersions").map { it.split(",") }
                dependencies {
                    hangar("PlaceholderAPI") { required = false }
                    url("Vault", "https://www.spigotmc.org/resources/vault.34315/") { required = false }
                }
            }
        }
    }
}
