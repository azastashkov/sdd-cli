plugins {
    `java-library`
    `java-test-fixtures`
}
dependencies {
    api(libs.jdbi3)
    implementation(libs.snakeyaml)
    implementation(libs.sqlite.jdbc)
    implementation(libs.jackson)
    // The TypeScript compiler, read off the classpath and materialized next to the sidecar script.
    // runtimeOnly because no Java code names a type from it — it is a resource, not a library.
    runtimeOnly(libs.typescript)
    // Read-only git history (sdd.core.git.GitRead). implementation, not api: GitRead returns
    // records of String/int only, so no consumer names a JGit type at compile time.
    implementation(libs.jgit)
    testFixturesApi(libs.jgit)
    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.wiremock)
}
