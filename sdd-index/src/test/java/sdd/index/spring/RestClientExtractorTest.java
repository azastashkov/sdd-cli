package sdd.index.spring;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.index.source.SourceParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RestClientExtractorTest {
    @TempDir Path repo;

    private SourceParser.Session parse(String relPath, String source) throws Exception {
        Path f = repo.resolve(relPath);
        Files.createDirectories(f.getParent());
        Files.writeString(f, source);
        return SourceParser.parseModule(repo, repo, List.of());
    }

    @Test
    void feignClientMethodsBecomeClientRows() throws Exception {
        var session = parse("src/main/java/com/acme/BillingClient.java", """
                package com.acme;
                import org.springframework.cloud.openfeign.FeignClient;
                import org.springframework.web.bind.annotation.*;
                @FeignClient(name = "billing", path = "/pay")
                public interface BillingClient {
                    @PostMapping("/charge") String charge(@RequestBody String req);
                    @GetMapping("/status/{id}") String status(@PathVariable String id);
                }
                """);
        List<SpringModel.ClientInfo> clients = RestClientExtractor.extract(session, Map.of());

        assertThat(clients).hasSize(2);
        assertThat(clients).allSatisfy(c -> {
            assertThat(c.kind()).isEqualTo("FEIGN");
            assertThat(c.targetHint()).isEqualTo("billing");
        });
        assertThat(clients).anySatisfy(c -> {
            assertThat(c.httpMethod()).isEqualTo("POST");
            assertThat(c.uriTemplate()).isEqualTo("/pay/charge");
        });
    }

    @Test
    void feignUrlAttributeWinsAndResolvesPlaceholders() throws Exception {
        var session = parse("src/main/java/com/acme/ExtClient.java", """
                package com.acme;
                import org.springframework.cloud.openfeign.FeignClient;
                import org.springframework.web.bind.annotation.GetMapping;
                @FeignClient(name = "ext", url = "${ext.base-url}")
                public interface ExtClient { @GetMapping("/ping") String ping(); }
                """);
        List<SpringModel.ClientInfo> clients = RestClientExtractor.extract(
                session, Map.of("ext.base-url", "http://ext:9000"));
        assertThat(clients).singleElement().satisfies(c ->
                assertThat(c.targetHint()).isEqualTo("http://ext:9000"));
    }

    @Test
    void restTemplateCallSitesWithConstantAndDynamicUris() throws Exception {
        var session = parse("src/main/java/com/acme/Caller.java", """
                package com.acme;
                public class Caller {
                    private static final String BASE = "http://billing:8080";
                    private Object restTemplate;
                    public void ok() { call(((org.springframework.web.client.RestTemplate) restTemplate)
                            .getForObject(BASE + "/charge", String.class)); }
                    public void dyn() { call(((org.springframework.web.client.RestTemplate) restTemplate)
                            .getForObject(System.getenv("URL"), String.class)); }
                    private void call(Object o) {}
                }
                """);
        List<SpringModel.ClientInfo> clients = RestClientExtractor.extract(session, Map.of());

        assertThat(clients).hasSize(2);
        assertThat(clients).anySatisfy(c -> {
            assertThat(c.kind()).isEqualTo("RESTTEMPLATE");
            assertThat(c.httpMethod()).isEqualTo("GET");
            assertThat(c.uriTemplate()).isEqualTo("http://billing:8080/charge");
            assertThat(c.resolution()).isEqualTo("CONSTANT");
        });
        assertThat(clients).anySatisfy(c -> {
            assertThat(c.resolution()).isEqualTo("DYNAMIC");
            assertThat(c.uriTemplate()).isNull();
            assertThat(c.rawExpr()).contains("System.getenv");
        });
    }

    @Test
    void feignRequestMappingStyleMethodsAreExtracted() throws Exception {
        var session = parse("src/main/java/com/acme/LegacyClient.java", """
                package com.acme;
                import org.springframework.cloud.openfeign.FeignClient;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RequestMethod;
                @FeignClient(name = "legacy")
                @RequestMapping("/v1")
                public interface LegacyClient {
                    @RequestMapping(value = "/things", method = RequestMethod.GET) String list();
                }
                """);
        List<SpringModel.ClientInfo> clients = RestClientExtractor.extract(session, Map.of());
        assertThat(clients).singleElement().satisfies(c -> {
            assertThat(c.kind()).isEqualTo("FEIGN");
            assertThat(c.httpMethod()).isEqualTo("GET");
            assertThat(c.uriTemplate()).isEqualTo("/v1/things");
            assertThat(c.targetHint()).isEqualTo("legacy");
        });
    }

    @Test
    void feignNoPathMethodInheritsBasePathResolutionInsteadOfHardcodedLiteral() throws Exception {
        var session = parse("src/main/java/com/acme/PropClient.java", """
                package com.acme;
                import org.springframework.cloud.openfeign.FeignClient;
                import org.springframework.web.bind.annotation.GetMapping;
                @FeignClient(name = "propd", path = "${propd.base-path}")
                public interface PropClient {
                    @GetMapping String root();
                }
                """);
        List<SpringModel.ClientInfo> clients = RestClientExtractor.extract(
                session, Map.of("propd.base-path", "/api"));
        assertThat(clients).singleElement().satisfies(c -> {
            assertThat(c.uriTemplate()).isEqualTo("/api");
            // resolution/rawExpr must reflect how the @FeignClient path base itself resolved
            // (a property placeholder here), not a hardcoded LITERAL/annotation-text fallback.
            assertThat(c.resolution()).isEqualTo("PROPERTY");
            assertThat(c.rawExpr()).contains("propd.base-path");
        });
    }

    @Test
    void webClientChainYieldsVerbAndUri() throws Exception {
        var session = parse("src/main/java/com/acme/W.java", """
                package com.acme;
                public class W {
                    private org.springframework.web.reactive.function.client.WebClient webClient;
                    public void go() { webClient.get().uri("/api/items").retrieve(); }
                }
                """);
        List<SpringModel.ClientInfo> clients = RestClientExtractor.extract(session, Map.of());
        assertThat(clients).singleElement().satisfies(c -> {
            assertThat(c.kind()).isEqualTo("WEBCLIENT");
            assertThat(c.httpMethod()).isEqualTo("GET");
            assertThat(c.uriTemplate()).isEqualTo("/api/items");
        });
    }
}
