package sdd.plan.confluence;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic text extraction from a Confluence export — storage-format XHTML or exported
 * HTML. No model involvement: headings/paragraphs/lists/tables/code become markdown-ish text;
 * images are NOT interpreted (design amendment) — they become [attachment: name] markers and
 * entries in Extracted.attachments so the Gate-1 reviewer knows visual context exists.
 * Walks child NODES (not just elements): bare text directly inside div/body wrappers must
 * survive — silent prose loss would starve the normalizer without anyone noticing.
 */
public final class ConfluenceExtract {
    static final int MAX_TEXT_CHARS = 300_000;   // ~75k tokens — leaves DeepSeek headroom
    private static final Pattern CDATA = Pattern.compile("\\[CDATA\\[(.*)]]", Pattern.DOTALL);

    public record Extracted(String text, List<String> attachments) {
    }

    private ConfluenceExtract() {
    }

    public static Extracted extract(String html) {
        Document doc = Jsoup.parse(html);
        List<String> attachments = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        walk(doc.body(), text, attachments);
        String result = text.toString().strip();
        if (result.length() > MAX_TEXT_CHARS) {
            throw new SpecNormalizationException("Confluence export too large: extracted "
                    + result.length() + " chars (limit " + MAX_TEXT_CHARS + ")");
        }
        return new Extracted(result, List.copyOf(attachments));
    }

    private static void walk(Element parent, StringBuilder text, List<String> attachments) {
        for (Node node : parent.childNodes()) {
            if (node instanceof TextNode textNode) {
                String bare = textNode.text().strip();
                if (!bare.isEmpty()) {
                    text.append(bare).append("\n\n");
                }
            } else if (node instanceof Element el) {
                element(el, text, attachments);
            }
        }
    }

    private static void element(Element el, StringBuilder text, List<String> attachments) {
        switch (el.tagName()) {
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                int level = el.tagName().charAt(1) - '0';
                text.append("#".repeat(level)).append(' ').append(el.text()).append("\n\n");
            }
            case "p" -> {
                String paragraph = paragraph(el, attachments);
                if (!paragraph.isBlank()) {
                    text.append(paragraph).append("\n\n");
                }
            }
            case "ul", "ol" -> {
                for (Element li : el.children()) {
                    if (li.tagName().equals("li")) {
                        text.append("- ").append(li.text()).append('\n');
                    }
                }
                text.append('\n');
            }
            case "table" -> {
                table(el, text);
                text.append('\n');
            }
            case "pre" -> text.append("```\n").append(el.wholeText().strip()).append("\n```\n\n");
            case "ac:image" -> text.append(storageImageRef(el, attachments)).append("\n\n");
            case "img" -> text.append(htmlImageRef(el, attachments)).append("\n\n");
            case "ac:structured-macro" -> {
                Element body = el.selectFirst("ac|plain-text-body");
                if (body != null) {
                    String code = plainText(body);
                    if (!code.isEmpty()) {
                        text.append("```\n").append(code).append("\n```\n\n");
                    }
                } else {
                    walk(el, text, attachments);
                }
            }
            default -> {
                // block-free wrappers (span/a/strong/...) render as one paragraph; anything
                // containing block structure dives deeper
                if (el.select("p, ul, ol, table, pre, h1, h2, h3, h4, h5, h6").isEmpty()) {
                    String paragraph = paragraph(el, attachments);
                    if (!paragraph.isBlank()) {
                        text.append(paragraph).append("\n\n");
                    }
                } else {
                    walk(el, text, attachments);
                }
            }
        }
    }

    /** jsoup's HTML parser turns CDATA sections into bogus Comment nodes — recover the payload. */
    private static String plainText(Element body) {
        String direct = body.wholeText().strip();
        if (!direct.isEmpty()) {
            return direct;
        }
        for (Node node : body.childNodes()) {
            if (node instanceof Comment comment) {
                Matcher m = CDATA.matcher(comment.getData());
                if (m.matches()) {
                    return m.group(1).strip();
                }
            }
        }
        return "";
    }

    private static String paragraph(Element p, List<String> attachments) {
        StringBuilder sb = new StringBuilder(p.text());
        for (Element image : p.select("ac|image, img")) {
            String ref = image.tagName().equals("img")
                    ? htmlImageRef(image, attachments)
                    : storageImageRef(image, attachments);
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(ref);
        }
        return sb.toString().strip();
    }

    private static void table(Element tableEl, StringBuilder text) {
        List<List<String>> rows = new ArrayList<>();
        for (Element tr : tableEl.select("tr")) {
            List<String> cells = new ArrayList<>();
            for (Element cell : tr.select("th, td")) {
                cells.add(cell.text().replace("|", "\\|"));
            }
            if (!cells.isEmpty()) {
                rows.add(cells);
            }
        }
        if (rows.isEmpty()) {
            return;
        }
        text.append("| ").append(String.join(" | ", rows.get(0))).append(" |\n");
        text.append("|").append(" --- |".repeat(rows.get(0).size())).append('\n');
        for (List<String> row : rows.subList(1, rows.size())) {
            text.append("| ").append(String.join(" | ", row)).append(" |\n");
        }
    }

    private static String storageImageRef(Element acImage, List<String> attachments) {
        Element attachment = acImage.selectFirst("ri|attachment");
        String name = attachment == null ? "unknown-image" : attachment.attr("ri:filename");
        return remember(name.isBlank() ? "unknown-image" : name, attachments);
    }

    private static String htmlImageRef(Element img, List<String> attachments) {
        String src = img.attr("src");
        int slash = src.lastIndexOf('/');
        String name = slash >= 0 ? src.substring(slash + 1) : src;
        return remember(name.isBlank() ? "unknown-image" : name, attachments);
    }

    private static String remember(String name, List<String> attachments) {
        if (!attachments.contains(name)) {
            attachments.add(name);
        }
        return "[attachment: " + name + "]";
    }
}
