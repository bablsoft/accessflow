# Agents

Four read-only agents that give a change a second opinion before it becomes a PR. They are
dispatched by [`impl-gh-issue`](../skills/impl-gh-issue/SKILL.md) at step 5b (and
[`add-engine`](../skills/add-engine/SKILL.md) at step 13): `af-verifier` and `af-reviewer` always
run; the two specialists run when their paths are touched. All four work standalone on any
branch.

| Agent | Job | Tools |
|---|---|---|
| [`af-verifier`](af-verifier.md) | Maps the touched paths onto the gates that actually apply and **runs** them. Reports `PASS` / `FAIL` / `NOT_RUN` / `BLOCKED` per gate. | Read, Grep, Glob, Bash |
| [`af-reviewer`](af-reviewer.md) | Cross-cutting reviewer: the fan-out completeness tables, the "same commit set" drift rules, website drift, backend↔frontend validation parity. Returns Blockers / Concerns / Nits with `file:line` evidence. | Read, Grep, Glob, Bash |
| [`af-java-reviewer`](af-java-reviewer.md) | Backend/engine code specialist (`backend/**`, `engines/**`): CLAUDE.md backend rules + the backend/engine [`patterns/`](../patterns/) checklists, incl. test parity. Same output format. | Read, Grep, Glob, Bash |
| [`af-frontend-reviewer`](af-frontend-reviewer.md) | Frontend/e2e code specialist (`frontend/**`, `e2e/**`): the frontend non-negotiables + the frontend/e2e [`patterns/`](../patterns/) checklists, incl. e2e selector drift. Same output format. | Read, Grep, Glob, Bash |

## Why neither has an Edit tool

That is the whole design. An agent that can fix what it reviews will fix it — and then report
green, having quietly changed the thing it was meant to judge. Removing the tool makes a clean
report mean something.

The same reasoning splits the pair in two. The implementer is invested in its own work and
rationalises past its own choices; a fresh agent reading cold catches what the author talked
itself out of noticing.

## Why the split between them

`af-verifier` owns mechanical truth — it runs commands and reports exit codes. `af-reviewer` owns
judgment — conventions, completeness, test quality. Keeping them separate means the reviewer never
has to guess whether the build passes, and the verifier never has to have an opinion.

AccessFlow suits this unusually well: with 30 CI checks, the architecture and parity tests, engine
SHA pins and the patterns' `## Required` checklists, a reviewer here has real ground truth to
check against rather than only taste.

The review side splits once more, by ownership rather than stack size: the **specialists own
code-level correctness within their stack** (`af-java-reviewer` for `backend/`+`engines/`,
`af-frontend-reviewer` for `frontend/`+`e2e/`), while **`af-reviewer` owns everything that spans
files, stacks, or artifacts** — fan-out completeness, drift, locale parity, validation parity,
website. Every finding thus has exactly one natural owner; when two reviewers still report the
same `path:line`, the dispatching skill keeps the specialist's version.

## The one hard rule

**A gate that was not run is `NOT_RUN`, never `PASS`.** A wrong `FAIL` costs a few minutes; a
wrong `PASS` ships a defect. The same applies to review findings — every one cites `path:line`,
and anything unchecked goes under **Not checked** rather than being implied as fine.

## This gate reports, it does not block

Findings are surfaced, not enforced. Fix what you agree is a genuine Blocker; **explicitly rebut
what you disagree with, with reasoning** — a reviewer finding can be wrong, and acting blindly on
a wrong Blocker is worse than having no review. Surviving Concerns belong in the PR description
under **Review notes**, where a human sees them.

This mirrors how the hooks shipped: advisory first, measure the false-positive rate on real work,
tighten only once it has earned trust.

## Gotcha: agents load at session start

A newly added or renamed agent is **not dispatchable until the Claude Code session restarts** —
`Agent type 'af-verifier' not found` means exactly that, not a malformed file. Editing an existing
agent's body has the same constraint. Restart, then dispatch.

## Adding an agent

- `name:` must equal the filename stem, or it will not resolve.
- Grant the **narrowest** tool set that lets it do its job. If it should not change files, do not
  give it Edit or Write and do not rely on telling it not to.
- Say what it must *not* do as explicitly as what it must — the failure mode for a review agent is
  padding its findings to look thorough, and for a verifier it is claiming an unearned pass.
