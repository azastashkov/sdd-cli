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
}
