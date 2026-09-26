// Shared conventions live in each module; the root project only groups them.

plugins {
    // Declared once here so all modules share one classloader for its build service
    id("io.papermc.hangar-publish-plugin") version "0.1.4" apply false
}
