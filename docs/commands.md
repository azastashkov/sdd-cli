# sdd command reference

This is the exhaustive reference for every `sdd` subcommand: what it does, its
real flags, its real exit codes, and what it writes to disk. Every claim below
was checked against the source at the file:line noted in parentheses; where a
detail could not be verified, it is left out rather than guessed.

For the two-gate model this reference serves (`plan approve` = Gate 1,
`review` + a decision = Gate 2) and a worked end-to-end sequence, see
[`README.md`](../README.md).

Run any command as `sdd-cli/build/install/sdd/bin/sdd <command> ...` after
`./gradlew :sdd-cli:installDist` (`sdd-cli/build.gradle.kts`: `application`
plugin, `mainClass = "sdd.cli.SddCli"`, `applicationName = "sdd"`). Every
command defaults `--workspace` to the current directory
(`Path workspace = Path.of(".")`).

## Exit codes, in general

`sdd` does not use one exit-code convention everywhere — the commands split
into two families:

- **`doctor`, `index`, `plan`, `plan approve`, `plan revise`** use a
  plain success/failure split: **`0`** on success, **`1`** on an application
  error (bad config, validation problems, an empty knowledge base, an
  unhandled exception). None of these set `exitCodeOnInvalidInput`, so a
  malformed invocation picocli itself rejects (an unknown option, for
  example) falls through to picocli's own default, **`2`** — the
  `CommandLine.ExitCode.USAGE` constant. Verified live:
  `sdd plan --help` prints `Unknown option: '--help'` and exits `2`, because
  none of these commands declare `-h`/`--help` themselves — only the top-level
  `sdd` does (`SddCli.java:14`, `mixinStandardHelpOptions = true`, not
  inherited by subcommands).
- **`implement`, `review` (and its `approve`/`reject`/`redo` subcommands),
  `clean`, `status`** all declare `exitCodeOnInvalidInput = 4` and use a
  4-way taxonomy: **`0`** clean success, **`2`** a real finding (a repo not
  `SUCCEEDED`, a failed rebuild/checkout/restore, checkpoint drift, a
  refused decision), **`3`** paused (only `implement`), **`4`** unusable
  input or a live run lock. `status` never returns `2` or `3` — it is
  read-only and never judges (`StatusCommand.java:92-93`).

## Atlassian integration: what's verified, what's assumed

**Read this before you trust anything below about Jira, Confluence or
Bitbucket.** No Jira/Confluence/Bitbucket Data Center instance has ever been
reachable from this codebase — the live-verification run that was originally
planned to replace every hand-written fixture with a recording and settle
every guessed API shape was cancelled (see
[`runbook.md`](runbook.md)). What exists instead is documentation-level
verification: every invented request/response shape was checked against
Atlassian's official Data Center documentation and recorded in
`api-verification-report.md` (repo root of the requirements this branch was
built from). This section restates that report's findings honestly, in three
buckets, and does not soften the third one — its whole value is that someone
debugging at 2am inside a network nobody else can reach finds the honest
answer instead of a reassuring one.

### 1. Verified by test

The Jira/Confluence/Bitbucket integration, and the mutual-TLS model endpoints,
are exercised by 1,599 tests (`sdd-core` 407, `sdd-cli` 601, `sdd-index` 244,
`sdd-plan` 227, `sdd-agent` 120 — `./gradlew clean build`, current tree)
against WireMock stand-ins for all three Atlassian products, a
client-auth-requiring WireMock server for the mTLS handshake, and real local
bare git repositories for the push/clone path.
**Say plainly what this proves and does not prove:** it proves `sdd`'s own
code does what its authors think it does WHEN the server responds exactly
the way `sdd` assumes it will — the request shapes it sends, the response
fields it reads, the retry/backoff/redaction behaviour around all of it. It
proves **nothing** about whether a real Jira/Confluence/Bitbucket Data
Center server actually responds that way. That gap is exactly what buckets 2
and 3 below are for.

### 2. Verified against official documentation

`api-verification-report.md` checked every invented Jira/Confluence/Bitbucket
Data Center request or response shape this codebase uses against Atlassian's
own Data Center documentation (Cloud-only docs were explicitly discarded, not
cited). Of 22 numbered assumptions: **18 CORRECT**, **2 WRONG** (both fixed
in this branch — see below), **2 UNVERIFIABLE** (bucket 3).

| # | Assumption | Verdict |
|---|---|---|
| 1 | Jira `GET .../issue/{key}?expand=renderedFields` → `renderedFields.description` is HTML | CORRECT |
| 3 | Jira `fields.updated` format `yyyy-MM-dd'T'HH:mm:ss.SSSZ` | CORRECT |
| 4 | Jira `GET .../remotelink` → `object.url` | CORRECT |
| 5 | Jira `POST .../comment` `{"body": "..."}`, 2xx response | CORRECT |
| 6 | Jira `issuelinks[].type.name`/`inward`/`outward` field names | CORRECT |
| 7 | Jira/Confluence PAT auth via `Authorization: Bearer <token>` | CORRECT |
| 8 | Jira `GET /rest/api/2/myself` is the whoami endpoint | CORRECT |
| 9 | Jira/Confluence PAT creation method | **WRONG** — was `PUT`, is `POST /rest/pat/latest/tokens`. **Fixed**: documented in [`runbook.md`](runbook.md)'s "Obtaining Personal Access Tokens" section, for the operator minting their own token by hand |
| 10 | Confluence `GET .../content/{id}?expand=body.storage,version,space` shape | CORRECT |
| 11 | Confluence `GET .../content?spaceKey=X&title=Y` search shape | CORRECT |
| 12 | Confluence URL forms (`viewpage.action`, `/display/`, `/spaces/.../pages/`, `/x/`) are genuine Data Center forms | CORRECT |
| 13 | Confluence `GET /rest/api/user/current` is the whoami endpoint, `username` field | CORRECT |
| 14 | Bitbucket default-branch lookup path | **WRONG** — was `.../default-branch`, is `GET .../branches/default`. **Fixed**: `BitbucketClient.defaultBranch` (`sdd-cli/src/main/java/sdd/cli/review/BitbucketClient.java:64-72`) |
| 15 | Bitbucket `POST .../pull-requests` `fromRef`/`toRef` shape | CORRECT |
| 16 | Bitbucket open-PR-by-source-branch filter (`at=`, `direction=OUTGOING`, `state=OPEN`) | CORRECT |
| 17 | Bitbucket PR `version` as a query parameter on `merge`/`decline`, empty body | CORRECT |
| 18 | A Bitbucket 409 signals a stale `version` | CORRECT |
| 20 | Bitbucket `X-AUSERNAME` response header carries the authenticated username | CORRECT (header value may be URL-encoded; `AtlassianProbe` reads it raw — cosmetic only) |
| 22 | Bitbucket git-over-HTTP PAT username `"x-token-auth"` | CORRECT for user-level tokens (which is all `sdd` mints) |

Two more (**21**, path/method/body of Bitbucket PAT creation) are CORRECT
except for one field, and are listed under bucket 3 below because that field
IS an open assumption. Items **2** and **19** are the two UNVERIFIABLE
findings — bucket 3, in full.

### 3. Unverified assumptions — complete and blunt, on purpose

These are real gaps. Nothing below was settled by a test or by
documentation; each is flagged with the symptom that would appear if it
turns out to be wrong, so that symptom can point straight back to this
list.

**The two the documentation review could not settle, and the two most likely
to bite first:**

- **Bitbucket project-key case-folding in `/scm/` clone URLs.**
  `RemoteGit.cloneUrl` lowercases BOTH the project and repo segments for the
  git clone/push URL (`<base>/scm/<project>/<repo>.git`), matching Bitbucket's
  documented always-lowercase repo-slug behaviour. But `BitbucketClient`'s
  REST `{projectKey}` path parameter is used exactly as configured in
  `atlassian.bitbucket.project` — verbatim, usually uppercase (e.g.
  `TRADING`) — because Data Center's REST API path parameters are
  case-sensitive. **No documentation settles whether the `/scm/` path
  segment is itself case-folded by the server**, so if `atlassian.bitbucket
  .project` is configured in mixed case, the git push (lowercased) and the
  PR-creation REST call (verbatim) could disagree about which project they
  mean. **Symptom if wrong:** not a clean error — a *confusing partial
  failure*: the git push to `/scm/<lowercased>/...` either 404s (if the
  server's SCM routing is case-sensitive for the project segment and no
  such lowercase project exists) or succeeds against a DIFFERENT,
  accidentally-matching project, while the REST PR-creation call against the
  verbatim uppercase project key succeeds or fails independently — so `sdd
  review` can report a pushed branch with no matching PR, or a PR opened
  against the wrong project's repo list, with no single error naming the
  cause. **First thing to check** if Bitbucket push/PR behaviour looks wrong
  on a live instance, per `api-verification-report.md` item 19.
