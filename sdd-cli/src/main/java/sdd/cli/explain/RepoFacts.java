package sdd.cli.explain;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import sdd.core.kb.EntityKind;
import sdd.core.kb.KbEntities;
import sdd.core.kb.Resolution;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * {@code describe} intent: repo-level facts for every repo an entity resolves to. Re-expresses
 * {@code RepoCardGenerator.composeInput}'s SQL shapes (modules, endpoints, topics, the two
 * symmetric {@code v_repo_dep_edge} queries) — deliberately <strong>without</strong> its
 * {@code README.md} read, since that file is unversioned working-tree text, not a KB fact.
 */
final class RepoFacts {
    /** {@code repo_card.card_md} is model prose stored in SQL; capped so one section can't dwarf the rest. */
    private static final int CARD_MD_CAP = 800;

    private RepoFacts() {
    }

    static List<Section> of(Jdbi jdbi, RetrievalRequest request) {
        List<Section> sections = new ArrayList<>();
        LinkedHashSet<String> repos = new LinkedHashSet<>();
        for (EntityRef entity : request.entities()) {
            Resolution resolution = KbEntities.resolve(jdbi, entity.kind(), entity.value());
            if (entity.kind() != EntityKind.REPO) {
                sections.add(EvidenceCollector.citation(entity, resolution, false));
            }
            repos.addAll(resolution.repos());
        }
        for (String repo : repos) {
            sections.addAll(describeRepo(jdbi, repo));
        }
        return sections;
    }

    private static List<Section> describeRepo(Jdbi jdbi, String repo) {
        return jdbi.withHandle(h -> {
            List<Section> sections = new ArrayList<>();
            sections.add(repoRow(h, repo));
            sections.add(card(h, repo));
            sections.add(modules(h, repo));
            sections.add(endpoints(h, repo));
            sections.add(kafkaRoles(h, repo));
            sections.add(depsOut(h, repo));
            sections.add(depsIn(h, repo));
            sections.add(topJavaTypes(h, repo));
            return sections;
        });
    }

    private static Section repoRow(Handle h, String repo) {
        Map<String, Object> row = h.createQuery(
                        "SELECT name, kind, gradle_status, parse_status, indexed_at FROM repo WHERE name = :r")
                .bind("r", repo).mapToMap().findOne().orElse(null);
        if (row == null) {
            return Section.of("Repo: " + repo, "repo", List.of());
        }
        StringBuilder text = new StringBuilder(String.valueOf(row.get("name")))
                .append(" (").append(row.get("kind")).append(')');
        if (row.get("gradle_status") != null) {
            text.append(", gradle_status=").append(row.get("gradle_status"));
        }
        if (row.get("parse_status") != null) {
            text.append(", parse_status=").append(row.get("parse_status"));
        }
        if (row.get("indexed_at") != null) {
            text.append(", indexed_at=").append(row.get("indexed_at"));
        }
        return Section.of("Repo: " + repo, "repo", List.of(new Fact(text.toString())));
    }

    private static Section card(Handle h, String repo) {
        Map<String, Object> row = h.createQuery("""
                        SELECT rc.card_line AS card_line, rc.card_md AS card_md
                        FROM repo_card rc JOIN repo r ON r.id = rc.repo_id
                        WHERE r.name = :r""")
                .bind("r", repo).mapToMap().findOne().orElse(null);
        if (row == null || row.get("card_line") == null) {
            return Section.of("Summary: " + repo, "repo_card", List.of());
        }
        List<Fact> facts = new ArrayList<>();
        facts.add(new Fact("card_line (model-generated summary, repo_card.card_line): "
                + row.get("card_line")));
        Object cardMdRaw = row.get("card_md");
        if (cardMdRaw == null) {
            facts.add(new Fact("card_md (model-generated summary, repo_card.card_md): none recorded"));
        } else {
            String cardMd = String.valueOf(cardMdRaw);
            String capped = cardMd.length() > CARD_MD_CAP
                    ? cardMd.substring(0, CARD_MD_CAP) + " [truncated]"
                    : cardMd;
            facts.add(new Fact("card_md (model-generated summary, repo_card.card_md, capped to "
                    + CARD_MD_CAP + " chars): " + capped));
        }
        return Section.of("Summary: " + repo, "repo_card", facts);
    }

