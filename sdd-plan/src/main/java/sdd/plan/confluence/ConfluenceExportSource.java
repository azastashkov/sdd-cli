package sdd.plan.confluence;

import sdd.core.llm.ChatModel;
import sdd.plan.source.SourceBundle;
import sdd.plan.source.SourceDoc;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** v1 Confluence adapter: exported page file -> deterministic extract -> model normalization. */
public final class ConfluenceExportSource implements SpecSource {
    private final ChatModel planner;
    private final String modelName;
    private final int maxTokens;

    public ConfluenceExportSource(ChatModel planner, String modelName, int maxTokens) {
        this.planner = planner;
        this.modelName = modelName;
        this.maxTokens = maxTokens;
    }

    @Override
    public NormalizedSpec load(String ref) {
        Path file = Path.of(ref);
        String html;
        try {
            html = Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ConfluenceExtract.Extracted extracted = ConfluenceExtract.extract(html);
        String id = specId(file);
        SourceDoc doc = new SourceDoc(SourceDoc.Kind.CONFLUENCE_PAGE, id, null, null, null,
                extracted.text(), extracted.attachments());
        SourceBundle bundle = new SourceBundle(List.of(doc), List.of());
        return ConfluenceNormalizer.normalize(bundle, planner, modelName, maxTokens, id);
    }

    static String specId(Path file) {
        String base = file.getFileName().toString();
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        String slug = base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return "spec-" + (slug.isBlank() ? "confluence" : slug);
    }
}
