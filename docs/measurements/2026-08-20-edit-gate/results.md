# The edit gate rejected valid Java 21 — measured before and after

## How the bug was found

Not by reading. A probe of recorded run transcripts, run to decide whether a `glob` tool was worth
adding, produced this error table for `estate11 SPEC-203-v1 / trading-core` (301 turns, 386 tool
calls):

| tool | calls | hard errors |
|---|---|---|
| `read_file` | 118 | 1 |
| `search` | 80 | 0 |
| `run_gradle` | 22 | 0 |
| `list_files` | 56 | 6 |
| **`apply_edit`** | **108** | **45 (41%)** |

Of the 45, **37 were `Record Declarations are not supported`**, 2 text blocks, 2 `instanceof`
patterns. `JavaSyntax.firstError` called `StaticJavaParser.parse` with no `ParserConfiguration`, so
it parsed at JavaParser's default language level while the estate is Java 21. The indexer had it
right from the start (`SourceParser` sets `BLEEDING_EDGE`); `JavaSyntax` was the only
`StaticJavaParser` caller in the codebase and the only one never configured.

An agent whose correct edit is reverted rewrites it and is reverted again: **42 of that run's 301
turns** went to the loop. `trading-core` is one of the two repos that has repeatedly exhausted its
turn and token budgets.

## Why a deterministic replay rather than a live re-run

A live re-run samples a different model conversation every time, so turn counts and edit counts move
for reasons unrelated to any fix. `EditReplayHarness` instead replays the exact edits the recorded
agent produced, against the tree it ran on, through the **real** `FileTools.applyEdit` — same
matcher, same gate, nothing reimplemented — and asks only whether the gate's verdict changed.

Tree: `636a538`, the parent of checkpoint `fc19a2a`, materialised with `git archive`.

Baseline-faithful by construction: an edit the recording ACCEPTED is applied so the tree advances as
it did then; an edit it REJECTED is attempted and rolled back whatever the new verdict is, so every
later edit still meets the file state it originally met.

## Result

```
recorded: 100 apply_edit calls, 43 rejected (40 of them syntax)
replayed: 49 still accepted, 38 NOW ACCEPTED (were rejected),
           5 still rejected, 8 newly rejected
unreplayable (args truncated in the transcript at 2000 chars): 8
```

**38 of 43 replayable rejections now pass.** Every remaining rejection is something other than a
language-level refusal:

| remaining | cause | verdict |
|---|---|---|
| ×2 | `cannot create … file already exists` | replay artifact, see below |
| ×1 | `no match for the search block` | never a syntax issue |
| ×1 | `path escapes the repo: ../.superpowers/config.json` | correct refusal — the jail working |
| ×1 | `Parse error. Found "record"` | the agent smuggling `record` past the old gate with a unicode escape; moot now |

**Zero `Record Declarations are not supported`, zero `Text Block Literals`, zero `instanceof`
patterns remain.**

## Two limits of this method, both stated rather than discovered

**The 8 "newly rejected" are artifacts.** The transcript holds **four attempts** — turn numbers reset
three times (58, 44, 100, 99 turns; it succeeded on attempt 4, `deepseek-v4-pro`). Each real attempt
starts from a reset branch, while the replay concatenates all four onto one tree, so a file created
in attempt 1 still exists when attempt 3 tries to create it, and a later attempt's search block meets
state an earlier one left behind. Skipping the 8 unreplayable edits perturbs state the same way.
Both effects produce EXTRA rejections, never fewer, so neither can manufacture the headline result.
Segmenting the replay per attempt would remove them; it was not needed to answer the question.

**8 edits could not be replayed at all.** `AgentLoop` caps a recorded field at
`MAX_TRANSCRIPT_FIELD_CHARS = 2000`, so a large edit's arguments are cut mid-string and will not
parse. 40 of the 42 syntax rejections survived the cap, so the evidence covers 93% of the calls. The
harness counts them rather than dropping them silently — a replay that quietly covered less than the
run it claims to measure would be the same failure this project keeps finding elsewhere.

## What this does and does not establish

It establishes, deterministically and at zero model cost, that the gate no longer rejects the edits
it was rejecting. It does **not** establish that a run gets cheaper end to end: that depends on how
the agent behaves once its edits stop being reverted, which only a live run shows.

## Reproducing

```
git -C <repo> archive <base-sha> | tar -x -C /tmp/replay-base
SDD_REPLAY_TRANSCRIPT=<run>/<repo>/transcript.jsonl \
SDD_REPLAY_TREE=/tmp/replay-base \
SDD_REPLAY_OUT=/tmp/replay.txt \
  ./gradlew :sdd-agent:test --tests '*EditReplayHarness' --rerun-tasks
```

Gated on `SDD_REPLAY_TRANSCRIPT`, so it is inert in an ordinary build. Note `@Tag("measure")`
excludes nothing on its own — there is no `excludeTags` anywhere in this build, and the environment
variable is the entire gate.
