# Atlassian live verification — a runbook

Nothing in this codebase has ever reached a real Jira, Confluence or Bitbucket Data Center
instance. Everything is WireMock fixtures cross-checked against Atlassian's documentation
(`docs/commands.md`, "what's verified, what's assumed"). This document is the staged run that
replaces the guesses with evidence.

**Read it before running it.** Steps S1–S8 and S10 are read-only. **S9 writes a comment to a real
ticket** and has its own gate.

## Before you travel to the network

These landed on 2026-08-20 and exist so the run below is diagnosable at all:

- **`sdd plan --fetch-only <ref>`** — fetch the sources, print their provenance, sizes and notes,
  then stop. No model is called and nothing is written, so Jira and Confluence can be exercised
  independently of the model gateway and of everything downstream.
- **`SDD_ATLASSIAN_DUMP=1`** — record every Atlassian request and response as JSONL to
  `.sdd/atlassian-wire.jsonl` (already gitignored), including allowlisted response headers.
  **Set it for every step below, and read the dump before interpreting any result.** The last
  integration against a closed corporate gateway killed five plausible theories, each supported by
  a clean-looking table, before anyone printed the bytes.

Prerequisite: a **scratch Jira project** and a **scratch Confluence space**, populated with
deliberately invented content. That is what makes the captured responses safe to commit as
fixtures by construction (§Fixtures), rather than safe only if somebody remembers to scrub them.

## The steps

Each names the command, what success looks like, and the failure it is designed to catch.

### S1 — `sdd doctor --report`  · gate

```
sdd doctor --report
```
Expect `[ OK ] atlassian:jira — https://… → HTTP 200 as <name>` and the Confluence equivalent.
Catches the truststore chain, the proxy, and expired or mis-scoped PATs.

**Nothing proceeds until this is green.** Everything downstream assumes transport works.

Known gap worth fixing while you are here: `AtlassianProbe.run` builds its `RestClient` without a
`TransportContext`, so a proxy-shaped failure here is reported with *less* enrichment than the same
failure during `sdd plan`.

### S2 — one Jira issue, no links, no comments

```
sdd plan --fetch-only SCRATCH-1
sdd plan --fetch-only SCRATCH-NOSUCH      # expect: error: Jira issue SCRATCH-NOSUCH not found
```
Expect one `jira SCRATCH-1 updated <ISO-8601 UTC> <url>` bullet and the description text.

Catches: whether `expand=renderedFields` returns HTML at all (an empty description means it does
not, or is configured off); whether `fields.updated` matches the assumed
`yyyy-MM-dd'T'HH:mm:ss.SSSZ` — a bullet showing an unnormalized timestamp means the instance
differs, which degrades gracefully rather than throwing. The 404 case confirms the `" HTTP 404:"`
string match still holds against the real error body.

### S3 — the dense one

Construct **one** issue with: subtasks, a blocking link, a non-blocking link, at least two
comments, a remote link, and **a named hyperlink to a Confluence page in the description**.

```
sdd plan --fetch-only SCRATCH-2
```
Catches, in a single command:
- whether `renderedFields.comment.comments[]` carries `id` and `updated` — if it does not, the
  values now come from `fields.comment.comments[]` positionally, so check the bullets are still
  well-formed rather than assuming;
- **the link-type note**: `issue link types on SCRATCH-2: …(followed)/(not followed)`. On a
  localised instance nothing matches the `block`/`depend` substring test and the note is the only
  thing that says so;
- **the comment-pagination note**: `only N of M comments read`;
- **relative hrefs** — the hyperlink must appear either as a fetched page or as a note. Silence
  means it was dropped, which is the bug fixed on 2026-08-20; report it if it recurs;
- remote links, and that "the same HTML reaches both readers" (the dump settles it in one run).

### S4 — a Confluence page by `/pages/`

```
sdd plan --fetch-only 'https://confluence.corp.local/pages/viewpage.action?pageId=65601'
```
Choose a page containing a table, a code macro, an image, an internal `ac:link`, and an
`include`/`excerpt-include` macro. Compare the extracted text against the page as rendered in a
browser and **write down what is missing** — that list is the real severity of the macro gap
(`ConfluenceExtract` descends into `<ac:parameter>` for a macro with no `ac:plain-text-body`, so an
include renders its parameter values as prose while the content is absent; and an `ac:link`/
`ri:page` has no href and no visible text, so it is invisible to both the extractor and the
harvester).

### S5 — the legacy `viewpage.action` shape

```
sdd plan --fetch-only 'https://confluence.corp.local/viewpage.action?pageId=65601'
```
Should now classify as a Confluence page. Before 2026-08-20 this reported the URL as a missing
markdown **file**.

### S6 — `/display/{space}/{title}`

```
sdd plan --fetch-only 'https://confluence.corp.local/display/ENG/Order+API+spec'
```
Repeat with a title containing a non-ASCII character, one containing `+`, and one containing `%`.
The last two were a silent wrong-title search and an uncaught `IllegalArgumentException` before
2026-08-20.

### S7 — a `/x/` tiny link

```
sdd plan --fetch-only 'https://confluence.corp.local/x/AbCd'
```
Read the dump for the **actual** hop count, whether `Location` came back absolute or relative, and
whether `hostMatches` accepted it. This is the exchange a corporate proxy most often rewrites, and
the probe uses `BodyHandlers.discarding()`, so the response headers in the dump are the entire
record.

