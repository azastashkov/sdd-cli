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
    // sdd-index's SourceParser.parseModule is overloaded on ParserConfiguration (a javaparser
    // type sdd-index keeps `implementation`-scoped, not exported). ContractActualizer never
    // names that type itself, but javac must resolve every same-arity overload to pick the
    // right one, so the class needs to be visible at compile time even though it is unused
    // at runtime.
    compileOnly(libs.javaparser.symbol.solver)
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
