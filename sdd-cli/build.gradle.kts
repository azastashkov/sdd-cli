plugins {
    application
}
dependencies {
    implementation(project(":sdd-core"))
    implementation(project(":sdd-index"))
    implementation(project(":sdd-plan"))
    implementation(project(":sdd-agent"))
    implementation(libs.jgit)
    implementation(libs.jackson)
    implementation(libs.picocli)
    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.wiremock)
    testImplementation(testFixtures(project(":sdd-core")))
    testImplementation(testFixtures(project(":sdd-index")))
}
application {
    mainClass.set("sdd.cli.SddCli")
    applicationName = "sdd"
}