Confirmed correct by inspection: `HttpClients` never calls `followRedirects`, so the JDK default
`NEVER` applies and the 3xx is genuinely seen.

### S8 — harvesting, depth, and the budget

```
sdd plan --fetch-only SCRATCH-2                 # atlassian.follow_depth: 1
# raise follow_depth to 2 in sdd.yml, repeat
```
Then point a link at a genuinely huge page (>300k extracted chars): expect **one note**, run
continues. Before 2026-08-20 this ended the whole run. Then link enough pages to exceed
`SourceBudget.MAX_TOTAL_CHARS` and confirm comments are dropped before pages.

### S9 — write-back · **MUTATES A REAL TICKET**

The gate is this checklist, not a code prompt — `sdd plan approve` must stay non-interactive.

1. The target key is a **scratch** issue. Open it in a browser and confirm.
2. The spec's `## Sources` names only that scratch issue.
3. Dry run: `sdd plan approve <spec>.plan.md --no-comment` → exit 0, and **no** `commented on …`
   line printed.
4. Only then set `atlassian.write_back: comment` and re-run without `--no-comment`.

```
sdd plan approve SCRATCH-2.spec.plan.md
```
Expect `commented on SCRATCH-2`, and the comment visible in the browser. **Check stderr**: a failed
post warns and deliberately never changes the exit code, so a green exit does not mean the comment
landed.

### S10 — the full chain

```
sdd plan SCRATCH-2                      # → SCRATCH-2.spec.md, then STOPS
#   ... a human edits SCRATCH-2.spec.md ...
sdd plan SCRATCH-2.spec.md              # → impact analysis + SCRATCH-2.spec.plan.md
sdd plan approve SCRATCH-2.spec.plan.md
```
The first command must print `review and edit the spec, then run: sdd plan …` and must **not** run
impact analysis. That human gate is real: `normalizeWithAtlassian` always ends at `writeNormalized`.

Catches the model call, and the `SpecRenderer.render` → `SpecParser.parse` self-check — a Cyrillic
or emoji-bearing Jira summary is the likeliest thing to break a round-trip that has only ever seen
ASCII fixtures.

## Bitbucket

The PR path is **out of scope**: `pull_requests: false` is the default, it is not part of "task
context = codebase + Jira/Confluence", and every remaining assumption needs a mutation on a real
repository. Three read-only checks are worth doing anyway:

1. Configure the site — `sdd doctor` already probes Bitbucket, so auth and TLS come free.
2. `git ls-remote https://x-token-auth:$BITBUCKET_API_KEY@host/scm/proj/repo.git` proves the
   git-over-HTTP PAT username.
3. **The same command with the project segment lowercased** proves whether `/scm/` is case-folded —
   the item both `commands.md` and `runbook.md` rank "check this first", whose documented symptom
   is a confusing partial failure. One command, no mutation.

## Fixtures

**Re-derive the SHAPE from real responses; keep the CONTENT synthetic.** A scratch project and
space populated with invented text makes every capture publishable by construction, so no scrubbing
step can be forgotten. Fall back to scrubbing a real response only for values you cannot fabricate —
realistically just the localised link-type names and the macro inventory, both of which can be
transplanted into a synthetic fixture by hand.

**Never commit the raw dump.** `.sdd/` is gitignored, which is why the dump defaults there.

Worth re-deriving: `jira/issue-with-subtasks-and-links.json` (real `type` names),
`jira/issue-with-comments.json` (the real `fields.comment` envelope — the one added on 2026-08-20 is
hand-written), `jira/remotelink.json` (Data Center's envelope carries `globalId`, `application`,
`relationship`; none are present today), `confluence/page.json` (real storage format with macros
both with and without `ac:plain-text-body`, `ri:page`, `ri:attachment`, a table),
`confluence/content-search-by-title.json`, a tiny-link header capture, and **context-path variants
of all of them**. Add a README beside them naming the product version and capture date: a fixture
whose provenance is unrecorded is back to being hand-written.

## What "done" means

Every contradiction the run finds produces **(a)** a re-derived fixture and **(b)** a WireMock test
that fails against the pre-fix code. A fix without a failing-first test proves nothing about the
next regression.

Then edit `docs/commands.md`'s "what's verified, what's assumed" section:

- Replace the preamble's "has ever been reachable" paragraph with a dated verification record:
  product versions, date, operator, network shape (proxy? authenticating? private CA?), and the
  exact `sdd` commit.
- **Bucket 1 is untouched.** Its claim — that WireMock proves sdd does what its authors think *when
  the server responds as assumed* — stays exactly as true and exactly as limited.
- Bucket 2 gains a verdict column: `CONFIRMED (live, <date>)` / `CONTRADICTED (live, <date>) — fixed
  in <commit>` / `NOT EXERCISED — <why>`.
- **Bucket 3 shrinks; it does not vanish.** Anything the run could not reach stays, with its reason
  downgraded but explicit. "This network did not require proxy authentication, so this remains
  unexercised" is a weaker and more useful claim than "nobody has ever tried", and it must never be
  allowed to read as *verified*.

Mirror the outcome into `docs/runbook.md`'s "First contact" list, and document any new config keys
in `sdd.yml.example`.
