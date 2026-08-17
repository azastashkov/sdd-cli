plugins {
    `java-library`
}

dependencies {
    api(project(":sdd-core"))
    implementation(libs.snakeyaml)
    implementation(libs.jackson)
    implementation(libs.jsoup)
    implementation(libs.jgit)
    testImplementation(libs.bundles.test)
    testImplementation(libs.wiremock)
    testImplementation(testFixtures(project(":sdd-core")))
    testRuntimeOnly(libs.junit.launcher)
}
