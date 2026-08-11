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
    implementation(libs.javaparser.symbol.solver)
    runtimeOnly(libs.slf4j.nop)
    testFixturesApi(testFixtures(project(":sdd-core")))
    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.launcher)
}
