plugins {
    `java-library`
    `java-test-fixtures`
}
dependencies {
    api(libs.jdbi3)
    implementation(libs.snakeyaml)
    implementation(libs.sqlite.jdbc)
    implementation(libs.jackson)
    testFixturesApi(libs.jgit)
    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.wiremock)
}
