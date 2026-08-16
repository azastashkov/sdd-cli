# Local Atlassian Data Center rig

The stand-in for the closed corporate network `sdd`'s Jira/Confluence ingestion and Bitbucket
source control (Tasks 1–5) were built against, but never yet run against a real Atlassian
instance. This is the runbook a human follows, in order, to bring the rig up and prove the
whole feature end to end. Steps marked **HUMAN** cannot be scripted — they need an interactive
Atlassian account and a browser.

Nothing in `scripts/atlassian-dc/` or `scripts/bitbucket-dc/` starts a container, obtains a
licence, or performs the live run itself. Those scripts write the fixtures and wire the
credentials; a human still has to run them and read what they print.

## 1. Bring up the rig — **HUMAN** (docker) + scripted (config)

```
cd scripts/atlassian-dc
docker compose up -d
docker compose ps       # wait for all three to report healthy — first boot can take several
                         # minutes per product; see docker-compose.yml's healthcheck comments
```

## 2. Obtain three 30-day evaluation licences — **HUMAN, browser required**

Go to <https://my.atlassian.com>, generate a free Data Center evaluation licence for each of
Jira Software, Confluence, and Bitbucket. This is an interactive Atlassian account action and
cannot be scripted.

## 3. Run the three setup wizards — **HUMAN, browser required**

Visit each product and click through its one-time setup wizard: choose "Data Center", let it
find the Postgres `docker-compose.yml` already wired up (host/user/password are pre-filled via
environment variables — nothing to type there), paste the licence key from step 2, and create
the first admin account. **Use the same admin username and password for all three** — the
scripts below assume one shared admin identity.

| Product    | URL                     |
|---|---|
| Jira       | <http://localhost:8080> |
| Confluence | <http://localhost:8090> |
| Bitbucket  | <http://localhost:7990> |

This work lives in the named volumes `docker-compose.yml` declares — a `docker compose down`
(without `-v`) or a host reboot does not lose it. Only `docker compose down -v` would; no script
here ever passes `-v`.

## 4. Mint tokens — scripted

```
./issue-tokens.sh
```

Prompts for the admin username/password (or reads `ATLASSIAN_ADMIN_USER`/
`ATLASSIAN_ADMIN_PASSWORD` from the environment), mints a Jira PAT, a Confluence PAT, and a
Bitbucket access token, and appends them to `~/.zshrc` as `JIRA_PAT` / `CONFLUENCE_PAT` /
`BITBUCKET_PAT` inside a clearly delimited, idempotent block. Backs up `~/.zshrc` first and
prints the backup path. Never prints a token in full.

## 5. Reload your shell — **HUMAN**

```
source ~/.zshrc
```

(or open a new terminal). `sdd doctor` reads these as plain environment variables and cannot see
them until this happens.

## 6. Check connectivity

```
sdd doctor
```

All three `atlassian:*` lines should read `HTTP 200 as <user>`. If any fails, fix it before
continuing — every later step depends on all three being reachable.

## 7. Mirror the estate into Bitbucket — scripted

```
../bitbucket-dc/mirror.sh <workspace-dir>
```

`<workspace-dir>` is the directory holding one checkout of every repo in the estate (the same
directory `sdd.yml`'s `--workspace` points at). Discovers the estate exactly the way
`WorkspaceScanner` does — every directory directly under `<workspace-dir>` with a `.git` entry —
creates the `TRADING` Bitbucket project and a same-named repo per checkout, mirrors every branch
and tag from each checkout's GitHub origin, and re-points `origin` at Bitbucket (preserving the
original as a `github` remote). Prints the full plan and asks for confirmation before the first
write (`--yes` skips the prompt). Re-runnable — existing Bitbucket projects/repos are reused.

## 8. Seed fixture data — scripted

```
./seed.sh --repo <one-of-the-mirrored-repo-names>
```

Default `--repo` is `order-service` — override it to whatever repo in your estate you want the
seeded spec to be about. Creates a Confluence space + page with real spec prose about that repo,
a Jira project + issue (`PROJ-1`) whose description links the page as a **named hyperlink**
*and* a Jira remote link to the same page (both discovery channels), a subtask, an "is blocked
by" linked issue, and a comment. Then re-fetches everything through the same REST calls `sdd`'s
own Java clients make, and writes the raw JSON as the new fixtures under
`sdd-plan/src/test/resources/{jira,confluence}/` and `sdd-cli/src/test/resources/bitbucket/`,
replacing the Task 3/5 hand-written guesses. Prints exactly which files it wrote.

