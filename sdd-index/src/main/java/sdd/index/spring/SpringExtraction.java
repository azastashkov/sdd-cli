package sdd.index.spring;

import sdd.index.source.SourceParser;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public final class SpringExtraction {
    private SpringExtraction() {}

    public static SpringModel.SpringExtract extractModule(SourceParser.Session session,
                                                           Map<String, String> defaultProps,
                                                           List<Path> classpathJars,
                                                           Collection<String> allConfigKeys) {
        List<SpringModel.EndpointInfo> endpoints = RestEndpointExtractor.extract(session, defaultProps);
        List<SpringModel.ClientInfo> clients = RestClientExtractor.extract(session, defaultProps);
        KafkaExtractor.KafkaResult kafka = KafkaExtractor.extract(
                session, defaultProps, classpathJars, allConfigKeys);
        return new SpringModel.SpringExtract(endpoints, clients, kafka.uses(), kafka.streamDetected());
    }
}
