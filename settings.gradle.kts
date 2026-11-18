plugins {
    // Lets Gradle download the JDK 21 toolchain if it isn't installed locally.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "dfs-hierarchy-filter"

include("hierarchy")
