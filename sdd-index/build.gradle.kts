plugins { `java-library` }
dependencies {
    api(project(":sdd-core"))
    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.launcher)
}
