# Atlassian Data Center integration — corporate-network runbook

The three products this integration talks to — Jira, Confluence, Bitbucket Data Center — already
exist inside the destination corporate network. **There is no local Atlassian rig, and there never
will be one built by this project**: the live-verification run once planned here — replacing every
hand-written WireMock fixture with a recording against a real instance, and settling every guessed
API shape — is cancelled. What stands in for it is `api-verification-report.md` (in the
requirements this branch was built from), which checked every invented request/response shape
against Atlassian's own Data Center documentation instead. See [`commands.md`](commands.md)'s
"Atlassian integration: what's verified, what's assumed" section for the full, honest breakdown;
this file is the step-by-step version — what to configure, what `sdd doctor` should say, the
sequence to run, and — the part that earns this document — what to check first the moment
something disagrees with what `sdd` expects.

**A note on the test fixtures.** The WireMock fixtures this repo's tests run against
(`sdd-plan/src/test/resources/{jira,confluence}/` and `sdd-cli/src/test/resources/bitbucket/`)
were hand-written from Atlassian's documented API shapes — none of them was ever recorded from a
live instance. If you gain access to a real Jira/Confluence/Bitbucket Data Center instance, the
single highest-value follow-up is re-recording these fixtures against it and settling every item
in section 4 below.

## 1. Configure `<workspace>/sdd.yml`

Copy the `atlassian:` block from `sdd.yml.example` into `<workspace>/sdd.yml`, uncommented, with
the corporate network's real shape:

```yaml
atlassian:
  tls:
    truststore: /etc/ssl/corp-ca.jks          # omit if the JDK's default trust store already
                                                # trusts these three hosts' certificates
    truststore_password: ${CORP_TRUSTSTORE_PASSWORD}   # omit if the truststore has no password
  proxy:
    host: proxy.corp.local                     # omit entirely if these hosts are reachable direct
    port: 8080
    no_proxy: [jira.corp.local, confluence.corp.local, bitbucket.corp.local]

  jira:
    base_url: https://jira.corp.local
    token: ${JIRA_API_KEY}
  confluence:
    base_url: https://confluence.corp.local
    token: ${CONFLUENCE_API_KEY}
  bitbucket:
    base_url: https://bitbucket.corp.local
    token: ${BITBUCKET_API_KEY}
    project: TRADING            # the Bitbucket project key repos live under, EXACT case
    default_reviewers: [alice, bob]

  write_back: comment           # none | comment — off by default; comment posts to Gate-1/Gate-2
  pull_requests: true           # off by default; true drives sdd review's Bitbucket push + PR
```

Each of the three sites is independently optional — declare only the ones this estate uses. The
`${JIRA_API_KEY}` / `${CONFLUENCE_API_KEY}` / `${BITBUCKET_API_KEY}` names above match what this
corporate environment exports from `~/.zshrc`; `sdd` itself parses whatever `${VAR}` name is
actually written in `sdd.yml`, so this is a naming convention this estate follows, not something
`sdd`'s code hard-codes anywhere (verify yourself with `grep -rn '"JIRA_API_KEY"\|"JIRA_PAT"'
sdd-core/src/main sdd-cli/src/main sdd-plan/src/main` — nothing matches; the variable name comes
from a regex match against the `${...}` reference in `ConfigLoader.parseAtlassianSite`).

**A note on `atlassian.bitbucket.project`'s case.** Type it exactly as Bitbucket has it configured
(conventionally uppercase, e.g. `TRADING`) — the REST API uses this value verbatim. See "First
contact" item 1 below for exactly why this specific field is the highest-risk value in this whole
block.

## 2. Run `sdd doctor` first

```
sdd doctor
```

On success, each configured site prints one line: `[ OK ] atlassian:jira — <base_url> → HTTP 200
as <name>` (Confluence and Bitbucket read the same way, with their own base URL and identity
field). A site with no `atlassian.<site>` block in `sdd.yml` simply prints no line at all —
`doctor`'s other output (java/config/database/model checks) is unaffected either way.

**Common failures and what they mean** — `doctor`'s messages are built to name the fix, not just
say "down":

| Symptom | Meaning | Fix |
|---|---|---|
| `... rejected the token in $JIRA_API_KEY (HTTP 401) — reissue it` (or 403) | The PAT is expired, revoked, or wrong | Mint a new token for that product and re-export the named variable |
| `TLS handshake with <host> failed using truststore <path>: ...` (or `(JDK default truststore)`) | A private CA is in play and either isn't configured, or the configured file/password is wrong | Point `atlassian.tls.truststore` at the corporate CA chain (`.jks` or `.p12`), and check `truststore_password` |
| `transport error talking to <site>: ...` mentioning a timeout or connection refused | Usually the corporate proxy — either not configured, misconfigured, or the host needs a `no_proxy` bypass | Check `atlassian.proxy`; try adding the failing host to `no_proxy` if it should be reachable directly |

Every probe result — including failures — is also written to a diagnostics file under
`.sdd/diagnostics/`, always, on every `sdd doctor` run. If a failure needs more than the one line
above to diagnose, run `sdd doctor --report` instead — see "First contact" below for why that flag
exists.

## 3. The end-to-end sequence

Run each command from `<workspace-dir>` — the directory holding one checkout of every repo in the
estate, alongside `sdd.yml`.

1. `sdd index` — builds the knowledge base; unrelated to Atlassian, but every later step needs it.
2. `sdd plan <ISSUE-KEY>` → `<ISSUE-KEY>.spec.md`. **Check by hand before going further:**
   - Requirements drawn from BOTH the Jira ticket AND its linked Confluence page, if it has one —
     the page's own Requirements/Acceptance-criteria prose should show up, not just the ticket
     summary.
   - A `## Sources` section lists every fetched document (the ticket, any linked issues, any
     linked Confluence pages) with its version.
   - Anything unfollowed — over the configured `follow_depth`/`max_pages`/`max_linked_issues` cap,
     unresolvable, or pointing at a host that isn't one of the configured sites — sits in Open
     Questions, not silently missing. If something you expected to be pulled in is absent from
     both `## Sources` and Open Questions, that's a real bug, not a documented limitation.
