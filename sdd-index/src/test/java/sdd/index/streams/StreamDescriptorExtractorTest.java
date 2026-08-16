package sdd.index.streams;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.index.source.SourceParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The builders here are trading-platform-libs' own {@code CanonicalDescriptors}, copied verbatim
 * down to the argument order — the point of this extractor is that it reads that exact shape, so a
 * simplified fixture would test nothing.
 */
class StreamDescriptorExtractorTest {
    @TempDir Path module;

    private List<StreamDescriptorExtractor.Descriptor> extract(String source) throws Exception {
        Path file = module.resolve("src/main/java/com/trading/streams/CanonicalDescriptors.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        return StreamDescriptorExtractor.extract(
                SourceParser.parseModule(module, module, List.of()));
    }

    private static final String MD = """
            package com.trading.streams;
            import java.util.List;
            import java.util.Map;
            public final class CanonicalDescriptors {
                private static final List<String> ALL_PRODUCTS =
                        List.of("PRODUCT1", "PRODUCT2", "PRODUCT3");

                public static StreamDescriptor md(String owner, List<String> products,
                                                  Map<String, List<String>> productRoles) {
                    KeySpec key = new KeySpec(
                            List.of(new KeyField("clientId", true, null),
                                    new KeyField("securityType", true, null)),
                            "clientId", "securityType");
                    ChannelBinding tick = new ChannelBinding(
                            "md.tick.{securityType}.{clientId}", ChannelScope.KEY,
                            FanoutMode.CHANNEL_KEYED, new FrameType("md.tick", null, null),
                            "md.lvc.{securityType}.{clientId}",
                            new Conflation(List.of("clientId", "securityType", "symbol")),
                            new FrameShape(null, List.of("ts")));
                    ChannelBinding reject = new ChannelBinding(
                            "md.reject.{securityType}.{clientId}", ChannelScope.KEY,
                            FanoutMode.CHANNEL_KEYED, new FrameType("md.reject", null, null),
                            "md.reject.lvc.{securityType}.{clientId}", null,
                            new FrameShape(null, List.of("ts")));
                    ChannelBinding feedStatus = new ChannelBinding(
                            "feed.status.{securityType}", ChannelScope.PRODUCT,
                            FanoutMode.CHANNEL_KEYED, new FrameType("feed.status", null, null),
                            "feed.status.lvc.{securityType}", null,
                            new FrameShape(List.of("securityType", "status", "ts"), List.of("ts")));
                    InterestSpec interest = new InterestSpec("/pricing/md-subs/{securityType}/{clientId}");
                    return new StreamDescriptor("md", owner, List.copyOf(products), productRoles,
                            Activation.ON_SUBSCRIBE, key, null,
                            List.of(tick, reject, feedStatus), interest, null, null);
                }
            }
            """;

    @Test
    void aBuildersKeyFieldsAreReadInOrder() throws Exception {
        assertThat(extract(MD)).singleElement().satisfies(descriptor -> {
            assertThat(descriptor.stream()).isEqualTo("md");
            // Order, not set: the field order decides how a subscription key is encoded on the
            // wire, so two ends that agree on the set and not the order never match at runtime.
            assertThat(descriptor.key()).containsExactly("clientId", "securityType");
        });
    }

    @Test
    void channelsAreReadAsTheirFrameTypes() throws Exception {
        assertThat(extract(MD)).singleElement().satisfies(descriptor ->
                assertThat(descriptor.channels())
                        .containsExactly("md.tick", "md.reject", "feed.status"));
    }

    @Test
    void theResolvedFieldListIsNotMistakenForTheChannelList() throws Exception {
        // candle passes BOTH List.of(tier) and List.of(update). Matching by argument position
        // would read whichever came first; matching by element type reads the right one.
        List<StreamDescriptorExtractor.Descriptor> descriptors = extract("""
                package com.trading.streams;
                import java.util.List;
                public final class CanonicalDescriptors {
                    public static StreamDescriptor candle(String owner) {
                        KeySpec key = new KeySpec(List.of(
                                new KeyField("clientId", true, null),
                                new KeyField("period", true, List.of("1m", "5m"))),
                                "clientId", "securityType");
                        ResolvedField tier = new ResolvedField("tier", "entitlementTier", "clientId");
                        ChannelBinding update = new ChannelBinding(
                                "candle.update.{securityType}.{tier}", ChannelScope.KEY,
                                FanoutMode.PAYLOAD_KEYED, new FrameType("candle.update", null, null),
                                "candle.lvc.{securityType}", null,
                                new FrameShape(null, List.of("openTs", "ts")));
                        return new StreamDescriptor("candle", owner, ALL_PRODUCTS, null,
                                Activation.ON_SUBSCRIBE, key, List.of(tier), List.of(update),
                                null, null, null);
                    }
                }
                """);

        assertThat(descriptors).singleElement().satisfies(descriptor -> {
            assertThat(descriptor.key()).containsExactly("clientId", "period");
            assertThat(descriptor.channels()).containsExactly("candle.update");
        });
    }

    @Test
    void aChannelDiscriminatedByAPayloadFieldHasNoFrameType() throws Exception {
        // order's channel carries `new FrameType(null, "kind", map)`. There genuinely is no single
        // frame type, so the entry is null — a fact about the descriptor, not a failure to read it.
        List<StreamDescriptorExtractor.Descriptor> descriptors = extract("""
                package com.trading.streams;
                import java.util.LinkedHashMap;
                import java.util.List;
                import java.util.Map;
                public final class CanonicalDescriptors {
                    public static StreamDescriptor order(String owner) {
                        KeySpec key = new KeySpec(List.of(new KeyField("clientId", true, null)),
                                "clientId", null);
                        Map<String, String> kindMap = new LinkedHashMap<>();
                        kindMap.put("notif", "order.notif");
                        ChannelBinding notif = new ChannelBinding(
                                "order.notif.{clientId}", ChannelScope.KEY, FanoutMode.CHANNEL_KEYED,
                                new FrameType(null, "kind", Map.copyOf(kindMap)), null, null, null);
                        return new StreamDescriptor("order", owner, ALL_PRODUCTS, null,
                                Activation.ON_AUTH, key, null, List.of(notif), null, null, null);
                    }
                }
                """);

        assertThat(descriptors).singleElement().satisfies(descriptor -> {
            assertThat(descriptor.stream()).isEqualTo("order");
            assertThat(descriptor.channels()).containsExactly((String) null);
        });
    }

    @Test
    void aMethodThatReturnsSomethingElseIsNotADescriptorBuilder() throws Exception {
        assertThat(extract("""
                package com.trading.streams;
                import java.util.List;
                public final class CanonicalDescriptors {
                    public static String name() { return "md"; }
                    public static KeySpec key() {
                        return new KeySpec(List.of(new KeyField("clientId", true, null)), "clientId", null);
                    }
                }
                """)).isEmpty();
    }

    @Test
    void anUnparseableTreeYieldsNothingRatherThanThrowing() throws Exception {
        // Contract actualization runs jar-less over whatever is on disk. A builder file mid-edit
        // must degrade that one descriptor, never the whole re-check.
        assertThat(extract("package com.trading.streams; public final class CanonicalDescriptors {"))
                .isEmpty();
    }
}
