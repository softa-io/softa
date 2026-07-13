# Softa Documentation Conventions

This file is the documentation map for the Softa codebase: **what lives where,
when to write each kind of doc, and what to avoid**.

Softa deliberately keeps **no decision-record archive** (ADRs / design
proposals were retired pre-production). The operating rules are:

- **Rationale lives inline where the rule is stated** — module READMEs,
  `CLAUDE.md` / `AGENTS.md`, `docs/ai/` guides, or a code comment when only
  the code can carry it. If you are tempted to cite a decision document,
  write the reason in place instead.
- **History lives in git** — superseded designs are recoverable from history;
  they are not kept as documents that can go stale and mislead readers
  (human or AI).

## What kinds of docs Softa uses

| Type | Purpose | Location |
|---|---|---|
| **Module README** | API surface, configuration, usage examples, behavioral invariants, known limitations | `<module>/README.md` |
| **AI agent guidance** | Copy-paste guides for AI agents, split by audience | `docs/ai/**` — `authoring/` (downstream apps) · `framework/` (this repo) · `studio-no-code.md` |
| **Migration guide & ledger** | When SQL is needed at all (vs annotation self-upgrade), and the ordered ledger of hand-run migrations | `deploy/migrations/README.md` + `deploy/migrations/<db>/V<N>__<slug>.sql` |
| **Top-level agent guidance** | What an AI / new contributor needs to operate the repo | `CLAUDE.md` / `AGENTS.md` |

## Decision tree — which doc should I write?

```
I have a code change to land
  │
  ├── Tiny change (typo, refactor, bugfix)
  │       → just a PR description. No doc.
  │
  ├── New API / config / behavior, single module
  │       → update that module's README.md
  │
  ├── Architectural decision affecting > 1 module
  │       → encode the rule + its rationale in CLAUDE.md / AGENTS.md (and the
  │         module READMEs it touches). No separate decision document.
  │
  ├── DB schema or data change
  │       → FIRST check deploy/migrations/README.md "Decision tree — do I
  │         need to write SQL?" — additive structure is annotation-only
  │         (scanner self-applies, incl. sys_* itself); SQL is only for
  │         data transformation / destruction / non-scanner-owned rows.
  │         If SQL is needed: deploy/migrations/<db>/V<N>__<slug>.sql
  │         AND mirror the end state into deploy/{demo,mini}-app/init_mysql/
  │         1.Metadata.ddl.sql (seed DML 2./3. files are retired — skip)
  │         AND register it in deploy/migrations/README.md
  │
  ├── AI agent will be operating in this area
  │       → downstream-app guidance: docs/ai/authoring/ ; framework-internal:
  │         docs/ai/framework/ (expand an existing guide, or add one)
  │
  └── New module / new starter
        → write <module>/README.md from day 1, even if minimal
```

## Anti-patterns (what NOT to do)

1. **Stage / phase markers in code comments.**
   ```java
   // Stage 5 Phase 2 — implements the scanner
   ```
   These age into noise. Git history is the lasting record. Code comments
   should explain _the present_, not the rollout journey.

2. **Citing decision documents instead of stating the reason.** No
   `per ADR-xxxx` / plan-item / rule-number citations — in code *or* docs.
   Inline the rule and the constraint that motivates it. (A reader must
   never need an archive to understand why the code is the way it is.)

3. **README that's not actually a reference.** A module's README should
   answer "how do I use this, and what invariants does it hold?" — not
   "what's the history of this?". History lives in git.

4. **Mixing prompts and reference docs.** AI prompt templates live in
   `docs/ai/` (split by audience), with a different update cadence
   (per capability change).

5. **Resurrecting retired designs from git history.** If an old design is
   recovered for reference, check the living docs first — the reason it was
   retired is usually recorded where its replacement is described.

## When this convention changes

This doc is itself meta-convention. Update it in the same PR that changes
the practice, with a brief rationale in the PR description.
