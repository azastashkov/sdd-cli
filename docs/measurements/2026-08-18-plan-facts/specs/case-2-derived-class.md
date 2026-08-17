---
id: SPEC-M2
title: FixSessionListener gains a reject callback
owner: azastashkov
status: draft
---

## Goal
`FixSessionListener` in the shared FIX library must notify implementors when the
venue rejects a session-level message, so every implementing class across the
estate can react instead of silently dropping the reject.

## Requirements
- R1: Add `onSessionReject(String reason)` to the shared FixSessionListener interface.
- R2: Every class in the estate that implements FixSessionListener must implement the new callback.

## Acceptance Criteria
- A1: The estate compiles with no unimplemented-abstract-method errors.
- A2: Each implementor logs or counts the reject rather than ignoring it.

## Touchpoints
- class: FixSessionListener
