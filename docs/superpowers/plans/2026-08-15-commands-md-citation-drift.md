# 27 verified citation corrections owed to `docs/commands.md`

## Context

`docs/commands.md` is this repo's command reference: for every `sdd` subcommand it states what the
command does, its flags, its exit codes and what it writes, and it grounds each of those statements
in a `file:line` citation into the source. The citations are the document's entire warrant — the
prose is only as good as the reader's ability to check it — so a citation that points somewhere else
is not a cosmetic defect. It is the document asserting something the code does not say.

While verifying the `sdd/retrieval-corpus` branch, **every `file:line` citation in both
`docs/commands.md` and that branch's plan doc was re-checked by opening the cited file and reading
the cited lines** — 145 citations in total. **31 were wrong.** Four belonged to the branch under
review and were fixed there, in `20805cf`: two in
`docs/superpowers/plans/2026-08-15-retrieval-corpus.md` (`ExplainCommand.java:174-176`, which the
previous correction round had itself broken, and `V2__fts_porter.sql:9`, which is the `tokenize =`
clause rather than the column list), and two in this file — `FtsRetriever.java:70`→`:82`, invalidated
by `9063806`'s own javadoc growth, and `AnswerNarrator.java:52`→`:56`, invalidated by `4be4625`
adding four lines to `SYSTEM_PROMPT` above the call. Three further citations into `SearchFacts.java`
were re-pointed in that same commit because its javadoc edit moved them.

**A fifth branch-owned citation escaped that audit** and was caught by the whole-branch review:
`AnswerNarrator.java:26-46`→`:26-50`, the `SYSTEM_PROMPT` constant itself, invalidated by the very
same four lines `4be4625` added to it. The audit corrected the citation *below* the constant and not
the one *at* it. It is fixed in this branch's final fix wave, which makes the real figures **32
wrong, five of them the branch's own**. The 27 below are unaffected.

**The remaining 27 are listed below, and were deliberately left untouched.** They are pre-existing
drift in the `sdd explain` call-1 paragraph, `sdd implement`, `sdd review` and the
`approve`/`reject`/`redo` subcommands — sections the retrieval branch never went near (it touched 51
lines of this file in total). Fixing them there would have put 27 range edits under a commit subject
about the retrieval record, and each one is a boundary judgement in code being read for the first
time — which is the exact mechanism that introduced a new false statement in each of the two
preceding correction rounds. They are recorded here instead so the verification is not lost and does
not have to be re-derived.

**Every correct citation in the tables below was verified by opening the file and reading the lines,
on 2026-08-15.** They are not inferred from diffs, from `git log`, or from grep.

---

## The two a reader would otherwise trust

Called out first because they fail silently in the most damaging way:

- **Two ranges run past end-of-file.** `ImplementCommand.java:65-423` cites a file that is **421**
  lines long, and `ReviewCommand.java:29-181` cites one that is **180**. A reader opening either at
  the stated range gets nothing at the end of it, with no indication of which end is wrong.
- **`DecisionCommand.java:300-301` is cited for `sdd review redo --reason` but is `Reject`'s
  `--reason`.** Both nested subcommands declare an identically-named option, so the cited lines *look
  exactly like* what the reader came for. `Redo`'s is at `:311-312`. The same trap sits one row down:
  `:303-304` is cited for `--no-reverify` and is `Reject.decide`.

Two more are semantically inverted rather than merely stale, and are flagged **semantic** in the
table: a citation filed under exit code `2` whose lines return `4`, and a citation for "a rewrite of
`state.json`" whose lines are the comment explaining when `state.json` must **not** be rewritten.

---

## `DecisionCommand.java` — 16

`sdd-cli/src/main/java/sdd/cli/review/DecisionCommand.java`, 380 lines. The subcommand classes
(`Approve` 205-218, `Reject` 297-306, `Redo` 308-326) and the helpers below them all sit lower in the
file than the citations assume, so nearly every pointer into the back half of this class is off.

| `commands.md` | cited as | verified correct | what the cited lines actually are |
|---|---|---|---|
| 446 | `:194-207` | `:205-218, 227-271` | `call()`'s `writeReport` arguments; `Approve` is 205-218 and `squashAndRecord` 227-271 |
| 452 | `:45-46` | `:56-57` | class-javadoc prose about concurrent approves, not the `@Parameters` |
| 453 | `:51-52` | `:62-63` | the javadoc's closing `*/` and the class declaration |
| 461 | `:160-163` | `:171-174` | **semantic** — filed under exit `2`, but these lines are the "is not in this plan" check, which returns **`4`** |
| 461 | `:233-237` | `:244-249` | **semantic** — cited for "squash refused (dirty tree or branch moved)", but these lines are the "no repo path or checkpoint on record" case, which returns `Followup.none()` |
| 461 | `:274-283` | `:285-295` | `afterRestore`'s javadoc rather than the method that produces the exit code |
| 462 | `:134-141, 144-152, 188-191` | `:145-152, 155-163, 199-202` | only 144-152 lands; 134-141 is an exception message plus `call()`'s signature, 188-191 is `writeReport`. The lock-held, not-in-plan and unhandled-exception paths are all outside the citation |
| 462 | `:196` | `:207` | `followup.scope()));`. `exitCodeOnInvalidInput = 4` is at 207, and again at 297 and 309 for `reject`/`redo` |
| 469 | `:252-254` | `:263-265` | **semantic, inverted** — the cited comment explains when `state.json` must **NOT** be rewritten; the rewrite is 263-265 |
| 475 | `:286-295` | `:297-306` | `afterRestore`; `Reject` is 297-306 |
| 481 | `:288-289` | `:299-300` | `afterRestore`'s warn println, not `Reject`'s `--reason` |
| 494 | `:297-359` | `:308-326, 336-370` | opens on **`Reject`'s** `@Command` and stops 11 lines inside `redoFollowUp` |
| 500 | `:300-301` | `:311-312` | **semantic** — **`Reject`'s** `--reason`, not `redo`'s |
| 501 | `:303-304` | `:314-315` | `Reject.decide`, not `--no-reverify` |
| 508 | `:342-358` | `:355-358, 368-369` | the exit-2 return is at 368-369, outside the cited range |
| 513 | `:327-328` | `:338-339` | a blank line and the opening of a javadoc. The `then run: sdd implement --retry …` string is 338-339 — and note the doc quotes that line **without** the `--workspace <dir>` the code actually prints |

