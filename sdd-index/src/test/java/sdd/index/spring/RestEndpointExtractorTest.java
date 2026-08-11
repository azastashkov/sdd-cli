package sdd.index.spring;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.index.source.SourceParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RestEndpointExtractorTest {
    @TempDir Path repo;

    private SourceParser.Session parse(String source) throws Exception {
        Path f = repo.resolve("src/main/java/com/acme/web/C.java");
        Files.createDirectories(f.getParent());
        Files.writeString(f, source);
        return SourceParser.parseModule(repo, repo, List.of());
    }

    @Test
    void joinsClassAndMethodPathsAcrossVerbAnnotations() throws Exception {
        var session = parse("""
                package com.acme.web;
                import org.springframework.web.bind.annotation.*;
                @RestController
                @RequestMapping("/api/orders")
                public class C {
                    @GetMapping("/{id}") public String get(@PathVariable String id) { return id; }
                    @PostMapping public String create(@RequestBody OrderReq req) { return "x"; }
                    @RequestMapping(path = "/search", method = RequestMethod.GET)
                    public String search() { return "s"; }
                }
                class OrderReq {}
                """);
        List<SpringModel.EndpointInfo> endpoints =
                RestEndpointExtractor.extract(session, Map.of());

        assertThat(endpoints).hasSize(3);
        assertThat(endpoints).anySatisfy(e -> {
            assertThat(e.httpMethod()).isEqualTo("GET");
            assertThat(e.pathTemplate()).isEqualTo("/api/orders/{id}");
            assertThat(e.methodName()).isEqualTo("get");
            assertThat(e.responseType()).isEqualTo("String");
        });
        assertThat(endpoints).anySatisfy(e -> {
            assertThat(e.httpMethod()).isEqualTo("POST");
            assertThat(e.pathTemplate()).isEqualTo("/api/orders");
            assertThat(e.requestType()).isEqualTo("OrderReq");
        });
        assertThat(endpoints).anySatisfy(e -> {
            assertThat(e.httpMethod()).isEqualTo("GET");
            assertThat(e.pathTemplate()).isEqualTo("/api/orders/search");
        });
    }

    @Test
    void controllerWithResponseBodyCountsPlainControllerDoesNot() throws Exception {
        var session = parse("""
                package com.acme.web;
                import org.springframework.stereotype.Controller;
                import org.springframework.web.bind.annotation.*;
                @Controller @ResponseBody
                public class C { @GetMapping("/x") public String x() { return "x"; } }
                """);
        assertThat(RestEndpointExtractor.extract(session, Map.of())).hasSize(1);

        var mvc = parse("""
                package com.acme.web;
                import org.springframework.stereotype.Controller;
                import org.springframework.web.bind.annotation.*;
                @Controller
                public class C { @GetMapping("/page") public String page() { return "view"; } }
                """);
        assertThat(RestEndpointExtractor.extract(mvc, Map.of())).isEmpty();
    }

    @Test
    void multiplePathsProduceMultipleEndpointsAndPropertyPathsResolve() throws Exception {
        var session = parse("""
                package com.acme.web;
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class C {
                    @GetMapping({"/a", "/b"}) public String multi() { return "m"; }
                    @GetMapping("${routes.health}") public String health() { return "h"; }
                }
                """);
        List<SpringModel.EndpointInfo> endpoints = RestEndpointExtractor.extract(
                session, Map.of("routes.health", "/health"));
        assertThat(endpoints).extracting(SpringModel.EndpointInfo::pathTemplate)
                .containsExactlyInAnyOrder("/a", "/b", "/health");
    }

    @Test
    void multiVerbRequestMappingFansOutOneEndpointPerVerb() throws Exception {
        var session = parse("""
                package com.acme.web;
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class C {
                    @RequestMapping(path = "/multi", method = {RequestMethod.GET, RequestMethod.POST})
                    public String multi() { return "m"; }
                }
                """);
        List<SpringModel.EndpointInfo> endpoints = RestEndpointExtractor.extract(session, Map.of());
        assertThat(endpoints).extracting(SpringModel.EndpointInfo::httpMethod)
                .containsExactlyInAnyOrder("GET", "POST");
        assertThat(endpoints).allSatisfy(e -> assertThat(e.pathTemplate()).isEqualTo("/multi"));
    }
}
