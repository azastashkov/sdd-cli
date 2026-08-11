package sdd.index.spring;

import java.util.List;

public final class SpringModel {
    private SpringModel() {}

    public record EndpointInfo(String classFqcn, String methodName, String httpMethod,
                               String pathTemplate, String requestType, String responseType) {}
    public record ClientInfo(String kind, String classFqcn, String methodOrSite, String httpMethod,
                             String uriTemplate, String targetHint, String resolution, String rawExpr) {}
    public record KafkaUse(String topic, String role, String classFqcn, String groupId,
                           String payloadType, String resolution, String rawExpr) {}
    public record SpringExtract(List<EndpointInfo> endpoints, List<ClientInfo> clients,
                                List<KafkaUse> kafka, boolean streamDetected) {}
}