3. Edit the spec by hand, same as any other spec.
4. `sdd plan <ISSUE-KEY>.spec.md` → drafts `plan.md`; edit it by hand.
5. `sdd plan approve <ISSUE-KEY>.plan.md` → freezes `plan.json`. **A comment appears on the
   Jira issue** (if `write_back: comment` is configured and the issue key is in `## Sources`) —
   confirm it in the Jira UI.
6. `sdd implement <ISSUE-KEY>.plan.json` → run branches exist locally, **and nothing has been
   pushed to Bitbucket yet** — verify this explicitly (`git -C <repo> log
   origin/<branch>..<branch>` against Bitbucket's `origin` should fail because the branch doesn't
   exist there yet, or check Bitbucket's UI directly). This is deliberate: nothing touches the
   network before Gate 2.
7. `sdd review <ISSUE-KEY>.plan.json` → `report.md`, one open Bitbucket PR per succeeded repo (if
   `pull_requests: true`), a second Jira comment.
8. The three decisions and their PR effect:
   - `sdd review approve <repo> ...` → the PR is merged (only once the local squash itself
     succeeds — a refused squash never reaches Bitbucket).
   - `sdd review reject <repo> ... --reason "..."` → the PR is declined.
   - `sdd review redo <repo> ... --reason "..."` → the PR is untouched — stays open.
9. `sdd clean --force <ISSUE-KEY>.plan.json` — deletes local run branches/run dir for anything not
   approved; does **not** touch Bitbucket-side PRs or branches it already merged/declined.

**Negative cases worth trying once**, each of which should fail with a clear, specific message,
never a stack trace: an unset credential variable (`sdd plan <KEY>` should name the missing `$VAR`
in its error); a nonexistent issue key; a Confluence link the token cannot read (a
permission-restricted space); `atlassian:` absent from `sdd.yml` entirely (every other command
must behave exactly as before this feature existed); Bitbucket unreachable mid-`sdd review`
(`report.md` should still be written, with a `` warn: bitbucket: ... `` line, exit code
unchanged).

## 4. First contact: what to check when something disagrees with what `sdd` expects

Every behaviour below was implemented from Atlassian's documented REST shapes and checked against
official Data Center documentation (`api-verification-report.md`) — but two of them could not be
settled by documentation alone, and a further handful were never checked against documentation at
all because nothing else in this codebase exercises them. **These are the specific things most
likely to be wrong, ordered by how likely each is to bite, each with the symptom that reveals it —
read the symptom, jump straight to the cause.**

1. **Bitbucket project-key case-folding.** `atlassian.bitbucket.project` is lowercased for the git
   clone/push URL (`/scm/<project>/<repo>.git`, matching Bitbucket's documented always-lowercase
   repo-slug behaviour) but used exactly as configured — usually uppercase — for every REST call
   (`{projectKey}` path parameter, which Data Center's REST API treats case-sensitively). No
   documentation confirms whether the `/scm/` path segment is itself case-folded by the server.
   **Symptom:** not a clean error — a confusing PARTIAL failure. The git push either 404s against
   a lowercase project segment the server doesn't recognize, or silently succeeds against a
   different, accidentally-matching project, while the REST pull-request call (verbatim case)
   succeeds or fails independently. `sdd review` can end up reporting a pushed branch with no
   matching PR. **Check this first** if Bitbucket push/PR behaviour looks wrong at all.
2. **Jira `renderedFields.comment.comments[]`'s `id`/`updated` fields.** Assumed to mirror the base
   `Comment` resource; only the base (non-rendered) resource's shape is documented. **Symptom:**
   silent, not a crash — a comment's `## Sources` bullet reads "updated unknown" and its doc id
   ends in `-comment-` (empty), instead of a real timestamp/id.
3. **A Confluence `/x/AbCd` tiny link's redirect chain** is assumed single-hop and same-origin (the
   5-hop cap is defensive, not evidence-based). **Symptom:** a legitimate tiny link ends up in Open
   Questions as "unfollowed" instead of being read.
4. **jsoup's `element.attr("href")`** is assumed to return the literal, unresolved attribute value
   against Data Center's real rendered HTML. **Symptom:** a relative link that should have been
   followed is silently filtered out (or the reverse).
5. **A Jira "blocking" link type's `name`** is assumed to literally contain "block" or "depend" —
   the field names themselves (`type.name`/`inward`/`outward`) are confirmed correct, but this
   substring match is admin-configurable per instance. **Symptom:** a real blocking dependency is
   missing from `## Sources`, with no error.
6. **The same rendered HTML string** is assumed to reach both the link-harvester and the spec
   extractor for a given description/comment body. **Symptom:** the two readers quietly disagree
   about what a document contains — one follows a link the other doesn't see.
7. **Bitbucket's `findOpenBySourceBranch`** assumes at most one OPEN pull request per source
   branch. **Symptom:** if a human manually opens a second PR from the same branch outside `sdd`,
   only whichever one the API happens to return first is ever read or updated.
8. **The PR-list pagination envelope's field names** (`size`/`isLastPage`/`values`) were never
   confirmed against a live response — `api-verification-report.md` checked only the `at=`/
   `direction=`/`state=` query parameters, not the response shape — and `findOpenBySourceBranch`
   never requests a second page regardless. A distinct cause from item 7 above, with an
   overlapping symptom: **if a matching OPEN PR exists but sits on a later page, it is never seen
   at all**, so `sdd review` opens a duplicate PR instead of updating the existing one.
9. **`atlassian.proxy` carries no proxy-authentication credentials.** If the corporate proxy
   requires its own auth, this was never exercised. **Symptom:** every Atlassian REST call and the
   git push hang or fail with a generic connect/407 error, indistinguishable at first glance from
   the network simply being down.

None of the above has ever caused a test failure, by construction — nothing in this repo can
exercise a real Atlassian server. Their absence from a green `./gradlew build` is not evidence
they are correct. **If you hit any of these, `sdd doctor --report` is the fastest way to capture
enough to debug it remotely — see the next section.**

### The single best tool for reporting a problem: `sdd doctor --report`

```
sdd doctor --report
```

writes one self-contained file under `.sdd/diagnostics/` — every configured site's probe result,
the Java/config/database checks, and (because diagnostics is always on) a tail of the last few
diagnostic files from whatever command was running when things went wrong. It is built to be
copied out of this network and pasted to someone who cannot reach it at all. **Known secret
values — every resolved Atlassian token, the TLS truststore password — are redacted by
construction; they cannot appear in the file no matter what fails.** Internal hostnames and
Bitbucket project / Jira / Confluence issue keys DO appear unredacted, because they are necessary
for diagnosis — the file says so in its own header. Decide for yourself whether that satisfies
your organization's sharing policy before pasting it anywhere; redact those manually first if it
doesn't. See [`commands.md`](commands.md)'s "Diagnostics" section (under `sdd doctor`) for exactly
what the file contains, line by line.

## 5. Obtaining Personal Access Tokens

`sdd` never mints a token itself — obtain one Personal Access Token per product yourself, the
normal Atlassian Data Center way (each product's own profile menu → **Personal Access Tokens** →
**Create token**), and export it from `~/.zshrc` under the name `sdd.yml` references:

```sh
export JIRA_API_KEY=...
export CONFLUENCE_API_KEY=...
export BITBUCKET_API_KEY=...
```

**Never pass a Jira/Confluence/Bitbucket token as a command-line argument** — it would land in
shell history and be visible to `ps` on a shared machine, even briefly. `sdd.yml`'s `${VAR}`
reference is the only supported way to hand `sdd` a credential.