- **Whether Jira's `renderedFields.comment.comments[]` carries `id`/`updated`
  alongside the rendered `body`.** `JiraClient` assumes the rendered
  comment sub-shape mirrors the base `Comment` resource's fields; only the
  base resource's `id`/`updated` fields are documented, not the *rendered*
  variant specifically. **Symptom if wrong:** silent, not a crash — a
  comment's Sources bullet reads "updated unknown" and its doc id ends in
  `-comment-` (empty), rather than the plan citing a real timestamp/id.
  (`api-verification-report.md` item 2)

**Every other open assumption**, by where it lives and what reveals it:

| Assumption | Where | Symptom if wrong |
|---|---|---|
| A Confluence `/x/AbCd` tiny-link redirect is single-hop and same-origin | `ConfluenceClient`'s tiny-link resolution (5-hop cap is defensive, not evidence-based) | A legitimate tiny link resolves to Open Questions as "unfollowed" instead of being read, because the real redirect chain is longer or cross-origin |
| jsoup's `element.attr("href")` returns the literal, unresolved attribute value against Data Center's real rendered HTML | Jira/Confluence link-harvesting in `sdd-plan/src/main/java/sdd/plan/{jira,confluence}/` | A relative link that should have been followed is silently filtered out (or a link that should have been filtered is followed) |
| A "blocking" Jira link type's `name` literally contains "block" or "depend" | Same link-harvesting code — admin-configurable per instance | A blocking dependency the estate actually has is missing from the plan's `## Sources`, with no error — it just never gets classified as blocking |
| `renderedFields.description`/comment bodies are the exact same HTML string both the link-harvest and `ConfluenceExtract.extract` read | Same area | Two readers of "the same" field quietly diverge — one follows a link the other doesn't see, again with no error |
| `findOpenBySourceBranch` assuming at most one OPEN PR per source branch | `BitbucketClient.findOpenBySourceBranch` (`sdd-cli/src/main/java/sdd/cli/review/BitbucketClient.java:80-88`) | If a human manually opens a second PR from the same branch outside `sdd`, only the first result the API happens to return is ever read/updated — the other is silently ignored |
| The PR-list pagination envelope's field names (`size`/`isLastPage`/`values`) — standard Bitbucket Server shape, never confirmed against a live response; `api-verification-report.md` item 16 verified only the `at=`/`direction=`/`state=` query parameters, not the response envelope | Same method — reads `node.path("values")` with no `isLastPage` check, and never requests a second page (`BitbucketClient.java:80-88`) | A distinct root cause from the row above, with an overlapping symptom: if a matching OPEN PR exists but sits on a later page, it is never seen at all (not merely de-prioritized) — `sdd review` then opens a duplicate PR instead of updating the existing one |
| `atlassian.proxy` never carries proxy authentication credentials | `sdd.core.http.HttpClients` proxy wiring | Every Atlassian REST call AND the git push hang or fail with a generic 407/connect error on a corporate proxy that requires its own auth — indistinguishable at first glance from a plain network-down failure |

None of the items in this bucket have ever caused a test failure — by
construction, since nothing in this repo can exercise them against a real
server. Their absence from a green `./gradlew build` is not evidence they
are correct.

## `sdd doctor`

**What it does:** checks the local environment is ready to run everything
else — Java major version, `sdd.yml` loads, `.sdd/index.db` opens (creating it
if absent), every configured model endpoint answers a probe (or, with
`--endpoint <name>`, just that one), and — when `sdd.yml` has an
`atlassian:` block — one probe per configured Jira/Confluence/Bitbucket site.
(`DoctorCommand.java:80-146`)

**Model endpoints authenticated with a client certificate (mutual TLS).** A
`models.<name>.tls` block (`cert`/`key`/`key_password`/`protocols`/
`truststore` — see `sdd.yml.example`) authenticates that endpoint with a
client certificate instead of, or alongside, `api_key`; a plain endpoint with
no `tls:` block is unaffected by any of what follows
(`DoctorCommand.java:249-252`). Before probing such an endpoint, `doctor`
runs one pre-flight check, `model:<name>:tls`, that validates exactly what
would otherwise fail opaquely at the TLS layer
(`DoctorCommand.java:249-283`):

| Validated | On failure | Verified |
|---|---|---|
| `tls.cert`/`tls.key` exist and are readable, and the key actually parses | Names the path (never file contents); a PKCS#1/SEC1/legacy-encrypted key names the header found and the exact `openssl pkcs8 -topk8 -nocrypt` conversion command | `DoctorCommand.java:255-264`, `HttpClients.java:228-266` |
| The client certificate is not expired | `client certificate expired <notAfter> (subject=<subject>)` | `DoctorCommand.java:269-275` |

Two more things happen alongside that check, printed as `  warn: ` lines —
same convention as every other `sdd` sub-diagnostic — rather than as a
pass/fail check, since neither one is itself a reason to distrust the
endpoint: the client key file is group- or world-readable
(`HttpClients.java:294-303`, wired at `DoctorCommand.java:265-268`), and the
certificate expires within 30 days (`DoctorCommand.java:276-282`). The
existing endpoint probe then runs exactly as it always has, whether or not
the pre-flight check passed — a broken certificate is reported, never hidden,
but it does not prevent every other check from running
(`DoctorCommand.java:218-223`) — and one line is added to the diagnostics
file for this endpoint; see "Diagnostics" below.

**The three Atlassian probes**, run only when `config.atlassian() != null`
(`DoctorCommand.java:143-145`) — a missing `atlassian:` block changes this
command's output not at all:

| Site | Probe | Success text | Verified |
|---|---|---|---|
| `atlassian:jira` | `GET /rest/api/2/myself` | `HTTP 200 as <name or displayName>` | `DoctorCommand.java:347-350` |
| `atlassian:confluence` | `GET /rest/api/user/current` | `HTTP 200 as <username or displayName>` | `DoctorCommand.java:351-354` |
| `atlassian:bitbucket` | `GET /rest/api/1.0/projects/{project}` | `HTTP 200 as <X-AUSERNAME header>` | `DoctorCommand.java:355-371` |

Each of the three is **independently optional** — declaring only
`atlassian.jira` runs only the Jira probe, and the other two lines simply do
not appear (`DoctorCommand.java:347, 351, 355`). Bitbucket has no
`/users/self` resource on Data Center's REST 1.0 API, so its probe reuses the
one authenticated call it already needs (confirming the configured project is
reachable) and reads the identity off that response's `X-AUSERNAME` header
instead of making a second call (`DoctorCommand.java:357-369`,
`AtlassianProbe.java:62-69`).

