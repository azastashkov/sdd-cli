plugins {
    `java-library`
    `java-test-fixtures`
}
dependencies {
    api(project(":sdd-core"))
    implementation(libs.gradle.tooling)
    implementation(libs.tomlj)
    implementation(libs.jgit)
    implementation(libs.jackson)
    runtimeOnly("org.slf4j:slf4j-nop:2.0.13")
    testFixturesApi(testFixtures(project(":sdd-core")))
    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.launcher)
}
