plugins {
    application
}
dependencies {
    implementation(project(":sdd-core"))
    implementation(libs.picocli)
    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.wiremock)
    testImplementation(testFixtures(project(":sdd-core")))
}
application { mainClass.set("sdd.cli.SddCli") }
