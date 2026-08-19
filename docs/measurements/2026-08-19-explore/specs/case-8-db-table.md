---
id: SPEC-E8
title: Add a status column to the client reference data
owner: azastashkov
status: draft
---

## Goal
Clients in `refdata.clients` cannot be deactivated — a decommissioned client keeps resolving to a
tier and keeps being entitled. Add a status to the client reference data and have every reader
treat a non-active client as unentitled.

## Requirements
- R1: `refdata.clients` gains a status, defaulting to active for existing rows.
- R2: Every service that resolves a client's tier from that data must treat a non-active client as unentitled.
- R3: The schema change must be applied by whichever service owns that schema, not by its readers.

## Acceptance Criteria
- A1: A deactivated client is refused entitlement everywhere it was previously granted.
- A2: The estate rebuilds green.