## 9. Configure `sdd.yml`

Copy the `atlassian:` block from `sdd.yml.example` into `<workspace>/sdd.yml`, uncommented, with:

```yaml
atlassian:
  jira:
    base_url: http://localhost:8080
    token: ${JIRA_PAT}
  confluence:
    base_url: http://localhost:8090
    token: ${CONFLUENCE_PAT}
  bitbucket:
    base_url: http://localhost:7990
    token: ${BITBUCKET_PAT}
    project: TRADING
  write_back: comment
  pull_requests: true
```

## 10. The end-to-end run — **HUMAN**, verify each step

This is what proves the feature works. Run each command from `<workspace-dir>`, in order, and
check the thing named:

1. `sdd doctor` — all three `atlassian:*` probes report `HTTP 200 as <user>`.
2. `sdd index` — over the checkouts, whose `origin` is now Bitbucket (step 7 re-pointed it).
3. `sdd plan PROJ-1` → `PROJ-1.spec.md`. **Check by hand:**
   - Requirements drawn from BOTH the Jira ticket AND the Confluence page (the page's
     Requirements/Acceptance-criteria prose should show up, not just the ticket summary).
   - The subtask and the "is blocked by" issue are both present.
   - A `## Sources` section lists every fetched document with its version.
   - Anything unfollowed (over a cap, unresolvable, wrong host) sits in Open Questions, not
     silently missing.
4. Edit the spec by hand → `sdd plan` → `sdd plan approve`. **A comment appears on PROJ-1.**
5. `sdd implement` → run branches exist locally, **and nothing has been pushed yet** — verify
   this explicitly (e.g. `git -C <repo> log origin/<branch>..<branch>` on Bitbucket's `origin`
   should show the branch does not exist there yet, or check Bitbucket's UI directly). This
   invariant — nothing touches the network before Gate 2 — is deliberate; see the design doc's
   §0 amendment.
6. `sdd review` → `report.md`, one open Bitbucket PR per succeeded repo, a second Jira comment.
7. `sdd review approve <repo> ...` → the PR is merged. `reject` → the PR is declined. `redo` →
   the PR is still open (not merged, not declined).
8. `sdd clean --force` — note what it does (deletes local run branches/run dir for anything not
   approved) and does **not** do (nothing to the Bitbucket branches or PRs it already
   merged/declined — those are Bitbucket-side state this command never touches).
9. Negative cases — each should fail with a clear, specific message, not a stack trace:
   - `unset JIRA_PAT` then `sdd plan PROJ-1` — should name `$JIRA_PAT` in the error.
   - A nonexistent issue key (`sdd plan PROJ-9999`).
   - A Confluence link the token cannot read (permission-restricted space).
   - `atlassian:` absent from `sdd.yml` entirely — every other command must behave exactly as
     it did before this feature existed.
   - Bitbucket stopped mid-`sdd review` (`docker compose stop bitbucket`) — `report.md` should
     still be written, with a `warn: bitbucket: ...` line, exit code unchanged.
10. Regressions — must be byte-for-byte unchanged from before this feature:
    - `sdd plan --text "..."` and `sdd plan SPEC-101.md` (a plain markdown spec, no Jira/
      Confluence involved).
    - A `plan.json`/`report.md` run with no `bitbucket:` block in `sdd.yml` — Gate 2 must
      produce exactly what it did before Task 5.

## Least-certain API shapes — check these first

Every one of these was implemented from Atlassian's documented REST shapes, not verified
against a live Data Center instance (that is exactly what this rig is for). If the live run
disagrees with sdd's behaviour, start here — these are the specific guesses most likely to be
wrong, gathered from Task 3's and Task 5's own reports plus two new ones this task's `seed.sh`
had to guess at.

**From Task 3 (Jira/Confluence clients — `sdd-plan/src/main/java/sdd/plan/{jira,confluence}/`):**

1. `renderedFields.comment.comments[]` carrying `id`/`updated` alongside the rendered `body` —
   assumed to mirror the base comment shape. If wrong: a comment's Sources bullet reads "updated
   unknown" and its doc id ends in `-comment-` (empty).
2. `fields.updated`'s exact date format (`yyyy-MM-dd'T'HH:mm:ss.SSSZ`) — instance-configurable,
   not fixed by the API. Falls back through ISO-offset parsing, then the raw string, so a
   mismatch degrades (an un-normalized timestamp) rather than fails.
3. Whether jsoup's `element.attr("href")` returns the literal attribute value, unresolved,
   against Data Center's actual rendered HTML — assumed yes (a relative href stays relative and
   gets filtered for having no host).
