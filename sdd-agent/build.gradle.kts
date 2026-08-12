plugins { `java-library` }
dependencies {
    api(project(":sdd-core"))
    implementation(libs.jackson)
    implementation(libs.javaparser.symbol.solver)
    testImplementation(libs.bundles.test)
    testImplementation(testFixtures(project(":sdd-core")))
    testRuntimeOnly(libs.junit.launcher)
}
