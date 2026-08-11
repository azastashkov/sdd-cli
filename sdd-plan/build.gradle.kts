plugins {
    `java-library`
}

dependencies {
    api(project(":sdd-core"))
    implementation(libs.snakeyaml)
    implementation(libs.jackson)
    implementation(libs.jsoup)
    testImplementation(libs.bundles.test)
    testImplementation(testFixtures(project(":sdd-core")))
    testRuntimeOnly(libs.junit.launcher)
}