## `RunStore.java` — 4

`sdd-cli/src/main/java/sdd/cli/implement/RunStore.java`, 547 lines.

| `commands.md` | cited as | verified correct | what the cited lines actually are |
|---|---|---|---|
| 377 | `:44-51` | `:50-61` | the `plan.json` and `spec.md` writes are at 55-56, past the end of the citation |
| 380 | `:201-215` | `:207-226` | `:201` is the **`state.json`** publish; the `events.jsonl` append is at 221, inside `appendTransition` |
| 381 | `:295-320` | `:286-306` | starts mid-`writePropagation` and runs on into `readPropagation` |
| 468 | `:201-215` | `:213-226` | same as row 380 — the decision-event overload is 213-215; the write itself is 221 |

## `QuestionInterpreter.java` — 2

`sdd-cli/src/main/java/sdd/cli/explain/QuestionInterpreter.java`, 402 lines.

| `commands.md` | cited as | verified correct | what the cited lines actually are |
|---|---|---|---|
| 255 | `:144-153` | `:180-196` | the `namesLine` vocabulary helper. The resolve-and-drop loop, with the `notes` entries the sentence describes, is 180-196 |
| 261 | `:291-352` | `:355-401` | `truncateEntities`/`intentOf`/`kindOf` and then `fallback`'s **javadoc**; the literal-matching body is 355-401 |

## Singles — 5

| `commands.md` | cited as | verified correct | what the cited lines actually are |
|---|---|---|---|
| 301 | `EvidenceCollector.java:49` | `:48` | the method's closing brace; `KbStatus.provenance(jdbi)` is built on 48 |
| 355 | `ImplementCommand.java:65-423` | `:66-421` | **past EOF** — the file is 421 lines. `:65` is the class javadoc's `*/`; the `@Command` opens at 66 |
| 365 | `ImplementCommand.java:84` | `:85-86` | a blank line; the `@Parameters` positional is 85-86 |
| 374 | `ImplementCommand.java:67` | `:68` | the `description =` attribute; `exitCodeOnInvalidInput = 4` is 68 |
| 399 | `ReviewCommand.java:29-181` | `:29-180` | **past EOF** — the file is 180 lines |

---

## Counted separately: 4 ranges that drifted but still cover their target

Recorded so a later pass does not have to re-adjudicate them, and **not** included in the 27. In each
of these the boundaries have gone stale, but a reader following the citation still finds what the
document claims, so they are imprecision rather than a false statement:

- `RunStore.java:57-77` (the run lock) — `acquireLock` is 63-99; the citation starts six lines early,
  inside `create`'s catch block, and stops well short, but contains the lock write at 67.
- `RunStore.java:106-195` (atomically-published `state.json`) — a 90-line span that crosses four
  unrelated methods but does contain `publishAtomically` at 178-193.
- `RunStore.java:239-278` (the per-repo `.jsonl` files) — contains `writeAgentEvents`,
  `writeTranscript` and `writeEdits`, and most of `writeJsonlAsIs`.
- `DecisionCommand.java:81-127` (the optimistic-retry decision write) — contains `MAX_ATTEMPTS = 5`
  at 95 and the head of `applyWithRetry`, whose body runs to 138.

Tightening these is optional. Fixing the 27 is not.

---

## How to apply

The corrections are mechanical **once verified**, and they have been verified — but do not apply this
table by find-and-replace.

Each row is a judgement about where a range should start and stop, taken by someone reading the file
on 2026-08-15. Source files move. Before editing any row, **re-open the named file at the cited line
and read it**, exactly as the audit that produced this table did: confirm the old citation is still
wrong in the way described, and confirm the replacement still lands on what the sentence claims. If
the file has moved since this note was written, the *diagnosis* in the last column is still the
useful part — it tells you what to search for — and the line numbers are not.

Two further rules, both of which this document exists because someone skipped:

1. **A `file:line` citation is a claim about the tree as it stands *after* your commit.** If your
   commit also edits the cited file, re-read the citation *after* that edit, not before. Two of the
   errors fixed in `20805cf` were introduced by earlier rounds that added lines above a cited call
   and edited `docs/commands.md` in the same commit without re-checking.
2. **Fix them in their own commit, or in commits scoped to one section at a time.** A correction
   round is where new errors get introduced; a diff a reviewer can actually check line-by-line
   against the source is the only defence.

When they are applied, this note should be deleted rather than left standing as a record of a debt
that no longer exists.
