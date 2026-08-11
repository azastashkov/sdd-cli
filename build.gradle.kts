subprojects {
    apply(plugin = "java")
    the<JavaPluginExtension>().toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    tasks.withType<Test>().configureEach { useJUnitPlatform() }
}
