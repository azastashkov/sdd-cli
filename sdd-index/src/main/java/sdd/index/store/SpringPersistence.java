package sdd.index.store;

import org.jdbi.v3.core.Handle;
import sdd.index.spring.RouteNormalizer;
import sdd.index.spring.SpringModel;

public final class SpringPersistence {
    private SpringPersistence() {}

    public static void persistModuleSpring(Handle h, long moduleId, String contextPath,
                                           SpringModel.SpringExtract extract) {
        h.createUpdate("DELETE FROM rest_endpoint WHERE module_id=:m").bind("m", moduleId).execute();
        h.createUpdate("DELETE FROM rest_client WHERE module_id=:m").bind("m", moduleId).execute();
        h.createUpdate("DELETE FROM kafka_role WHERE module_id=:m").bind("m", moduleId).execute();

        for (SpringModel.EndpointInfo e : extract.endpoints()) {
            h.createUpdate("""
                            INSERT INTO rest_endpoint(module_id, class_fqcn, method_name, http_method,
                                                      path_template, norm_path, request_type, response_type)
                            VALUES (:m, :cls, :name, :verb, :path, :norm, :req, :resp)""")
                    .bind("m", moduleId).bind("cls", e.classFqcn()).bind("name", e.methodName())
                    .bind("verb", e.httpMethod()).bind("path", e.pathTemplate())
                    .bind("norm", RouteNormalizer.normalize(
                            RouteNormalizer.join(contextPath, e.pathTemplate())))
                    .bind("req", e.requestType()).bind("resp", e.responseType()).execute();
        }
        for (SpringModel.ClientInfo c : extract.clients()) {
            h.createUpdate("""
                            INSERT INTO rest_client(module_id, kind, class_fqcn, method_or_site,
                                                    http_method, uri_template, norm_path, target_hint,
                                                    resolution, raw_expr)
                            VALUES (:m, :kind, :cls, :site, :verb, :uri, :norm, :hint, :res, :raw)""")
                    .bind("m", moduleId).bind("kind", c.kind()).bind("cls", c.classFqcn())
                    .bind("site", c.methodOrSite()).bind("verb", c.httpMethod())
                    .bind("uri", c.uriTemplate())
                    .bind("norm", c.uriTemplate() == null ? null
                            : RouteNormalizer.normalize(c.uriTemplate()))
                    .bind("hint", c.targetHint()).bind("res", c.resolution())
                    .bind("raw", c.rawExpr()).execute();
        }
        for (SpringModel.KafkaUse k : extract.kafka()) {
            h.createUpdate("INSERT INTO kafka_topic(name, resolution) VALUES (:n, :r) "
                            + "ON CONFLICT(name) DO NOTHING")
                    .bind("n", k.topic()).bind("r", k.resolution()).execute();
            long topicId = h.createQuery("SELECT id FROM kafka_topic WHERE name=:n")
                    .bind("n", k.topic()).mapTo(Long.class).one();
            h.createUpdate("""
                            INSERT INTO kafka_role(module_id, topic_id, role, class_fqcn, group_id, payload_type)
                            VALUES (:m, :t, :role, :cls, :grp, :payload)""")
                    .bind("m", moduleId).bind("t", topicId).bind("role", k.role())
                    .bind("cls", k.classFqcn()).bind("grp", k.groupId())
                    .bind("payload", k.payloadType()).execute();
        }
        h.createUpdate("UPDATE module SET kafka_status=:s WHERE id=:m")
                .bind("s", extract.streamDetected() ? "UNPARSED_STREAM" : null)
                .bind("m", moduleId).execute();
    }
}
