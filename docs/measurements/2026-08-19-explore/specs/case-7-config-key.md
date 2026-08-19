---
id: SPEC-E7
title: Make the tier cache sweep interval tunable per environment
owner: azastashkov
status: draft
---

## Goal
`sweep-interval` is pinned at 30s. In the DR environment the sweep is too aggressive and in the
soak environment too slow. Make it configurable per environment, with the current value as the
default, and validate it the way the other interval settings are validated.

## Requirements
- R1: The sweep interval must be settable per environment without a code change.
- R2: An out-of-range value must fail at startup rather than at sweep time.

## Acceptance Criteria
- A1: Starting with the setting absent behaves exactly as today.
- A2: Starting with a negative value fails fast with a message naming the setting.