4. `/rest/api/content?spaceKey=...&title=...&expand=version`'s response shape (used for the
   `/display/SPACE/Title` Confluence URL resolution path) — modeled as
   `{"results": [...], "size": N}` with `results[0].id`, not confirmed against a live response.
5. `/x/AbCd` tiny-link redirects being single-hop and same-origin — the 5-hop cap is defensive,
   not evidence-based.
6. Jira `issuelinks[].type` field names (`name`/`inward`/`outward`) and that a "blocking" link
   type's name literally contains "block"/"depend" — standard shape, but admin-configurable per
   instance and could differ from this rig's own seeded "Blocks" type.
7. Whether `renderedFields.description`/comment bodies are the exact same HTML string both the
   `a[href]` harvest and `ConfluenceExtract.extract` see — assumed yes (one field, two readers),
   not verified live.

**From Task 5 (Bitbucket — `sdd-cli/src/main/java/sdd/cli/review/{BitbucketClient,RemoteGit}.java`):**

8. The PR `version` field's presence on every PR-shaped response (`create`, `get`,
   `findOpenBySourceBranch`, and required as a query param on `merge`/`decline`) — matches
   Bitbucket's documented optimistic-locking behaviour, not confirmed live.
9. `merge`/`decline` taking `version` as a QUERY parameter with an empty body, rather than a
   JSON body field — the brief's exact wording; some API references show the JSON-body form
   instead.
10. The clone-URL form lowercasing BOTH `project` and `repo` (`RemoteGit.cloneUrl`), while the
    REST `{projectKey}` path parameter (`BitbucketClient`) is used exactly as configured,
    uppercase key included — the single most likely divergence point from a live instance; the
    two classes' differing treatment of `project` is itself worth re-checking, not just each
    half separately.
11. `create`'s `fromRef`/`toRef` shape (`{id, repository: {slug, project: {key}}}`) — matches
    Bitbucket Server's documented shape from memory, not verified live.
12. `default-branch`'s response field being `displayId` (unprefixed), not `id`
    (`refs/heads/...`) — if wrong, `defaultBranch` throws a clear error naming the response
    rather than silently returning the wrong branch.
13. The git-over-HTTP PAT username (`"x-token-auth"`, `BitbucketClients.GIT_USERNAME`) —
    Bitbucket's PAT auth is documented as token-carries-identity, so any non-empty placeholder
    should work, but this is unverified. `mirror.sh` uses the same placeholder for exactly this
    reason — if it needs to change, change it in both places.
14. `findOpenBySourceBranch` assuming at most one open PR per source branch — the first result
    of the filtered list is returned unconditionally.
15. The pagination envelope shape (`{"size", "isLastPage", "values": [...]}`) for the PR list
    endpoint — standard Bitbucket Server shape, not verified live; `findOpenBySourceBranch` never
    paginates past the first page.
16. Proxy resolution (`atlassian.proxy`) reads only `host`/`port`/`no_proxy`, never proxy
    authentication — if a live corporate proxy requires its own auth, both the REST calls and
    the git push need that added; not specific to this rig, but worth checking here too.

**New in this task (`scripts/atlassian-dc/seed.sh`, `scripts/atlassian-dc/issue-tokens.sh`):**

17. Jira Server/DC project creation's exact body shape
    (`POST /rest/api/2/project` with `projectTypeKey: "software"`,
    `projectTemplateKey: "com.pyxis.greenhopper.jira:gh-simplified-agility-kanban"`, `lead`) —
    this is the best-documented shape available, but genuinely unverified; if it 400s, that is
    the first thing to fix in `seed.sh`.
18. Bitbucket's access-token creation body including an `expiryDays` field
    (`PUT /rest/access-tokens/1.0/users/{user}`) — the brief only names the endpoint, not the
    body; `expiryDays` is this script's own guess. If the live instance rejects it, drop the
    field from `issue_bitbucket_token` in `issue-tokens.sh` and mint a token good until revoked.
