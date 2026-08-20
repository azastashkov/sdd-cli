package sdd.plan.openspec;

import sdd.core.contract.Markdown;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Renders one repository's slice of an sdd plan as an OpenSpec change.
 *
 * <p>Pure: no clock, no filesystem, no database. Rendering the same input twice must produce the
 * same bytes, because the writer decides "ours, unchanged" from "a human edited it" by comparing
 * them — and because a wall-clock stamp would make a regenerated plan un-diffable. Nothing here
 * emits a date, and {@code .openspec.yaml}'s optional {@code created:} is deliberately never
 * written.
 *
 * <p>Targets OpenSpec <b>v1.10.0</b>. The rules that shape this file, in the order they bite:
 * <ul>
 *   <li>every {@code ADDED} requirement needs at least one {@code #### Scenario:} and non-empty
 *       body text — both hard errors. {@link #scenarios} is a four-rung ladder that cannot return
 *       empty, so neither error is reachable;
 *   <li>{@code SHALL} or {@code MUST} must appear in the requirement <em>body</em>, not only the
 *       header — a warning normally, an error under {@code --strict}. Every body opens with one;
 *   <li>a delta at {@code specs/spec.md} with no capability folder is an error, so a capability
 *       path is always derived;
 *   <li>zero deltas is an error unless {@code skip_specs: true}, which is exactly what a
 *       rebuild-only repo gets.
 * </ul>
 *
 * <p>Only {@code ## ADDED Requirements} is ever emitted. {@code MODIFIED} must reproduce the full
 * current requirement including every scenario the live spec already has, or archive refuses — that
 * means faithfully reproducing text sdd did not write, under a rule whose failure is silent until
 * someone archives. Adding a requirement to a capability that already exists is legal and is the
 * honest description of what an sdd change does.
 */
public final class OpenSpecChange {

    /** The OpenSpec release this renderer targets. Bumping it means re-running the npx harness. */
    public static final String TARGET_VERSION = "1.10.0";

    /** OpenSpec warns below 50 characters; the boilerplate below guarantees we clear it. */
    private static final int MIN_WHY_CHARS = 50;

    /** A requirement name is one line, and a long one is unreadable in a heading. */
    private static final int MAX_NAME_CHARS = 80;

    private OpenSpecChange() {
    }

    /**
     * The rendered files, keyed by their path relative to the repository root. A rebuild-only repo
     * has an empty {@code deltas} map and no {@code specs/} directory at all — not an empty one,
     * which would be a delta-less change and therefore an error.
     */
    public record Files(String openSpecYaml, String proposal, String design, String tasks,
                        Map<String, String> deltas) {
        public Files {
            deltas = Map.copyOf(deltas);
        }

        /** Every file as {@code <repo-relative path> -> contents}, in a stable order. */
        public Map<String, String> byPath(String changeId) {
            Map<String, String> out = new LinkedHashMap<>();
            String base = "openspec/changes/" + changeId + "/";
            out.put(base + ".openspec.yaml", openSpecYaml);
            out.put(base + "proposal.md", proposal);
            if (design != null) {
                out.put(base + "design.md", design);
            }
            out.put(base + "tasks.md", tasks);
            new java.util.TreeMap<>(deltas).forEach((capability, body) ->
                    out.put(base + "specs/" + capability + "/spec.md", body));
            return out;
        }
    }

    /** Renders with no knowledge of the target tree — the capability reads as new. */
    public static Files render(OpenSpecInput in) {
        return render(in, false);
    }

    /**
     * @param capabilityExists whether the target repository already has a main spec for this
     *                         capability. A filesystem fact, not plan data, which is why it is a
     *                         parameter rather than a field on {@link OpenSpecInput}: the input is
     *                         built at plan time and this is only knowable once the tree is at the
     *                         plan's base commit. It changes only whether the proposal says New or
     *                         Modified Capabilities — the delta is ADDED either way.
     */
    public static Files render(OpenSpecInput in, boolean capabilityExists) {
        String capability = capabilityOf(in);
        Map<String, String> deltas = in.rebuildOnly() || in.covers().isEmpty()
                ? Map.of()
                : Map.of(capability, delta(in, capability));
        return new Files(openSpecYaml(deltas.isEmpty()),
                proposal(in, capability, deltas.isEmpty(), capabilityExists), design(in), tasks(in),
                deltas);
    }

    // ---------------------------------------------------------------- .openspec.yaml

    private static String openSpecYaml(boolean noDeltas) {
        StringBuilder out = new StringBuilder("schema: spec-driven\n");
        if (noDeltas) {
            // Without this, a change with no delta specs is an error. With it, "this repository
            // rebuilds and changes no behaviour" is expressible, which is what a rebuild-only repo
            // in a multi-repo change actually is.
            out.append("skip_specs: true\n");
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- capability

    /**
     * The capability path, in falling order of how much anyone actually knows.
     *
     * <p>A capability is a durable behaviour area of the <em>target</em> repository. sdd's slice is
     * described by change-scoped requirement ids and contract ids, neither of which is durable, so
     * sdd cannot derive a good one mechanically — which is why the drafter proposes it and a human
     * corrects it at Gate 1. The fallbacks exist so the export never fails; the last one says so
     * loudly in the proposal.
     */
    public static String capabilityOf(OpenSpecInput in) {
        if (in.plan().capability() != null) {
            return in.plan().capability();
        }
        if (!in.provides().isEmpty()) {
            return Kebab.of(in.provides().get(0).id());
        }
        if (!in.consumes().isEmpty()) {
            return Kebab.of(in.consumes().get(0).id()) + "-integration";
        }
        return Kebab.of(in.specId());
    }

    private static boolean capabilityIsChangeScoped(OpenSpecInput in) {
        return in.plan().capability() == null && in.provides().isEmpty() && in.consumes().isEmpty();
    }

    // ---------------------------------------------------------------- proposal.md

    private static String proposal(OpenSpecInput in, String capability, boolean noDeltas,
                                   boolean capabilityExists) {
        StringBuilder md = new StringBuilder();
        md.append("# ").append(inline(in.specTitle())).append("\n\n");

        md.append("## Why\n");
        md.append(prose(in.goal())).append("\n\n");
        if (in.rebuildOnly()) {
            md.append("This repository has no behaviour change of its own. It is part of `")
                    .append(in.changeId()).append("` because it builds against a repository that "
                            + "does change, and must be rebuilt and re-verified against it.\n\n");
        }
        md.append(sharedIdParagraph(in));
        if (why(in).length() < MIN_WHY_CHARS && !in.background().isBlank()) {
            md.append('\n').append(prose(in.background())).append('\n');
        }

        md.append("\n## What Changes\n");
        String subSpec = prose(in.subSpec());
        if (!subSpec.isBlank()) {
            md.append(subSpec).append("\n\n");
        }
        List<String> changes = new ArrayList<>();
        for (OpenSpecInput.Contract contract : in.provides()) {
            changes.add("Expose the `" + contract.id() + "` (" + contract.kind() + ") interface"
                    + consumersSuffix(contract) + ".");
        }
        for (OpenSpecInput.Contract contract : in.consumes()) {
            changes.add("Consume the `" + contract.id() + "` (" + contract.kind() + ") interface"
                    + (contract.provider() == null || contract.provider().isBlank() ? ""
                            : " from `" + contract.provider() + "`") + ".");
        }
        for (String bump : in.bumps()) {
            changes.add(inline(bump));
        }
        for (String file : in.files()) {
            changes.add("Change `" + inline(file) + "`.");
        }
        if (!"none".equals(in.versionAction())) {
            changes.add("Publish a `" + in.versionAction() + "` version bump.");
        }
        if (in.rebuildOnly() && in.bumps().isEmpty()) {
            changes.add("Rebuild and re-verify. No source change is expected.");
        }
        if (changes.isEmpty()) {
            changes.add("No file-level change is known in advance; see the delta specification.");
        }
        changes.forEach(c -> md.append("- ").append(c).append('\n'));

        md.append("\n## Capabilities\n");
        if (noDeltas) {
            md.append("\n_None — this change makes no behavioural change in this repository._\n");
        } else {
            md.append("\n### ").append(capabilityExists ? "Modified" : "New")
                    .append(" Capabilities\n");
            md.append("- `").append(capability).append("`: ")
                    .append(capabilityBlurb(in, capability)).append('\n');
        }

        md.append("\n## Impact\n");
        md.append("- Repositories in `").append(in.changeId()).append("`: ")
                .append(repoList(in)).append(".\n");
        if (!in.order().isEmpty()) {
            md.append("- Execution order: ").append(orderLine(in)).append(".\n");
        }
        for (OpenSpecInput.Contract contract : in.consumes()) {
            if (contract.provider() != null && !contract.provider().isBlank()) {
                md.append("- Depends on `").append(contract.provider())
                        .append("` landing `").append(contract.id())
                        .append("` first; do not implement this repository before it.\n");
            }
        }
        md.append("- Release: `").append(in.versionAction()).append("`.\n");
        md.append("- Generated from sdd plan `").append(in.specId()).append("` version ")
                .append(in.planVersion());
        if (in.baseSha() != null && !in.baseSha().isBlank()) {
            md.append(", against `").append(shortSha(in.baseSha())).append('`');
        }
        md.append(".\n");
        if (capabilityIsChangeScoped(in) && !noDeltas) {
            md.append("- The capability path `").append(capability).append("` was derived from the "
                    + "specification id because nothing better was available. It is change-scoped; "
                    + "rename it to a durable behaviour area before applying.\n");
        }
        // Phrased as a condition, not a claim: the renderer is pure and cannot see the repo, and
        // sdd deliberately never creates config.yaml (its schema is unverified for this version).
        md.append("- If this repository has no `openspec/` project yet, run `openspec init` before "
                + "applying this change.\n");
        return md.toString();
    }

    /** The whole cross-repo convention, in prose, because the format has nowhere to put it. */
    private static String sharedIdParagraph(OpenSpecInput in) {
        return "This repository is one slice of a change that spans " + in.siblingRepos().size()
                + " repositor" + (in.siblingRepos().size() == 1 ? "y" : "ies") + ", tracked under "
                + "the shared change id `" + in.changeId() + "`. Every repository it touches "
                + "carries a change directory with that same id; the shared id is the only link "
                + "between them.\n";
    }

    private static String why(OpenSpecInput in) {
        return prose(in.goal());
    }

    /**
     * What a capability is FOR — deliberately not the spec's goal.
     *
     * <p>OpenSpec uses a delta's {@code ## Purpose} to seed the main spec of a capability this
     * change creates, so the text becomes the capability's durable description. The spec's goal is
     * a problem statement ("tier updates do not take effect until a restart") and would be stale
     * the moment the change lands, permanently, in someone else's repository.
     *
     * <p>sdd does not know the durable purpose of a behaviour area in a repository it is only
     * visiting. So this says what is true — the area's name, the repo, and where the text came
     * from — and asks to be replaced. OpenSpec's own behaviour when Purpose is absent is a
     * {@code TBD} placeholder, so an honest placeholder is in keeping rather than a novelty.
     */
    private static String capabilityBlurb(OpenSpecInput in, String capability) {
        return "The `" + capability + "` behaviour of `" + in.repo() + "`. This description was "
                + "generated from sdd specification `" + in.specId() + "` and should be replaced "
                + "with a durable statement of what this capability is for.";
    }

    private static String consumersSuffix(OpenSpecInput.Contract contract) {
        if (contract.consumers().isEmpty()) {
            return "";
        }
        List<String> quoted = contract.consumers().stream().map(c -> "`" + c + "`").toList();
        return " consumed by " + String.join(", ", quoted);
    }

    private static String repoList(OpenSpecInput in) {
        List<String> names = new ArrayList<>();
        for (String repo : in.siblingRepos()) {
            names.add("`" + repo + "`" + (repo.equals(in.repo()) ? " (this one)" : ""));
        }
        return names.isEmpty() ? "`" + in.repo() + "` (this one)" : String.join(", ", names);
    }

    private static String orderLine(OpenSpecInput in) {
        List<String> units = new ArrayList<>();
        for (List<String> unit : in.order()) {
            units.add(String.join(" + ", unit.stream().map(r -> "`" + r + "`").toList()));
        }
        return String.join(" -> ", units);
    }

    // ---------------------------------------------------------------- design.md

    /** Null when there is nothing to decide — {@code design.md} is optional, and a rebuild-only
     *  repo that emits one is claiming a decision it did not make. */
    private static String design(OpenSpecInput in) {
        if (in.rebuildOnly()) {
            return null;
        }
        if (in.provides().isEmpty() && in.consumes().isEmpty() && in.constraints().isEmpty()
                && in.outOfScope().isEmpty() && in.openQuestions().isEmpty()) {
            return null;
        }
        StringBuilder md = new StringBuilder();
        md.append("## Context\n");
        md.append("This is one repository's slice of `").append(in.changeId()).append("`. ")
                .append(prose(in.goal())).append('\n');
        if (!in.background().isBlank()) {
            md.append('\n').append(prose(in.background())).append('\n');
        }

        md.append("\n## Goals / Non-Goals\n\n**Goals:**\n");
        if (in.covers().isEmpty()) {
            md.append("- Rebuild against the changed dependency without behaviour change.\n");
        } else {
            in.covers().forEach(item ->
                    md.append("- ").append(item.id()).append(": ").append(inline(item.text()))
                            .append('\n'));
        }
        md.append("\n**Non-Goals:**\n");
        List<String> nonGoals = new ArrayList<>();
        in.outOfScope().forEach(o -> nonGoals.add(inline(o)));
        in.requirementOwners().forEach((requirement, owner) -> {
            if (!owner.equals(in.repo())) {
                nonGoals.add(requirement + ", which `" + owner + "` covers in this change");
            }
        });
        if (nonGoals.isEmpty()) {
            nonGoals.add("Anything not named in the goals above.");
        }
        nonGoals.forEach(n -> md.append("- ").append(n).append('\n'));

        if (!in.provides().isEmpty() || !in.consumes().isEmpty()) {
            md.append("\n## Decisions\n");
            in.provides().forEach(c -> appendContract(md, in, c, true));
            in.consumes().forEach(c -> appendContract(md, in, c, false));
        }

        List<String> risks = new ArrayList<>();
        for (OpenSpecInput.Contract contract : in.provides()) {
            if (contract.compat() != null && !contract.compat().isBlank()) {
                risks.add("`" + contract.id() + "` is declared " + contract.compat()
                        + ": consumers build against this repository, so removing or re-signing an "
                        + "existing public member breaks them.");
            }
        }
        in.constraints().forEach(c -> risks.add(c.id() + ": " + inline(c.text())));
        if (!"none".equals(in.versionAction())) {
            risks.add("Release action `" + in.versionAction()
                    + "`: consumers must be re-pinned once this lands.");
        }
        if (!risks.isEmpty()) {
            md.append("\n## Risks / Trade-offs\n");
            risks.forEach(r -> md.append("- ").append(r).append('\n'));
        }

        List<String> questions = new ArrayList<>();
        in.openQuestions().forEach(q -> questions.add(q.id() + ": " + inline(q.text())));
        for (OpenSpecInput.Item requirement : in.covers()) {
            if (scenarioSource(in, requirement) == Source.BACKSTOP) {
                questions.add("No acceptance criterion covers " + requirement.id()
                        + "; its scenario below is a placeholder and should be replaced with a "
                        + "checkable one.");
            }
        }
        md.append("\n## Open Questions\n");
        if (questions.isEmpty()) {
            md.append("- None recorded.\n");
        } else {
            questions.forEach(q -> md.append("- ").append(q).append('\n'));
        }
        return md.toString();
    }

    private static void appendContract(StringBuilder md, OpenSpecInput in,
                                       OpenSpecInput.Contract contract, boolean provided) {
        md.append("\n### `").append(contract.id()).append("` — ").append(contract.kind());
        if (contract.compat() != null && !contract.compat().isBlank()) {
            md.append(", ").append(contract.compat());
        }
        md.append(provided ? ", provided" : ", consumed");
        if (provided && !contract.consumers().isEmpty()) {
            md.append(" to ").append(String.join(", ",
                    contract.consumers().stream().map(c -> "`" + c + "`").toList()));
        } else if (!provided && contract.provider() != null && !contract.provider().isBlank()) {
            md.append(" from `").append(contract.provider()).append('`');
        }
        md.append('\n');
        if (contract.body() != null && !contract.body().isBlank()) {
            md.append("\n```\n").append(Markdown.neutralizeFences(contract.body()).strip())
                    .append("\n```\n");
        }
        if (!contract.declared().isEmpty()) {
            md.append("\nDeclared members, re-extracted from the implementation and checked:\n");
            contract.declared().forEach(d ->
                    md.append("- `").append(inline(d)).append("`\n"));
        }
    }

    // ---------------------------------------------------------------- tasks.md

    private static String tasks(OpenSpecInput in) {
        Map<String, List<String>> groups = new LinkedHashMap<>();

        List<String> upstream = new ArrayList<>();
        for (OpenSpecInput.Contract contract : in.consumes()) {
            if (contract.provider() != null && !contract.provider().isBlank()) {
                upstream.add("Wait for `" + contract.provider() + "` to land `" + contract.id()
                        + "` in `" + in.changeId() + "` — this repository consumes it");
            }
        }
        groups.put("Upstream", upstream);

        List<String> contracts = new ArrayList<>();
        for (OpenSpecInput.Contract contract : in.provides()) {
            contracts.add("Expose `" + contract.id() + "` (" + contract.kind() + ")"
                    + consumersSuffix(contract));
            contract.declared().forEach(d -> contracts.add("Provide `" + inline(d) + "`"));
            if (contract.compat() != null && !contract.compat().isBlank()) {
                contracts.add("Keep `" + contract.id() + "` " + contract.compat()
                        + " — do not remove or re-sign existing public members");
            }
        }
        groups.put("Contracts", contracts);

        List<String> implementation = new ArrayList<>();
        in.covers().forEach(item ->
                implementation.add(item.id() + ": " + inline(item.text())));
        in.files().forEach(f -> implementation.add("Change `" + inline(f) + "`"));
        in.bumps().forEach(b -> implementation.add(inline(b)));
        groups.put("Implementation", implementation);

        List<String> verification = new ArrayList<>();
        in.verification().forEach(v -> verification.add("Run `" + inline(v) + "`"));
        allocatedAcceptance(in).forEach(item ->
                verification.add(item.id() + ": " + inline(item.text())));
        groups.put("Verification", verification);

        List<String> release = new ArrayList<>();
        if (!"none".equals(in.versionAction())) {
            release.add("Apply a `" + in.versionAction() + "` version bump");
        }
        groups.put("Release", release);

        StringBuilder md = new StringBuilder();
        int number = 0;
        for (Map.Entry<String, List<String>> group : groups.entrySet()) {
            if (group.getValue().isEmpty()) {
                continue;   // skipped, never emitted empty — an empty group is untracked noise
            }
            number++;
            if (number > 1) {
                md.append('\n');
            }
            md.append("## ").append(number).append(". ").append(group.getKey()).append("\n\n");
            int item = 0;
            for (String task : group.getValue()) {
                item++;
                md.append("- [ ] ").append(number).append('.').append(item).append(' ')
                        .append(task).append('\n');
            }
        }
        if (number == 0) {
            md.append("## 1. Verification\n\n- [ ] 1.1 Rebuild this repository and confirm it is "
                    + "green\n");
        }
        return md.toString();
    }

    /** Every acceptance item any of this step's requirements is allocated, deduplicated. */
    private static List<OpenSpecInput.Item> allocatedAcceptance(OpenSpecInput in) {
        Set<String> ids = new LinkedHashSet<>();
        in.covers().forEach(r -> ids.addAll(in.plan().acceptanceFor()
                .getOrDefault(r.id(), List.of())));
        List<OpenSpecInput.Item> out = new ArrayList<>();
        for (OpenSpecInput.Item item : in.acceptance()) {
            if (ids.contains(item.id())) {
                out.add(item);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- delta spec

    private static String delta(OpenSpecInput in, String capability) {
        StringBuilder md = new StringBuilder();
        md.append("# ").append(capability).append("\n\n");
        // Seeds the Purpose of a main spec this change creates, and is ignored when the capability
        // already exists — so it is always safe to emit and never overwrites a human's text.
        md.append("## Purpose\n").append(capabilityBlurb(in, capability)).append("\n\n");
        md.append("## ADDED Requirements\n");
        for (OpenSpecInput.Item requirement : in.covers()) {
            md.append("\n### Requirement: ").append(requirementName(requirement)).append('\n');
            // The body opens with SHALL so the whole-word check passes on the BODY, which is what
            // --strict actually inspects — a SHALL in the header alone is still a warning.
            md.append('`').append(in.repo()).append("` SHALL satisfy ").append(requirement.id())
                    .append(" of `").append(in.specId()).append("`: ")
                    .append(inline(requirement.text())).append("\n\n");
            md.append("Traceability: sdd spec `").append(in.specId()).append("`, plan version ")
                    .append(in.planVersion()).append(", requirement ").append(requirement.id())
                    .append(".\n");
            for (String scenario : scenarios(in, requirement)) {
                md.append('\n').append(scenario);
            }
        }
        return md.toString();
    }

    private static String requirementName(OpenSpecInput.Item requirement) {
        String text = inline(requirement.text());
        int stop = text.indexOf('.');
        String name = truncate(stop > 0 ? text.substring(0, stop) : text);
        return name.isBlank() ? requirement.id() : name;
    }

    /** Which rung of the ladder produced this requirement's scenarios. */
    private enum Source { ALLOCATED, OVERLAP, VERIFICATION, BACKSTOP }

    private static Source scenarioSource(OpenSpecInput in, OpenSpecInput.Item requirement) {
        if (!in.plan().acceptanceFor().getOrDefault(requirement.id(), List.of()).isEmpty()) {
            return Source.ALLOCATED;
        }
        if (!overlapping(in, requirement).isEmpty()) {
            return Source.OVERLAP;
        }
        return in.verification().isEmpty() ? Source.BACKSTOP : Source.VERIFICATION;
    }

    /**
     * At least one scenario, always. OpenSpec rejects an ADDED requirement without one, so the
     * ladder's last rung is tautological rather than absent — a placeholder a human can see and
     * replace beats a change that will not validate.
     */
    private static List<String> scenarios(OpenSpecInput in, OpenSpecInput.Item requirement) {
        List<String> out = new ArrayList<>();
        switch (scenarioSource(in, requirement)) {
            case ALLOCATED -> {
                for (String id : in.plan().acceptanceFor().get(requirement.id())) {
                    textOf(in, id).ifPresent(text -> out.add(scenario(in, id, text, null)));
                }
            }
            case OVERLAP -> {
                for (OpenSpecInput.Item item : overlapping(in, requirement)) {
                    out.add(scenario(in, item.id(), item.text(),
                            "Derived by term overlap, not allocated by a human — check it."));
                }
            }
            case VERIFICATION -> {
                StringBuilder s = new StringBuilder("#### Scenario: verification\n");
                s.append("- **WHEN** `").append(in.specId()).append("` is implemented in `")
                        .append(in.repo()).append("`\n");
                for (String command : in.verification()) {
                    s.append("- **THEN** `").append(inline(command)).append("` passes\n");
                }
                out.add(s.toString());
            }
            case BACKSTOP -> out.add("#### Scenario: " + requirement.id() + " is satisfied\n"
                    + "- **WHEN** `" + in.specId() + "` is implemented in `" + in.repo() + "`\n"
                    + "- **THEN** the behaviour " + requirement.id() + " requires holds\n");
        }
        if (out.isEmpty()) {
            // Unreachable via scenarioSource, but the hard error this guards is silent until
            // somebody runs the validator, so the guard stays.
            out.add("#### Scenario: " + requirement.id() + " is satisfied\n"
                    + "- **WHEN** `" + in.specId() + "` is implemented in `" + in.repo() + "`\n"
                    + "- **THEN** the behaviour " + requirement.id() + " requires holds\n");
        }
        return out;
    }

    private static String scenario(OpenSpecInput in, String id, String text, String caveat) {
        StringBuilder s = new StringBuilder("#### Scenario: ").append(id).append(" — ")
                .append(shortName(text)).append('\n');
        s.append("- **WHEN** `").append(in.specId()).append("` is implemented in `")
                .append(in.repo()).append("`\n");
        s.append("- **THEN** ").append(inline(text)).append('\n');
        if (caveat != null) {
            s.append("- **AND** ").append(caveat).append('\n');
        }
        return s.toString();
    }

    private static String shortName(String text) {
        String name = inline(text);
        int stop = name.indexOf('.');
        if (stop > 0) {
            name = name.substring(0, stop);
        }
        return truncate(name);
    }

    /** Cuts at the last word boundary inside the limit — a heading ending mid-word reads as damage
     *  rather than as a summary. */
    private static String truncate(String text) {
        if (text.length() <= MAX_NAME_CHARS) {
            return text.strip();
        }
        String cut = text.substring(0, MAX_NAME_CHARS);
        int space = cut.lastIndexOf(' ');
        return (space > MAX_NAME_CHARS / 2 ? cut.substring(0, space) : cut).strip() + "…";
    }

    private static java.util.Optional<String> textOf(OpenSpecInput in, String id) {
        return in.acceptance().stream().filter(a -> a.id().equals(id))
                .map(OpenSpecInput.Item::text).findFirst();
    }

    /**
     * Acceptance items sharing an identifier-ish term with the requirement. The same shape the
     * drafter's evidence ranking uses — but note it is weaker here: there it ranks, and a wrong
     * order costs nothing, whereas here it allocates. That is why anything it produces is labelled
     * in the rendered scenario.
     */
    private static List<OpenSpecInput.Item> overlapping(OpenSpecInput in,
                                                        OpenSpecInput.Item requirement) {
        Set<String> terms = tokens(requirement.text());
        List<OpenSpecInput.Item> out = new ArrayList<>();
        if (terms.isEmpty()) {
            return out;
        }
        for (OpenSpecInput.Item item : in.acceptance()) {
            Set<String> other = tokens(item.text());
            other.retainAll(terms);
            if (!other.isEmpty()) {
                out.add(item);
            }
        }
        return out;
    }

    private static Set<String> tokens(String text) {
        Set<String> out = new LinkedHashSet<>();
        for (String token : text.toLowerCase(Locale.ROOT).split("[^a-z0-9_]+")) {
            if (token.length() >= 3) {
                out.add(token);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- sanitizers

    /** One line, no leading '#', no fence — the anti-forgery rule plan.md already applies. */
    private static String inline(String value) {
        if (value == null) {
            return "";
        }
        return Markdown.neutralizeFences(
                value.replaceAll("(?U)\\s+", " ").strip().replaceAll("^#+\\s*", ""));
    }

    /** Keeps its lines, but loses every structural marker this renderer owns. */
    private static String prose(String value) {
        if (value == null) {
            return "";
        }
        return Markdown.neutralizeFences(value.replaceAll("(?m)^\\s*#+\\s*", "")
                .replaceAll("(?m)^---\\s*$", "—"))
                .strip();
    }

    private static String shortSha(String sha) {
        return sha.length() <= 8 ? sha : sha.substring(0, 8);
    }
}