**Failure diagnostics.** A probe failure's message is built to point at a
fix, not just report "down": an HTTP 401/403 names the environment variable
to reissue (`"<site> rejected the token in $<VAR> (HTTP 401) — reissue it"`,
`RestClient.java:186-187, 285-288`); a TLS handshake failure names the host
and which truststore was in play (`"TLS handshake with <host> failed using
truststore <path>: <detail>"`, or `"(JDK default truststore)"` when none is
configured, `HttpClients.java:384-387`, wired for Atlassian at
`AtlassianProbe.java:98-102`); any other transport failure (a connect timeout
being the common proxy-related case) prints the JDK's own message under
`"transport error talking to <site>: <detail>"` (`RestClient.java:201-203`)
— there is no separate "effective proxy" sentence on `doctor`'s own stdout
for this case (that enrichment exists in `RestClient.logFailure` for every
OTHER Atlassian caller's diagnostics-file entries, `RestClient.java:252-274`,
but `AtlassianProbe`'s own `RestClient` is built with no `TransportContext`,
so it does not apply to `doctor`'s probes specifically). A bad
`atlassian.tls.truststore` (missing file, unreadable, wrong password) is
reported against every configured site rather than aborting the rest of
`doctor`'s checks (`DoctorCommand.java:332-345`).

A model endpoint's TLS handshake failure gets the same host/truststore
naming, extended with the specific trap this is most likely to be: **a
working `curl` to the same URL is not evidence the JDK trusts the same
certificate chain** — curl trusts the OS certificate store (macOS keychain;
`/etc/ssl/certs` on Linux), the JDK trusts only its own `cacerts`, so a
corporate CA installed system-wide but never imported into the JDK produces a
bare `PKIX path building failed` right after curl succeeded against the same
URL. The message names both fixes — set `models.<name>.tls.truststore` (the
endpoint's own `TlsConfig.configPath`, threaded through by `ConfigLoader.
parseModelTls`; a generic `tls.truststore` only when built with no known
path), or import the CA into `$JAVA_HOME/lib/security/cacerts`
(`HttpClients.java:421-427`, wired at `EndpointProbe.java:92-101`).

**Flags**

| Flag | Default | Description | Verified |
|---|---|---|---|
| `--workspace <dir>` | `.` | Workspace directory | `DoctorCommand.java:38-39` |
| `--report [path]` | off | Also write a self-contained diagnostics report — default path under `.sdd/diagnostics/` when given with no value, or the exact path given; prints the path plus a one-line note on what is/isn't redacted | `DoctorCommand.java:51-54, 105-107, 148-154` |
| `--endpoint <name>` | off (probe every configured model) | Probe only this model endpoint instead of every configured tier — for iterating on one endpoint's TLS certificate without waiting for the rest; an unknown name reports `[FAIL] endpoint` rather than silently probing nothing. The `atlassian:*`, java, config and database checks are unaffected either way | `DoctorCommand.java:56-68, 138-146, 196-213` |

**Exit codes**

| Code | Meaning |
|---|---|
| `0` | every check passed |
| `1` | at least one check failed (`DoctorCommand.java:155`, `allOk ? 0 : 1`) |

**Writes to disk:** `Database.open`'s side effect of creating `.sdd/index.db`
(and the `.sdd/` directory) if it does not already exist, same as every other
command that opens the database — **plus, always, one diagnostics file**
under `<workspace>/.sdd/diagnostics/` for this invocation (see "Diagnostics"
below); `--report` either reuses that same file (default path) or writes it
at the given path instead, and additionally appends a `probe <check>:
OK|FAIL — <detail>` line for every check (not only the Atlassian ones) plus a
tail of the 3 most recent other diagnostic files (`DoctorCommand.java:148-154,
166-184`).

## Diagnostics

**Every `sdd` command that talks to Jira, Confluence or Bitbucket writes one
diagnostics file per invocation** to `<workspace>/.sdd/diagnostics/`, named
`<yyyyMMdd-HHmmssSSS>-pid<pid>-<seq>-<command>.log`
(`DiagnosticsDir.java:82-85`). This is always on for `sdd doctor`, `sdd plan`
(the Jira/Confluence ref path only), `sdd plan approve`, and `sdd review`
(including its `approve`/`reject`/`redo` decisions) — there is no flag to
disable it, only `sdd doctor --report` to also produce one at a chosen path.
The most recent 20 files are kept; older ones are deleted the next time a
command allocates a new one (`DiagnosticsDir.java:34, 88-101`).

**What it is for:** a single file, designed to be copied out of a closed
corporate network and pasted to someone who cannot reach it, that is
self-contained enough to debug from — every Atlassian HTTP attempt (method,
path, status, duration, retry), every terminal failure's full cause chain (a
TLS or proxy-shaped failure gets one extra correlated line naming the
truststore or effective proxy in play), Gate-2's per-repo decision events,
and every git-push outcome (`DiagnosticWriter.java:139-203`).

**Model endpoint TLS.** For each model endpoint configured with `tls.cert`/
`tls.key`, one `model-tls` line is added per `sdd doctor` run: the
certificate path, its subject and expiry, the TLS protocol actually
negotiated during the probe (or `?` when the probe never completed a
handshake — a plain HTTP endpoint, or one that failed before the TLS layer),
and whether a custom `tls.truststore` was in play — never the key, never
`tls.key_password` (`DoctorCommand.java:309-327`). The code that builds this
line never touches the key or its password, and never interpolates the whole
`TlsConfig`/`ModelEndpoint` record either — only individual accessors like
`clientCert()`/`truststore()` (`DoctorCommand.java:291-303`) — so it is
written the same way every other line in this file is, through
`DiagnosticWriter`, which still applies the redaction pass described below
as a backstop.

**Redacted by construction, not by caller discipline.** Every resolved
Atlassian token and the TLS truststore password are collected once, from the
whole `atlassian:` config, before anything is written, and every string
written to the file is scrubbed against that set. `sdd doctor`'s writer also
collects every configured model endpoint's `tls.key_password` and
`tls.truststore_password` the same way (`DiagnosticsSecrets.java:46-91`,
wired from `DoctorCommand.java:112-119`) — belt-and-braces alongside the
model-tls line's own "never construct the interpolation" guarantee above,
not a substitute for it — plus three
pattern-based rules needing no known-secret list at all: URL userinfo
elision, `Authorization:` header elision, and a credential-shaped query
parameter (`token=`/`access_token=`/`pat=`/`password=`/`secret=`/
`api[-_]?key=`) elision (`Redactor.java:41-56, 97-110`). A secret shorter
than 8 characters is not collected at all, so a misconfigured or placeholder
token cannot substring-match and mangle unrelated prose (`Redactor.java:58-65,
84-93`). A non-2xx response body's first 500 characters are redacted BEFORE
being truncated to that length, not after, so a leaked credential straddling
the cutoff cannot survive as an unredacted fragment (`DiagnosticWriter.java:126-150`).

**What is NOT redacted, on purpose:** internal hostnames and Jira/Confluence/
Bitbucket project and issue keys appear unredacted, because a file with them
scrubbed out would be useless for diagnosis — the header block says this in
plain words (`DiagnosticHeader.java:59-71`), and `sdd doctor --report` prints
the same caveat to stdout (`DoctorCommand.java:151-153`). Decide for yourself
whether that satisfies your own sharing policy before pasting a file
anywhere; if not, redact those manually first.

**Never fails the command it instruments.** Opening the file, and every
write, swallow their own I/O failures and warn at most once
(`DiagnosticWriter.java:25-32`); the `Diagnostics.open`/`openAt` facade itself
is wrapped end to end so even a failure in rendering the header cannot turn a
diagnostics problem into a failed `sdd` command (`Diagnostics.java:16-29`).

## Progress reporting

`index`, `implement`, `review` and `plan` report how far a slow run has
gotten while it is still running, instead of staying silent until everything
prints at once — described below. `doctor` is **not** wired to this feature:
`DoctorCommand` never calls `SddCli.resolve`, so `--quiet` and
`SDD_PROGRESS=off` have no effect on it; it keeps the separate `[ OK ]`/
`[FAIL]` per-probe stream it already had before this feature existed (see
"`sdd doctor`" above), unaffected either way. `clean` and `status` do nothing
slow enough to need it and were never wired either.

**It always writes to stderr, never stdout** — the writer is captured once,
in `SddCli.main`, as `new PrintWriter(System.err, true)`
(`SddCli.java:47-55`) — so `sdd index 2>/dev/null` or `sdd index | cat`
leaves stdout's own report byte-for-byte identical to a run with progress
off. A subcommand never constructs a renderer itself; it asks for whatever
`SddCli.main` armed via `SddCli.resolve` (`SddCli.java:67-78`), which walks
up to the root `SddCli` object and returns `Progress.noOp()` — a real
implementation whose every method does nothing (`Progress.java:98-102`,
`sdd-core/src/main/java/sdd/core/progress/Progress.java`) — if nothing was
ever armed (true of every test in this tree, which is why none of them see a
progress line by construction, not by coincidence).

**Live on a terminal, plain when piped.** `SDD_PROGRESS` = `off` / `plain` /
`live` / `auto` (default `auto`) is checked first, ahead of everything else,
consistent with `SDD_NODE`; any other value is treated as `auto` rather than
rejected, so a typo in the escape hatch cannot fail a command over a progress
bar. When it is unset or `auto`, the decision ladder runs in order —
`SDD_PROGRESS` → `TERM` unset/`dumb` → `CI` set → console — and each rung is
checked only once the ones before it declined to answer, so an explicit
choice can never be overridden by a later rung, only narrowed
(`ProgressEnvironment.java:39-60`). The last rung is a real terminal check
(`ConsoleSupport.java:27-38`), deliberately not the simpler
`System.console() != null`: that check is correct on Java 21 but silently
wrong on 22+ (JDK-8295803 made `System.console()` return non-null even when
redirected), so it is called reflectively and a `ReflectiveOperationException`
— reachable only on 21, where a non-null console already means a real
terminal — is treated as `true`, so a future JDK upgrade cannot quietly turn
live rendering on in CI. `--quiet` is checked before any of this, ahead of
the whole ladder — it is a command-line choice, not an environment one
(`ProgressArming.java:32-47`).

**The terminal check is not tied to the stream progress actually writes
to.** `ConsoleSupport.isTerminal()` reads `System.console()`, which reflects
the JVM's own stdin/stdout attachment — it says nothing about stderr, and
the renderer always writes to stderr (above), never the stream this rung
inspected. So `sdd index 2>progress.log`, run interactively, still has
`System.console()` return non-null: the ladder selects LIVE, and the raw
`\r` + space-padding frames land byte-for-byte in `progress.log` instead of
on a screen — nothing in the ladder asks whether stderr specifically is a
terminal.

**`--quiet`** is a root-level option with `scope = ScopeType.INHERIT`
(`SddCli.java:23-26`), so both `sdd --quiet index` and `sdd index --quiet`
parse and disable progress the same way, regardless of `SDD_PROGRESS` or
whether the console is a terminal.

**Plain (piped/CI):** no thread, no timer, no state — `phase`/`start`/
`detail` are silent by design (rendering any of them would mean remembering
something this renderer deliberately does not carry), and the only line it
ever prints is one `println` per finished item, `"<item>  done"`
(`PlainProgress.java:32-56`). **Never a `<repo>: ` prefix** — that exact
shape is load-bearing for `ReviewReport`/`InteractiveReview.replaceForRepos`
to parse (`RebuildPass.java:113-114`) — a mid-pass `note`/`suspend` message is
passed straight through unchanged instead.

**Live (TTY):** a single self-updating line — `\r` + space-padding +
`flush()`, truncated to 80 columns, no ANSI, no colour, no new dependency
(`LiveProgress.java:54-55, 261-293`). A daemon `sdd-progress` thread repaints
it once a second via `scheduleWithFixedDelay` (fixed-*rate* would emit a
catch-up burst after a GC pause); `stop()` erases the line and shuts the
thread down, so a killed process never leaves a half-drawn line on the
terminal or a non-daemon thread hanging the JVM (`LiveProgress.java:107-128,
216-232`). Elapsed time renders as `m:ss` — `0:42`, `4:12`, `12:45`
(`Elapsed.java:19-24`). The renderer picks its own line shape from how many
items are simultaneously in flight, with no caller declaring which one it
wants (`LiveProgress.java:295-332`) — three shapes share the same model:

| Shape | Example | When |
|---|---|---|
| Sequential | `4/11  order-service  gradle extract  0:42` | exactly one item in flight (`index`'s per-repo loop, `review`'s rebuild pass) |
| Parallel | `3/11 done  running: order-service 4:12, billing 1:07 (+1)  12:45` | more than one item in flight (`implement`'s scheduler) |
| Idle | `impact analysis  0:15` | a `phase()` with no `start`/`finish` calls at all (`plan`) |

The parallel line caps named repos at two with `(+N)` for the rest and sorts
oldest-first — insertion order in a `LinkedHashMap` already is start order,
so nothing has to re-sort per tick and the two shown names cannot flicker
between adjacent repos (`LiveProgress.java:74-77, 313-332`). A phase with no
items in flight and no phase name at all (nothing has called `phase()`
yet) renders an empty string, and `paintLocked` short-circuits on it — a
renderer nobody has emitted an event to writes nothing at all, not even a
bare `\r` and 80 spaces (`LiveProgress.java:261-271, 334-339`).

**What each wired command reports:**

- **`index`** — one estate-wide `"index"` phase sized to the repo count, with
  a `start`/`finish` pair per repo and a `detail` for whichever long pole
  that repo is currently sitting at (`<build system> extract`, e.g. `gradle
  extract`/`npm extract`, then `source extraction`), followed by fixed
  phases for the estate-wide linking/usage/matching/runtime-edge/cleanup/
  report passes and, unless `--no-cards`, its own `"cards"` phase with a
  `start`/`finish` per repo card actually generated — a cache hit (an
  up-to-date `repo_card` row) `continue`s before `start` is ever called, so
  it never looks like work (`IndexService.java:149-186, 306, 310`;
  `RepoCardGenerator.java:60, 89, 122`).
- **`implement`** — the parallel line above: one `"implement"` phase sized to
  the run's repo count, driven entirely off `Orchestrator`'s own state
  transitions rather than a second, parallel event stream that could
  disagree with `state.json` — `IN_PROGRESS` is a `start`, every terminal
  state (`SUCCEEDED`/`FAILED`/`SKIPPED_UPSTREAM_FAILED`) is a `finish`, and a
  pause (`PAUSED_INFRA`/`PAUSED_ENDPOINT`) is a `note` rather than a `stop` —
  a pause belongs to the one repo that hit it, not to the whole run, and
  sibling repos already in flight in the same parallel layer keep running
  after it (`Orchestrator.java:132, 778-785`).
- **`review`** — one estate-wide `"rebuild"` phase sized to every repo
  `Scheduler.sequence` visits (not just the ones actually rebuilt — a
  provider staged only as an upstream tree, or a repo that never reached
  `SUCCEEDED`, still gets a `start`/`finish` pair), with `start`/`finish`
  bracketing the WHOLE per-repo body in one `try`/`finally` — the same
  structural fix `IndexService`'s per-repo loop uses — so `finish` fires
  whichever of the loop's several exits a repo took: an unstageable
  repo, a failed checkout, a repo outside the rebuild subset, one with no
  locally-runnable verification, or `verify` itself throwing
  (`RebuildPass.java:96-206`). Within a repo's bracket, `detail` marks the
  sub-steps actually worth sitting through: the checkpoint checkout, npm
  provider overlay staging (only for an `NPM_OVERLAY` consumer), and
  `verify` — the true long pole, since `EstateRebuild.verify` shells out to
  gradle/npm per task with a 15-minute-per-task timeout
  (`RebuildPass.java:134, 177, 188`). After the loop, re-checking actualized
  contracts against fresh extraction is its own `"contracts"` phase (it runs
  once over the whole staged estate, not per repo), and restoring every
  checked-out repo to its original branch/commit in the outer `finally` is a
  `"restore"` phase for the same reason (`RebuildPass.java:213, 219`). What
  `Progress` ALSO still does here, unchanged by any of the above: keep a
  mid-pass finding from colliding with a live frame — every `` warn: `` line
  this pass can print (a failed checkout, a repo with no checkpoint to stage
  at all, a failed restore back to the original branch) is routed through
  `Progress.suspend`, which erases the line, prints the warning
  unconditionally (even under `Progress.noOp()`, since these are review
  findings, not progress chrome), and repaints — and guarantees the line is
  erased, via `stop()`, before `report.md`'s own first line prints
  (`RebuildPass.java:73-74, 153-155, 231-232, 247-248`; `ReviewCommand.java:110,
  180, 191`).
- **`plan`** — named phases around `PlanCommand.validate`'s four expensive
  calls: `"impact analysis"`, then `"execution order"`, `"open questions"`
  and `"draft plan"`. The `impact:` report block prints midway through this
  sequence, not at the end, so it is routed through `Progress.suspend`
  rather than `Progress.stop` — `stop()` would end the whole session and
  leave the later three phases nothing to paint into — with the real
  `stop()` deferred to right after drafting finishes, before the `plan
  written:` block prints (`PlanCommand.java:413, 423, 425, 427, 429, 436`).
  Confluence/Jira normalization (`sdd plan` with no canonical `.md` ref) is
  deliberately left uninstrumented: each source's own model/network call is
  a single blocking round trip, not a loop with a meaningful item count the
  way the other four phases are, so a `phase()` with nothing to advance
  through would add ceremony without adding signal — `Progress` is still
  resolved for that path so a live renderer's ticker thread is never leaked,
  but it never receives an event (`PlanCommand.java:94-103`).

## `sdd index`

**What it does:** scans every git repo directly under the workspace
(`WorkspaceScanner.scan`: a directory with a `.git`, not excluded by
`sdd.yml`'s `excludes:`), works out which build system owns each one, extracts
its facts into `.sdd/index.db`, links internal dependencies, matches REST/Kafka
edges, and
(unless skipped) generates a repo-card summary per repo with the `coder`
model. (`IndexCommand.java:51-148`)

**Flags**

| Flag | Default | Description | Verified |
|---|---|---|---|
| `--workspace <dir>` | `.` | Workspace directory | `IndexCommand.java:29-30` |
| `--no-cards` | off | Skip model-generated repo card summaries | `IndexCommand.java:32-33` |
| `--force` | off | Re-index every repo even when its fingerprint is unchanged, instead of skipping it as "(unchanged, skipped)". Composable with `--no-cards`; does not itself force card regeneration (cards are cached independently by content hash) | `IndexCommand.java:35-40` |

**Exit codes**

| Code | Meaning |
|---|---|
| `0` | no repo was scanned, or at least one repo did not fail — `allFailed` requires a non-empty result list whose every entry is `FAILED`, so an empty workspace exits 0 too (`IndexCommand.java:138-140`) |
| `1` | config failed to load, `--no-cards` is absent and the `coder` endpoint's API key is unresolved, every scanned repo's status was `FAILED`, or an unhandled exception (`IndexCommand.java:69-74, 81-87, 138-144`) |

**Writes:** `.sdd/index.db` (schema tables for repos, modules, deps, REST
endpoints/clients, Kafka roles, repo cards) and a curation report path printed
at the end (`service.lastReportPath()`, `IndexCommand.java:137`).

**Build systems.** A repo is offered to each extractor in turn and the first
that claims it wins; Gradle is asked first, so a Spring service that ships a
`package.json` to build its frontend assets stays a Gradle repo. A repo that
neither claims is recorded `UNSUPPORTED` — distinct from `FAILED`, which means
"we tried to read this build and could not".

- **Gradle** — the Tooling API, with a static parse of the build files as the
  degraded fallback.
- **npm** — `package.json`, its `workspaces` globs, and `package-lock.json`
  when present. No subprocess, no network and no `node`: everything needed is
  declared in files the repo checks in. `node_modules` is never read, because
  it records what someone installed rather than what the repo declares.

**TypeScript sources** are read by the TypeScript compiler itself, run under
`node`. Without `node` a repo's dependency graph still indexes fully and only
its `parse_status` is `FAILED` — and because a failed parse is never skipped as
"unchanged", the repo re-reads itself on the next run once `node` appears.
Only real syntax counts: a path named in a doc comment is not a call site, so
`/api/streams` is recorded from the repo that calls it and not from the one
that merely documents it.

## `sdd plan [<ref>...] [--text <text>...]`

**What it does:** dispatches on the SHAPE of each `<ref>` — by pattern alone,
no network or filesystem access — into one of four kinds
(`SpecSources.classify`, `sdd-plan/src/main/java/sdd/plan/spec/SpecSources.java:25-43`):

| Kind | Recognised as | Example |
|---|---|---|
| `MARKDOWN` | anything not matched below | `SPEC-101.md` |
| `CONFLUENCE_EXPORT` | `.html`/`.htm`/`.xhtml` filename | `export.html` |
| `JIRA` | a Jira key (`[A-Z][A-Z0-9_]*-[1-9][0-9]*`), or a URL whose path matches `/browse/<KEY>` | `PROJ-101`, `https://jira.corp.local/browse/PROJ-101` |
| `CONFLUENCE_PAGE` | a URL whose path contains `/pages/`, `/display/`, or `/x/` | `https://confluence.corp.local/display/SPACE/Title` |

and then into one of two modes:

- **Validate mode — exactly one `MARKDOWN` ref, alone.** Validates the spec,
  requires a non-empty `.sdd/index.db` (i.e. `sdd index` already run), runs
  impact analysis (which repos are affected and why), computes an execution
  order, drafts open questions and a plan narrative with the `planner` model,
  and writes `<spec-base>.plan.md`. (`PlanCommand.java:126-127, 385-448`)
- **Normalize mode — everything else** (one or more `CONFLUENCE_EXPORT`/
  `JIRA`/`CONFLUENCE_PAGE` refs, any mix, plus any number of `--text`):
  fetches/reads every source, assembles them into one bundle, normalizes it
  into canonical markdown via the `planner` model, self-checks that the
  result re-parses, and writes a single `.spec.md`. This is spec
  *normalization*, not impact analysis — every remote mode stops here and
  prints `review and edit the spec, then run: sdd plan <path>` rather than
  running impact analysis itself (`PlanCommand.java:129, 351-369`).

**Combination rules** (`PlanCommand.java:109-116`): a `MARKDOWN` ref is already
a normalized, reviewable spec — combining it with anything else (a second
ref, or `--text`) is meaningless and is rejected with `error: a canonical
spec ref cannot be combined with other sources`. Every other kind composes
freely: any mix of `CONFLUENCE_EXPORT`, `JIRA` and `CONFLUENCE_PAGE` refs,
plus any number of `--text` values, is assembled into one `SourceBundle` and
normalized together (`PlanCommand.java:198-233, 270-327`). `sdd plan` with
neither a ref nor `--text` fails with `error: missing required parameter:
<ref>` (`PlanCommand.java:105-108`).

**Atlassian sources need config.** A `JIRA` ref requires `atlassian.jira`;
a `CONFLUENCE_PAGE` ref requires `atlassian.confluence` — each checked, and
failed with a `ConfigException` naming the missing block, before any network
call (`PlanCommand.java:214-220`). A `JIRA` ref additionally follows linked
Confluence pages when `atlassian.confluence` is configured, bounded by
`atlassian.follow_depth`/`max_pages`/`max_linked_issues`
(`PlanCommand.java:255-268`); with no Confluence site configured, Jira
material is ingested with no link-following rather than erroring
(`PlanCommand.java:251-255`).

**`--out` and default output paths** (`PlanCommand.java:139-187, 235-327`):

| Refs given | Default target (no `--out`) |
|---|---|
| Single `CONFLUENCE_EXPORT` ref, no `--text` | `<ref>.spec.md` |
| Any mix of `CONFLUENCE_EXPORT`/`--text`, no Jira/Confluence-page ref | first export ref's `<ref>.spec.md`, or (pure `--text`) `<workspace>/<slug of first --text>.spec.md` |
| One or more `JIRA` refs present | `<workspace>/<first Jira key>.spec.md` — a Jira ref is treated as the primary requirement record and wins over any export/page anchor |
| `CONFLUENCE_PAGE` ref(s), no Jira ref | first export ref's path if one is also present, else `<workspace>/<fetched page id>.spec.md` |

`--out` overrides all of the above and must itself be a markdown target — a
Confluence-export-shaped `--out` is rejected (`PlanCommand.java:140-142,
199-201`). The slug used for a pure-`--text` filename is the first ~6 words
of the text, lowercased and non-alphanumerics collapsed to `-`
(`PlanCommand.java:371-383`).

**Diagnostics.** Any invocation that touches a `JIRA` or `CONFLUENCE_PAGE`
ref opens one diagnostics file under `.sdd/diagnostics/` for the whole
invocation, covering both the Jira and Confluence REST traffic
(`PlanCommand.java:222-233`); a plain markdown or Confluence-export-only
`sdd plan` opens none. See "Diagnostics" under `sdd doctor` above.

**Flags**

| Flag | Default | Description | Verified |
|---|---|---|---|
| `--workspace <dir>` | `.` | Workspace directory | `PlanCommand.java:67-68` |
| `--out <path>` | see table above | Where to write the normalized spec (rejects a non-markdown target) | `PlanCommand.java:70-72` |
| `--text <text>` | none | Free-text requirement (repeatable); never inferred from a bare positional | `PlanCommand.java:74-76` |
| `<ref>` (positional, arity 0..*) | none | Spec refs: canonical `.md`, exported Confluence `.html`/`.htm`/`.xhtml`, a Jira key/URL, or a Confluence page URL | `PlanCommand.java:78-81` |

**Exit codes**

| Code | Meaning |
|---|---|
| `0` | normalization or plan-drafting succeeded |
| `1` | missing ref/text, a canonical ref combined with other sources, config load failed, missing `atlassian.jira`/`atlassian.confluence` for a ref that needs it, spec validation problems, empty/missing knowledge base, or an unhandled exception (`PlanCommand.java:105-133, 214-220, 385-402`) |

**Writes:** the normalized `.spec.md` (normalize mode) or `<spec-base>.plan.md`
(validate mode), via `SafeWrite.writeWithBackup` — an existing file at that
path is backed up first, and the backup path is printed if one was made
(`PlanCommand.java:359-363, 440-444`) — plus, for any Atlassian-sourced run,
a diagnostics file (above).

### `sdd plan approve <spec>.plan.md` — Gate 1

**What it does:** the human-in-the-loop gate that freezes a reviewed
`plan.md` into `plan.json`. Parses `plan.md`, re-validates the sibling
`<spec>.md`, checks every affected repo's live git state against what the
plan expects, runs `PlanValidator` (plan-vs-knowledge-base consistency),
probes each cross-repo edge with a Gradle include-build smoke test (a failed
probe only warns and falls back to `MAVEN_LOCAL` — it never fails the
command; `PlanJson.java:92-104`), and on success compiles and writes
`<spec-base>.plan.json`. (`ApproveCommand.java:31-131`)

**Gate-1 Jira comment.** When the approved spec's `## Sources` section names
one or more Jira issue keys (`SourceBullet.jiraIssueKeys`), and STRICTLY
AFTER `plan.json` is durably written, posts one best-effort comment to each
source issue: `` sdd: plan approved for `<spec-id>` — `<N>` repos affected,
execution order: `<repo>, <repo>, …` `` (`ApproveCommand.java:125,
139-152`). A spec with no Jira sources triggers no config load and no output
at all — the normal case for a hand-written or free-text-derived spec
(`JiraWriteBack.java:85`). Posting only happens when
`atlassian.write_back: comment` is configured; the default (`none`, or no
`atlassian:` block) posts nothing and prints nothing
(`JiraWriteBack.java:91-95`). Every failure — one issue's comment failing,
the whole config/credential/network path failing — prints `` warn: jira
comment failed: <detail> `` and **never changes the exit code**: this method
never throws (`JiraWriteBack.java:29-34, 104-124`).

**Flags**

| Flag | Default | Description | Verified |
|---|---|---|---|
| `--workspace <dir>` | `.` | Workspace directory | `ApproveCommand.java:34-35` |
| `--no-comment` | off | Suppress the Jira write-back comment even when `atlassian.write_back: comment` is configured | `ApproveCommand.java:40-42` |
| `<planPath>` (positional, required) | — | The reviewed `<spec>.plan.md` | `ApproveCommand.java:37-38` |

**Exit codes**

| Code | Meaning |
|---|---|
| `0` | plan approved, `plan.json` written (a failed/suppressed Jira comment does not change this) |
| `1` | wrong file extension, spec validation problems, empty/missing knowledge base, git-state/plan-validator problems, or an unhandled exception (`ApproveCommand.java:53-56, 65-70, 71-74, 99-106, 128-131`) |

**Writes:** `<spec-base>.plan.json` (`ApproveCommand.java:112-114`). Prints
the spec and plan SHA-256 hashes that get embedded in the plan JSON. Opens no
diagnostics file of its own for the plan-compilation work above, but the
Jira write-back (when it runs) opens one under `.sdd/diagnostics/` scoped to
just that comment attempt (`JiraWriteBack.java:96-100`; see "Diagnostics"
under `sdd doctor`).

### `sdd plan revise <spec>.plan.md`

**What it does:** regenerates a plan with the prior round's Q&A folded in,
bumping `plan_version`. Re-runs impact analysis and drafting against the
current knowledge base, using the old plan's questions/resolutions as extra
context for the `planner` model. (`ReviseCommand.java:35-111`)

**Flags**

| Flag | Default | Description | Verified |
|---|---|---|---|
| `--workspace <dir>` | `.` | Workspace directory | `ReviseCommand.java:39-40` |
| `<planPath>` (positional, required) | — | The existing `<spec>.plan.md` to revise | `ReviseCommand.java:42-43` |

**Exit codes**

| Code | Meaning |
|---|---|
| `0` | plan revised |
| `1` | wrong file extension, spec validation problems, empty/missing knowledge base, or an unhandled exception (`ReviseCommand.java:55-58, 64-70, 71-74, 106-109`) |

**Writes:** overwrites `<planPath>` in place (version bumped), via
`SafeWrite.writeWithBackup` — the previous version is backed up first
(`ReviseCommand.java:98-101`).

## `sdd explore <spec>.md`

**What it does:** runs a read-only agent over every indexed repo to work out what the spec's free
text actually refers to, then writes the answer back into the spec as proposed `## Touchpoints` and
cited `## Evidence` bullets (`ExploreCommand.java`).

**Why it is not part of `sdd plan`.** `sdd plan approve` SHA-hashes `plan.md`, so the drafter's
evidence must be a deterministic function of the knowledge base and the spec. A model roaming the
estate is not. So exploration runs *before* the gate and materialises its findings into a file a
human reviews — the same discipline `PlanCommand.writeNormalized` applies to a normalized Confluence
spec, and the reason `Closure.expand` still takes no model.

**Tools the agent has** (`ExploreTools.java`): `list_repos`, `list_files`, `read_file`,
`search_code` (regex over every repo's real text, with an optional repo filter and path glob),
`search_symbols` (the indexed FTS corpus), `kb_resolve`, `propose_touchpoint`, `record_finding`,
`done`. There is **no** edit tool and **no** build tool — omitting `apply_edit` alone would not be
read-only, since a Gradle or npm task writes to disk freely.

**Two gates enforced in code, not in the prompt:**

- `propose_touchpoint` resolves through `KbEntities` before it is accepted, and a miss is refused
  with the reason. The explorer proposes hints; the knowledge base verifies them.
- `record_finding` refuses a citation whose `<repo>/<path>` this run never opened via `read_file` or
  surfaced through `search_code`, then **re-reads the cited file itself** and copies the line
  verbatim. The model never supplies the quoted text.

**Paths are estate-wide.** Every path argument is `<repo>/<path-in-repo>`; the prefix is required,
because resolving a bare `src/Foo.java` against whichever root happened to match first would make
the same argument mean different files across a large estate (`EstateJail.java`). Containment, the
`.git` ban and the symlink `toRealPath` check apply per repo root.

**Search quotas are per repo** (`EstateSearch.java`). A single global hit budget spent in path order
is exhausted inside the alphabetically-first repo, leaving every other one looking empty; here each
repo has its own allowance, a repo that exceeds it is named with its true match count, and an empty
result names the repos it searched.

| Option | Meaning |
|---|---|
| `--workspace <dir>` | workspace directory (default: current dir) |
| `--model <key>` | which `models:` entry to explore with (default: `planner`) |
| `--out <path>` | write the enriched spec here instead of in place |

**Budgets** come from `sdd.yml`'s `explore:` block — `turns` (default 200), `tokens` (8000000),
`wall_seconds` (7200), `context_soft_cap` (200000). They bound termination and reproducibility, not
cost. `wall_seconds` is also the first thing to make `AgentBudget.maxWall` configurable at all.

**`explore.single_tool`** (default false) advertises the nine operations as one declaration carrying
an `action` argument, for a gateway whose function-calling path breaks as the declaration set grows.
`Tools.route` translates the call back to its operation before anything else sees it, so `done`
interception, the wedge detector and the transcript are unaffected, and a call that names an
operation directly still passes through. Both gates are unchanged. It costs real quality — the
per-operation schemas are what tell the model which arguments an operation takes — so it is a
workaround, not a default.

| Exit | Meaning |
|---|---|
| `0` | the survey finished on `done(success)` |
| `2` | the survey ended some other way — budget, wedge, `done(blocked)` — everything found so far is still written, plus an Open Question saying the survey may be incomplete |
| `1` | unreadable spec, empty knowledge base, unknown `--model` key, or an unhandled exception |

**Seeing what it did.** Every tool call is printed as it happens, rendered as what it did rather
than as a tool name — `search_code tier\.lvc\.map  → 27 lines`, `read_file payments-api/src/...  →
118 lines`. After the run, the per-turn record `AgentLoop` builds is written to
`<workspace>/.sdd/explore/<specId>/transcript.jsonl`, one JSON object per model call:

| field | what it answers |
|---|---|
| `finish` | did the endpoint stop for length, a tool call, or a refusal |
| `prompt_tokens` / `completion_tokens` | is the window growing, is the reply being truncated |
| `content` | what the model said when it did NOT call a tool — the only thing that separates a refusal from an endpoint that cannot emit tool calls at all |
| `tool_calls` / `tool_results` | what it asked for, and what it got back |

**An endpoint that dies mid-run is reported as that, not as a finished survey.** The console says
`explored: ENDPOINT FAILED after N completed turns` with the transport error, the turns that did
happen are still written to `transcript.jsonl`, everything the notebook had reached is still merged
into the spec, and the spec gains an Open Question naming the failure. Exit is `2`. Previously the
exception propagated out of `Explorer.explore` and all of that was discarded.

`events.txt` alongside it holds the loop's own notes — `no tool call`, `malformed <tool>`,
`endpoint rejected oversized request — evicted and retried` — and they are printed too. Together
these are what turn "the proxy shows one request" into a reason.

**Writes:** the spec at `<spec>.md` (or `--out`), via `SafeWrite.writeWithBackup` — the previous
version is backed up first, and the rendered result is re-parsed as a self-check before it is
written. Existing touchpoints, evidence and every other section the human wrote are preserved, and
duplicates are dropped, so running it twice does not multiply the spec. A run that records nothing
leaves the spec untouched.

## `sdd implement <spec>.plan.json`

**What it does:** executes an approved `plan.json` across the estate,
repo-by-repo in dependency order, with the escalation-ladder coding models
(`sdd.yml`'s `run.escalation_ladder`, default `[coder, planner]`,
`RunSettings.java:16`). This is the work that happens *between* the two
gates. A run is identified by `<specId>-v<planVersion>` and persisted under
`.sdd/runs/<runId>/`. (`ImplementCommand.java:68-453`)

**Flags**

| Flag | Default | Description | Verified |
|---|---|---|---|
| `--workspace <dir>` | `.` | Workspace directory | `ImplementCommand.java:72-73` |
| `--resume` | off | Resume a paused or crashed run of this plan from its checkpoints | `ImplementCommand.java:75-76` |
| `--retry <repo>[,<repo>...]` | none | Re-run these already-settled (`SUCCEEDED` or `FAILED`) repos on resume; repeatable or comma-separated; implies `--resume`; retrying a `SUCCEEDED` repo discards its checkpoint and resets the branch to the plan base | `ImplementCommand.java:78-81, 250-256` |
| `--wait-endpoint` | off | After a pause caused by an unreachable model endpoint, poll the ladder's endpoints every 30s and auto-resume once they all answer | `ImplementCommand.java:83-85, 96, 110-144` |
| `<planJsonPath>` (positional, required) | — | The approved `<spec>.plan.json` | `ImplementCommand.java:87-88` |

**Exit codes**

| Code | Meaning |
|---|---|
| `0` | every repo `SUCCEEDED` ("COMPLETE") (`Orchestrator.java:181`) |
| `2` | the run finished but at least one repo did not succeed ("PARTIAL") (`Orchestrator.java:181`) |
| `3` | the run paused ("PAUSED") — an infra failure, an unreachable model endpoint, or the run's token budget exhausted (`Orchestrator.java:705-711, 199, 312, 491`) — resume with `sdd implement --resume <planJsonPath>` (or `--wait-endpoint`, for the endpoint case) (`ImplementCommand.java:399-408`) |
| `4` | unusable input: wrong file extension, no run to resume, unknown `--retry` repo, preflight/resume-prep problems, the run's lock is held by another process, or an unhandled exception (`ImplementCommand.java:171-174, 186-189, 225-228, 236-248, 257-266, 280, 411-414`, `exitCodeOnInvalidInput = 4` at `ImplementCommand.java:70`) |

**Writes:** under `.sdd/runs/<runId>/` — `plan.json` and `spec.md`
(snapshots taken at run start, `RunStore.java:50-61`), `lock` (held for the
duration; `RunStore.java:57-77`), atomically-published `state.json`
(`RunStore.java:106-195`), append-only `events.jsonl` (per-repo state
transitions; `RunStore.java:207-226`), `propagation.json`
(cross-repo publish plan; `RunStore.java:286-306`), and, per repo touched, a
`<repo>/` subdirectory with `agent-events.jsonl`, `transcript.jsonl` and
`edits.jsonl` (`RunStore.java:239-278`). When any plan edge needs a
`mavenLocal` fallback, also writes the Maven-local init script under the run
dir (`MavenLocalInit`, referenced at `ImplementCommand.java:324-332`).

## `sdd review <spec>.plan.json` — Gate 2 (read-only half)

**What it does:** the review half of Gate 2 (design line 66-67): checks the
whole estate out to its run checkpoints, rebuilds/verifies each `SUCCEEDED`
repo, re-checks actualized contracts against fresh extraction, computes
checkpoint drift, and renders `report.md` — the release runbook plus
per-section findings. Every checked-out repo is restored to its original
branch/commit in a `finally`, even on failure. No lock is taken, but the
command refuses (exit `4`) on every path — not only the mutating ones — while
`sdd implement`'s run lock is held, because racing it would report on an
estate that no longer exists; a *stale* lock only warns and reviews anyway,
since the crashed run is exactly the one a human needs to see.
(`ReviewCommand.java:104-248`)

**Known scope limitation, carried from earlier phases:** the rebuild pass
covers only repos in state `SUCCEEDED`, not "every affected repo"
(`RebuildPass.java:115-119`) — a repo that never ran, or that `FAILED`, is not
rebuilt.

A repo that DECLARED `compat: binary-compatible` or `compat: type-compatible` and
whose gate never reached a verdict fails the review even when everything else is
green, and gets a `## Compatibility gates that did not run` section naming why.
Exit `0` on such a run would be `sdd review` asserting a guarantee holds on the
strength of a check that did not happen. A gate that ran and passed, or a repo
that declared no guarantee, is silent (`SkippedGates.java`,
`ReviewCommand.java:231, 238-244`).

**Gate-2 Jira comment.** Strictly after `report.md` is durably written, and
only when the run's spec (`<runDir>/spec.md`) names Jira source keys, posts
one best-effort comment per source issue: `` sdd: review report for
`<spec-id>` `` plus the same decisions-summary line `report.md` itself
renders (`ReviewCommand.java:196, 203, 250-288`,
`ReviewReport.decisionsSummaryLine`). Same rules as Gate 1's comment (above):
gated on `atlassian.write_back: comment`, suppressible with `--no-comment`,
every failure warns and never changes the exit code
(`JiraWriteBack.java:29-34, 61-125`). Unlike Gate 1, this reuses the SAME
diagnostics file `sdd review`'s own Atlassian traffic already writes to,
rather than opening a second one (`ReviewCommand.java:287`,
`RunContext.java:94-100`).

**Bitbucket push and pull request, gated on `atlassian.pull_requests: true`.**
Off by default; with it off (or no `atlassian:` block at all) `sdd review`'s
behaviour and output are byte-for-byte unchanged from before this feature —
that check is the very first thing this step does, before any config or
credential is touched (`BitbucketReview.java:30-33, 45-49`). When on, for
every repo in state `SUCCEEDED` (`BitbucketReview.java:63-65`): pushes the
run branch to Bitbucket, then finds an OPEN pull request whose source is that
branch (updating its title/description if one exists) or opens a new one
(`BitbucketReview.java:77-121`). The PR description is `ReviewReport
.renderRepo`'s SAME rendered findings `report.md`'s Repos section carries for
that repo, plus the run id, spec id, and a link to the source Jira issue —
the two artifacts can never disagree about what a repo's run produced
(`BitbucketReview.java:151-160`). Called strictly AFTER `report.md` (and the
Jira comment, above) are durably written; every failure — one repo's push, or
its PR call — prints `` warn: bitbucket: <repo>: <detail> `` (or a bare `` warn:
bitbucket: <detail> `` for a config/credential failure before any repo is
reached) and never stops the attempt for the rest of the repos, never changes
the exit code (`BitbucketReview.java:21-34, 45-58, 68-74`).

**Flags**

| Flag | Default | Description | Verified |
|---|---|---|---|
| `--workspace <dir>` | `.` | Workspace directory (`scope = INHERIT`, so it also applies to the `approve`/`reject`/`redo` subcommands) | `ReviewCommand.java:64-66` |
| `--no-rebuild` | off | Skip the estate rebuild verification pass — the report is built from whatever branch the working trees happen to be on | `ReviewCommand.java:68-69, 165-183` |
| `--interactive` | off | After the report is written, walk every `PENDING` repo in order and prompt `[a]pprove / [r]eject / re[d]o / [v]iew diff / [s]kip / [q]uit` | `ReviewCommand.java:71-73, 209-217` |
| `--no-comment` | off | Suppress the Jira write-back comment even when `atlassian.write_back: comment` is configured | `ReviewCommand.java:75-77` |
| `<planJsonPath>` (positional, arity 0..1) | — | The approved `<spec>.plan.json` | `ReviewCommand.java:93-94` |

**Exit codes**

| Code | Meaning |
|---|---|
| `0` | every repo `SUCCEEDED`, no rebuild failure, no restore/staging failure, no checkpoint drift, every declared compatibility guarantee actually checked, and (if `--interactive`) no follow-up finding |
| `2` | any of the above conditions failed, OR (if `--interactive`) a follow-up (a refused decision, a squash refusal, a failed re-verify) demanded it — whichever is worse wins (`ReviewCommand.java:219-244`) — the Jira comment and Bitbucket push/PR step are never part of this: both are best-effort and cannot themselves produce a `2` |
| `4` | missing `<planJsonPath>`, no run found for it (`ReviewCommand.java:112-119`), the run's lock is held by `sdd implement` (`ReviewCommand.java:145-149`), or an unhandled exception (`ReviewCommand.java:131-134`, `exitCodeOnInvalidInput = 4` at `ReviewCommand.java:57`) |

**Writes:** `.sdd/runs/<runId>/review/report.md` and one
`review/<repo>.diff` per `SUCCEEDED` repo with a resolvable checkpoint
(`RunContext.java:103-128`). `report.md`'s sections, in the order they
appear in the document: Summary, Staging failures, Checkpoint drift, Repos,
Rebuild failures, Contract re-check, Branch restore failures, Diff failures,
Propagation, Release runbook (`ReviewReport.java:87, 409, 425, 222, 308, 363,
438, 450, 464, 70`). Summary, Repos and Release runbook always render; the
other six are omitted when they have nothing to report. The order is
load-bearing rather than
incidental: staging failures and checkpoint drift precede Repos deliberately,
because both invalidate what Repos says and a reader who meets "rebuild: OK"
first has already formed a verdict by the time the caveat arrives
(`ReviewReport.java:58-61`). If `--interactive` records any decision, it also writes
whatever `approve`/`reject`/`redo` write (below) for each decided repo, and
re-renders `report.md` once at the end of the walk
(`InteractiveReview.java:163-167`). With `pull_requests: true`, also rewrites
`state.json` with the opened/updated PR's id and link per repo
(`BitbucketReview.java:118-121`). Always opens one diagnostics file under
`.sdd/diagnostics/` for the whole invocation, covering the Jira comment
attempt, every Bitbucket REST call, and every git push
(`RunContext.java:94-100`; see "Diagnostics" under `sdd doctor`).

### `sdd review approve <repo> <spec>.plan.json` — Gate 2 (decision)

**What it does:** approves one repo's run branch — squashes it into the one
reviewed commit and records the new checkpoint. Refuses (does not apply) if
the repo's own state or its plan-graph invariants disallow it (e.g. an
unresolved upstream); a refusal is reported, not thrown.
(`DecisionCommand.java:187-192, 219-232, 241-302`)

**Then, only with `atlassian.pull_requests: true` and only after a squash
that actually applied** (not on a refused squash): force-pushes the squashed
branch, then merges its pull request — push first so the merge lands the
exact single commit the human reviewed
(`BitbucketDecisions.java:35-68`). **The ordering guarantee is the crux of
this step, and it holds by construction, not by a check:** `squashAndRecord`
calls `BitbucketDecisions.afterApprove` from only its two branches where
`SquashApprove` reported `applied()` — the no-op-squash branch and the
real-squash branch — never from the refusal branch, which returns before
`BitbucketDecisions` is reached at all (`DecisionCommand.java:258-268,
269-284, 285-301`). A refused local squash therefore can never be followed by
a Bitbucket merge. On any failure — the push, or the merge itself — local
state is already correct (the squash and its checkpoint write-back already
happened); this only warns (`` warn: bitbucket: could not merge PR for
<repo> — merge it manually in the Bitbucket UI: <detail> ``) and never
changes the exit code (`BitbucketDecisions.java:41-68`). With
`pull_requests` off, or no PR recorded for this repo, nothing is attempted.

**Flags**

| Flag | Description | Verified |
|---|---|---|
| `<repo>` (positional, required) | The repo to decide on | `DecisionCommand.java:56-57` |
| `<planJsonPath>` (positional, arity 0..1) | The approved `<spec>.plan.json` | `DecisionCommand.java:62-63` |
| `--workspace` | inherited from `sdd review` | `ReviewCommand.java:64-66` |

**Exit codes**

| Code | Meaning |
|---|---|
| `0` | approved and squashed cleanly (or squash was a no-op because there was nothing to squash); a failed/skipped Bitbucket merge does not change this |
| `2` | the decision itself was refused (`DecisionCommand.java:189-192`), the squash was refused (dirty tree or branch moved off checkpoint; `DecisionCommand.java:258-268`), or the post-squash branch restore failed (`DecisionCommand.java:326-336`) |
| `4` | missing `<planJsonPath>`, no run found, repo not in the plan, the run's lock is held, or an unhandled exception (`DecisionCommand.java:145-152, 173-181, 164-167`, `exitCodeOnInvalidInput = 4` at `DecisionCommand.java:221`) |

**Writes:** `.sdd/runs/<runId>/review/decisions.json` (the new verdict, via
optimistic-retry write with up to 5 attempts on a concurrent-write conflict;
`DecisionCommand.java:118-138`), an entry appended to the run's top-level
`events.jsonl` (same file `implement` appends repo-state transitions to;
`RunStore.java:213-226`), a rewrite of `state.json` with the new checkpoint
sha on a real squash (`DecisionCommand.java:290-292`), and a re-render of
`review/report.md`. With `pull_requests: true`, Gate-2 decision events (one
line per phase transition — squash refused/applied, checkpoint write,
merge attempted/succeeded/failed/not-attempted) are appended to the run's
diagnostics file (`DecisionCommand.java:304-312`,
`BitbucketDecisions.java:81-92`).

### `sdd review reject <repo> <spec>.plan.json [--reason <text>]`

**What it does:** rejects a repo's run branch — no squash, no downstream
re-verify. (`DecisionCommand.java:338-356`)

**Then, only with `atlassian.pull_requests: true` and only if this repo has a
recorded PR:** declines it (`BitbucketDecisions.java:96-117`). No local git
side effect to get out of order with, unlike `approve`'s squash-then-merge —
`decisions.json` already recorded `REJECTED` by the time this runs
(`BitbucketDecisions.java:27-29`). Best-effort, same as `approve`: a decline
failure warns (`` warn: bitbucket: could not decline PR for <repo>: <detail> ``)
and never changes the exit code.

**Flags:** same `<repo>`/`<planJsonPath>` as `approve`, plus:

| Flag | Default | Description | Verified |
|---|---|---|---|
| `--reason <text>` | `""` | Why the work was rejected | `DecisionCommand.java:340-341` |

**Exit codes:** `0` applied, `2` decision refused, `4` input/lock error — same
mechanics as `approve` (no squash step, so no squash-specific `2` case; a
failed/skipped PR decline never changes the exit code either).

**Writes:** `decisions.json`, `events.jsonl`, re-rendered `report.md` — same
as `approve`, minus the `state.json` checkpoint rewrite. With
`pull_requests: true` and a recorded PR, the decline is not itself logged as
a `gate2` diagnostics event (only `approve`'s squash/merge phases are;
`BitbucketDecisions.java` has no `gate2(...)` call in `afterReject`).

### `sdd review redo <repo> <spec>.plan.json [--reason <text>] [--no-reverify]`

**What it does:** marks a repo for re-implementation and, unless
`--no-reverify`, re-verifies its transitive downstream subtree against its
current checkpoints (design line 67's redo includes re-verify by definition,
not as an optional extra). (`DecisionCommand.java:358-376, 386-420`)

**No Bitbucket call at all.** `redo` never touches the repo's PR — no push,
no merge, no decline — so a PR opened by an earlier `sdd review` is left
exactly as it was: open, neither merged nor declined. A human redoing the
work is expected to push a new commit later, which the next `sdd review`
picks up as an update to the SAME open PR (`BitbucketReview.java:111-114`
finds it by open source branch).

**Flags**

| Flag | Default | Description | Verified |
|---|---|---|---|
| `--reason <text>` | `""` | Why the work must be redone | `DecisionCommand.java:361-362` |
| `--no-reverify` | off | Skip re-verifying the downstream subtree | `DecisionCommand.java:364-365` |

**Exit codes**

| Code | Meaning |
|---|---|
| `0` | redo recorded; downstream re-verify (if run) found nothing wrong |
| `2` | decision refused, the downstream staging failed, or a downstream repo's branch restore failed (`DecisionCommand.java:405-408, 418`) |
| `4` | input/lock error, same as `approve` |

**Writes:** `decisions.json`, `events.jsonl`, re-rendered `report.md`; prints
`then run: sdd implement --workspace <dir> --retry <repo> <planJsonPath>` as the next step
(`DecisionCommand.java:388-389`).

**PR lifecycle across the three decisions** (all gated on
`atlassian.pull_requests: true`; every step below is best-effort and never
changes any decision's exit code):

| Decision | Effect on the repo's Bitbucket PR |
|---|---|
| `approve` | force-pushed squashed branch, then merged — but ONLY when the local squash itself applied; a refused squash never reaches Bitbucket at all |
| `reject` | declined |
| `redo` | untouched — stays open; the next `sdd review` updates its description when the redo's new work lands |

## `sdd clean [<spec>.plan.json]`

**What it does:** deletes the run branches for repos that never got
`APPROVED` (design line 21/94) — everything `sdd implement` leaves sitting on
its run branch once a human is done deciding — and the run dir alongside
them, once every non-approved repo in it was cleanly deleted. `APPROVED`
repos and their branches are never touched. A decision token that cannot be
parsed, or a `state.json` branch name outside this run's own `sdd/<runId>/`
namespace, blocks the delete for that repo (and the run dir) rather than
guessing. Without `--force` this only prints what it would do.
(`CleanCommand.java:49-311`)

**Flags**

| Flag | Default | Description | Verified |
|---|---|---|---|
| `--workspace <dir>` | `.` | Workspace directory | `CleanCommand.java:53-54` |
| `--force` | off | Actually delete; without it, only prints what would happen | `CleanCommand.java:56-57` |
| `<planJsonPath>` (positional, arity 0..1) | every run dir in the workspace | A specific `<spec>.plan.json` to clean | `CleanCommand.java:59-61` |

**Exit codes**

| Code | Meaning |
|---|---|
| `0` | nothing needed cleaning, or every applicable delete succeeded |
| `2` | at least one per-repo or per-run failure (branch delete failed, corrupted decision token, foreign branch name, unreadable run dir) — reported, other runs/repos still processed (`CleanCommand.java:111-120`) |
| `4` | named plan has no run dir, OR any targeted run's lock is held by `sdd implement` (checked per run; one locked run does not stop others from being reported), or an unhandled exception (`CleanCommand.java:79-80, 97-102, 117-119, 121-124`, `exitCodeOnInvalidInput = 4` at `CleanCommand.java:51`) |

**Writes (only with `--force`):** deletes the qualifying `sdd/<runId>/…` git
branches (checking each out to the plan's base sha first if it happened to be
the currently checked-out branch) and, once every repo in that run is fully
handled, deletes the run dir itself — `state.json` last, so a crash mid-delete
still leaves something `sdd status`/`sdd clean` can find on a later pass
(`CleanCommand.java:280-302`).

## `sdd status [<spec>.plan.json]`

**What it does:** a read-only view of one or every run — run state and
Gate-2 decisions per repo, plus the lock's live/idle status. Never checks a
repo out, never touches the run lock, never writes anything.
(`StatusCommand.java:29-148`)

**Flags**

| Flag | Default | Description | Verified |
|---|---|---|---|
| `--workspace <dir>` | `.` | Workspace directory | `StatusCommand.java:45-46` |
| `<planJsonPath>` (positional, arity 0..1) | every run dir, newest-first | A specific `<spec>.plan.json` | `StatusCommand.java:48-50` |

**Exit codes**

| Code | Meaning |
|---|---|
| `0` | always, on any successful invocation — including "no runs found" and a per-run read failure (that run is reported and skipped, not fatal; `StatusCommand.java:92-93, 106-109`) |
| `4` | named plan has no run dir, or an unhandled exception at the top level (`StatusCommand.java:65-66, 84-87`, `exitCodeOnInvalidInput = 4` at `StatusCommand.java:43`) |

**Writes:** nothing.
