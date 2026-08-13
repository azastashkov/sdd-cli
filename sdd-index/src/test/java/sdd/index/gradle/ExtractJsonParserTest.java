package sdd.index.gradle;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractJsonParserTest {
    private static final String PROJECTS_JSON = """
            {"projects":[{
              "path":":","name":"lib-a","group":"com.acme","version":"1.2.0",
              "projectDir":"/tmp/lib-a",
              "plugins":["java-library","maven-publish"],
              "hasBootJarTask":false,
              "publications":[{"groupId":"com.acme","artifactId":"lib-a"}],
              "configurations":{"compileClasspath":{
                "declared":[{"group":"com.acme","name":"lib-core","version":"2.0.0"},
                            {"group":"org.apache.commons","name":"commons-lang3","version":null}],
                "resolved":[{"group":"org.apache.commons","name":"commons-lang3","version":"3.14.0",
                             "files":["/cache/commons-lang3-3.14.0.jar"]}],
                "unresolved":["com.acme:lib-core:2.0.0"]}}}]}
            """;
    private static final String SETTINGS_JSON = """
            {"includedBuilds":["/tmp/lib-core"]}
            """;

    @Test
    void parsesProjectsAndSettings() {
        GradleModel.Extract e = ExtractJsonParser.parse(PROJECTS_JSON, SETTINGS_JSON);
        assertThat(e.includedBuilds()).containsExactly(Path.of("/tmp/lib-core"));
        GradleModel.Project p = e.projects().get(0);
        assertThat(p.name()).isEqualTo("lib-a");
        assertThat(p.plugins()).contains("maven-publish");
        assertThat(p.publications().get(0).artifactId()).isEqualTo("lib-a");
        GradleModel.DepConfig cc = p.configurations().get("compileClasspath");
        assertThat(cc.declared()).hasSize(2);
        assertThat(cc.declared().get(1).version()).isNull();
        assertThat(cc.resolved().get(0).version()).isEqualTo("3.14.0");
        assertThat(cc.unresolved()).containsExactly("com.acme:lib-core:2.0.0");
    }

    @Test
    void nullSettingsMeansNoIncludedBuilds() {
        assertThat(ExtractJsonParser.parse(PROJECTS_JSON, null).includedBuilds()).isEmpty();
    }

    // sdd-init.gradle now also emits testCompileClasspath/testRuntimeClasspath entries with
    // declared deps only (empty resolved/unresolved, since test classpaths are never lenient-
    // resolved). The parser has no hardcoded config names — it iterates whatever keys are present
    // in the "configurations" object — so a test-scope entry parses the same way a compile-scope
    // one does; this pins that generic behavior against the new shape.
    private static final String PROJECTS_JSON_WITH_TEST_SCOPE = """
            {"projects":[{
              "path":":","name":"product-b","group":"com.trading","version":"1.0.0",
              "projectDir":"/tmp/product-b",
              "plugins":["java"],
              "hasBootJarTask":false,
              "publications":[],
              "configurations":{
                "compileClasspath":{"declared":[],"resolved":[],"unresolved":[]},
                "testCompileClasspath":{
                  "declared":[{"group":"com.trading","name":"mock-pricing-venue","version":"1.0.0"}],
                  "resolved":[], "unresolved":[]}}}]}
            """;

    @Test
    void parsesTestScopeConfigurationWithDeclaredDepsAndEmptyResolvedUnresolved() {
        GradleModel.Extract e = ExtractJsonParser.parse(PROJECTS_JSON_WITH_TEST_SCOPE, null);
        GradleModel.DepConfig tc = e.projects().get(0).configurations().get("testCompileClasspath");
        assertThat(tc.declared()).extracting(GradleModel.DeclaredDep::name).containsExactly("mock-pricing-venue");
        assertThat(tc.resolved()).isEmpty();
        assertThat(tc.unresolved()).isEmpty();
    }
}
