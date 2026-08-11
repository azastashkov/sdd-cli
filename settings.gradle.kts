dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven { url = uri("https://repo.gradle.org/gradle/libs-releases") }
    }
}
rootProject.name = "sdd"
include("sdd-core", "sdd-index", "sdd-plan", "sdd-agent", "sdd-cli")
