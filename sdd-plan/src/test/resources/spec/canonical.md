---
id: SPEC-9
title: 'Loyalty tiers: phase one'
owner: ana
status: draft
---

## Goal
Add loyalty tiers to pricing.

Gold customers get the discounted rate.

## Background
Pricing today is flat per SKU.

## Requirements
- R1: Price response includes the customer tier.
- R2: Tier rules load from configuration.

## Acceptance Criteria
- A1: GET /price returns tier for gold customers.

## Constraints
- C1: No schema change to the pricing database.

## Touchpoints
- repo: svc-pricing
- endpoint: GET /price

## Out of Scope
- Loyalty point accrual

## Open Questions
- Q1: Which service owns tier configuration?

## Attachments
- tier-diagram.png