    private static Section modules(Handle h, String repo) {
        List<Map<String, Object>> rows = h.createQuery("""
                        SELECT m.gradle_path AS gradle_path, m.kind AS kind
                        FROM module m JOIN repo r ON r.id = m.repo_id
                        WHERE r.name = :r ORDER BY m.gradle_path""")
                .bind("r", repo).mapToMap().list();
        List<Fact> facts = rows.stream()
                .map(row -> new Fact(row.get("gradle_path") + " (" + row.get("kind") + ")"))
                .toList();
        return Section.capped("Modules: " + repo, "module", facts, Section.DEFAULT_LIMIT);
    }

    private static Section endpoints(Handle h, String repo) {
        List<Map<String, Object>> rows = h.createQuery("""
                        SELECT re.http_method AS http_method, re.norm_path AS norm_path
                        FROM rest_endpoint re JOIN module m ON m.id = re.module_id
                        JOIN repo r ON r.id = m.repo_id
                        WHERE r.name = :r ORDER BY re.norm_path, re.http_method""")
                .bind("r", repo).mapToMap().list();
        List<Fact> facts = rows.stream()
                .map(row -> new Fact(row.get("http_method") + " " + row.get("norm_path")))
                .toList();
        return Section.capped("Endpoints: " + repo, "rest_endpoint", facts, Section.DEFAULT_LIMIT);
    }

    private static Section kafkaRoles(Handle h, String repo) {
        List<Map<String, Object>> rows = h.createQuery("""
                        SELECT t.name AS name, kr.role AS role
                        FROM kafka_role kr
                        JOIN kafka_topic t ON t.id = kr.topic_id
                        JOIN module m ON m.id = kr.module_id
                        JOIN repo r ON r.id = m.repo_id
                        WHERE r.name = :r ORDER BY t.name, kr.role""")
                .bind("r", repo).mapToMap().list();
        List<Fact> facts = rows.stream()
                .map(row -> new Fact(row.get("name") + " (" + row.get("role") + ")"))
                .toList();
        return Section.capped("Kafka roles: " + repo, "kafka_role", facts, Section.DEFAULT_LIMIT);
    }

    private static Section depsOut(Handle h, String repo) {
        List<String> names = h.createQuery("""
                        SELECT DISTINCT r2.name AS name
                        FROM v_repo_dep_edge e
                        JOIN repo r1 ON r1.id = e.from_repo_id
                        JOIN repo r2 ON r2.id = e.to_repo_id
                        WHERE r1.name = :r ORDER BY r2.name""")
                .bind("r", repo).mapTo(String.class).list();
        List<Fact> facts = names.stream().map(Fact::new).toList();
        return Section.capped("Depends on: " + repo, "v_repo_dep_edge", facts, Section.DEFAULT_LIMIT);
    }

    private static Section depsIn(Handle h, String repo) {
        List<String> names = h.createQuery("""
                        SELECT DISTINCT r2.name AS name
                        FROM v_repo_dep_edge e
                        JOIN repo r1 ON r1.id = e.to_repo_id
                        JOIN repo r2 ON r2.id = e.from_repo_id
                        WHERE r1.name = :r ORDER BY r2.name""")
                .bind("r", repo).mapTo(String.class).list();
        List<Fact> facts = names.stream().map(Fact::new).toList();
        return Section.capped("Depended on by: " + repo, "v_repo_dep_edge", facts, Section.DEFAULT_LIMIT);
    }

    private static Section topJavaTypes(Handle h, String repo) {
        List<Map<String, Object>> rows = h.createQuery("""
                        SELECT t.fqcn AS fqcn, t.kind AS kind, t.is_api AS is_api
                        FROM java_type t JOIN module m ON m.id = t.module_id
                        JOIN repo r ON r.id = m.repo_id
                        WHERE r.name = :r ORDER BY t.is_api DESC, t.fqcn""")
                .bind("r", repo).mapToMap().list();
        List<Fact> facts = rows.stream()
                .map(row -> new Fact(row.get("fqcn") + " (" + row.get("kind")
                        + ", is_api=" + row.get("is_api") + ")"))
                .toList();
        return Section.capped("Top API types: " + repo, "java_type", facts, Section.MEMBER_LIMIT);
    }
}
