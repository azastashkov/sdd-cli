package sdd.plan.confluence;

import org.jdbi.v3.core.Jdbi;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * The read-through cache for {@link ImageDescriber}, in the shape {@code RepoCardGenerator} uses
 * for repo cards: static methods over a {@code Jdbi}, a content hash as the key, an upsert on the
 * way back. See {@code V8__attachment_description.sql} for why each component is in the key.
 */
public final class AttachmentDescriptions {

    /** A cached pair. {@code disagreement} is empty when the two readings agreed. */
    public record Cached(String description, String disagreement) {
    }

    private AttachmentDescriptions() {
    }

    /**
     * The cache key: everything that could change the answer.
     *
     * <p>{@code systemPrompt} is included for the reason {@code RepoCardGenerator} learned the hard
     * way — leaving it out makes a prompt-only improvement a permanent no-op for everything already
     * described, so the fix ships and nothing ever regenerates to use it.
     */
    public static String key(String pageId, String filename, String attachmentVersion,
            String modelName, String systemPrompt) {
        return sha256(String.join("\n", pageId, filename, String.valueOf(attachmentVersion),
                modelName, systemPrompt));
    }

    public static Optional<Cached> lookup(Jdbi jdbi, String inputHash) {
        return jdbi.withHandle(h -> h.createQuery(
                        "SELECT description, disagreement FROM attachment_description "
                                + "WHERE input_hash = :h")
                .bind("h", inputHash)
                .map((rs, ctx) -> new Cached(rs.getString("description"), rs.getString("disagreement")))
                .findOne());
    }

    public static void store(Jdbi jdbi, String inputHash, String pageId, String filename,
            String attachmentVersion, String modelName, Cached value) {
        jdbi.useHandle(h -> h.createUpdate("""
                        INSERT INTO attachment_description(input_hash, page_id, filename,
                            attachment_version, model, description, disagreement, created_at)
                        VALUES (:h, :page, :file, :ver, :model, :desc, :dis, :at)
                        ON CONFLICT(input_hash) DO UPDATE SET description=excluded.description,
                          disagreement=excluded.disagreement, created_at=excluded.created_at""")
                .bind("h", inputHash).bind("page", pageId).bind("file", filename)
                .bind("ver", attachmentVersion).bind("model", modelName)
                .bind("desc", value.description()).bind("dis", value.disagreement())
                .bind("at", Instant.now().toString())
                .execute());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
