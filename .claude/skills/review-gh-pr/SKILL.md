---
name: review-gh-pr
description: Review an existing AccessFlow pull request — check the head out into an isolated worktree, fan out the read-only agents (change summary, conventions, security, frontend, cross-cutting drift), explain what the PR actually does, then merge the findings into one numbered list and let you pick which ones to keep. Never posts to GitHub; renders the selected findings as markdown for you to paste. Trigger when the user says "review PR #N", "review this PR", "what does this PR do", "summarize PR #N", "security review the PR", or passes a GitHub pull-request URL.
---

# Review a GitHub pull request

**CLAUDE.md is the authoritative rulebook** and the agents under
[`.claude/agents/`](../../agents/README.md) do the actual reviewing. This skill only adds the
workflow that gets a PR onto disk, fans them out, and turns their reports into something you can
choose from.

Three properties define it:

- **It explains the PR before it critiques it.** The first thing you get is what the change
  actually does — read from the diff, not from the description — so the findings that follow have
  somewhere to land.
- **Your working tree is never touched.** The PR head goes into a throwaway git worktree. You can
  review a PR in the middle of your own work, on a dirty tree, without stashing.
- **It never writes to GitHub.** Not a comment, not a review, not an API call. The selected
  findings come back as markdown you paste yourself.

## Inputs

A PR number (`735`), a `#`-prefixed number (`#735`), or a GitHub pull-request URL. Optional flag:

| Flag | Effect |
|---|---|
| `--verify` | Also dispatch `af-verifier` to **run** the real build gates locally. Slow — a backend PR pays a full `mvn verify -Pcoverage`. Without it, report `gh pr checks` instead, since CI already ran them. |

If the user omits the number, ask for it once and stop. **Do not guess from `gh pr list`** — the
wrong PR reviewed silently is worse than a question.

## Workflow

### 1. Resolve the PR

```bash
gh pr view <n> --json number,title,body,url,state,author,headRefName,headRefOid,baseRefName,isCrossRepository,additions,deletions,files
gh pr checks <n>
gh api repos/<owner>/<repo>/pulls/<n> --jq .base.sha      # the PR's base commit — see below
```

`gh pr checks` exits non-zero when checks are failing or pending — that is data, not an error;
capture the output and carry on.

**`base.sha` is load-bearing — do not substitute `origin/<baseRefName>` for it.** For a *merged*
PR the head is already an ancestor of the base branch, so `git merge-base HEAD origin/main` returns
HEAD itself and the diff comes back **empty** — every agent would then report "no files touched,
VERDICT: approve", which is the worst possible failure: a silent false pass. `base.sha` is the
commit the PR was actually opened against, and it gives the right diff for open and merged PRs
alike. Sanity-check it: `git diff --shortstat <base.sha>...<headRefOid>` must match the
`additions`/`deletions` from `gh pr view`. If it does not, stop and say so rather than reviewing a
diff you cannot account for.

(Note `gh pr view --json files` caps at 100 entries, so on a large PR its file list is short of the
real count. Use it to pick which agents to dispatch, but take counts from `git diff`.)

If `state` is `MERGED` or `CLOSED`, say so and ask whether to continue before spending the agents.
Reviewing merged code is legitimate (post-mortem, learning the codebase) but rarely what someone
means by "review this PR".

### 2. Check the head out into an isolated worktree

```bash
git fetch origin <baseRefName>
git fetch origin pull/<n>/head          # works for fork PRs too — do not add a fork remote
git worktree add --detach .claude/worktrees/pr-<n> <headRefOid>
git -C .claude/worktrees/pr-<n> cat-file -e <base.sha>^{commit} || git fetch origin <base.sha>
```

Detached HEAD is correct and sufficient: the worktree shares the parent `.git`, so both the head
and `base.sha` resolve in it. Confirm the diff is non-empty before spending any agent:

```bash
git -C .claude/worktrees/pr-<n> diff --shortstat <base.sha>...HEAD
```

An empty result here means something is wrong with the refs — never dispatch on it.

If `.claude/worktrees/pr-<n>` already exists from an earlier run, remove it and recreate — a stale
worktree pinned to an older head silently reviews the wrong code.

**Never** `gh pr checkout`, and never `git checkout` in the user's tree. Switching their branch is
the one thing this skill exists to avoid.

### 3. Fan out the reviewers

Pick from `.files[].path` in the step-1 JSON, and dispatch **all of them in a single message** so
they run concurrently.

| Condition | Agent |
|---|---|
| always | **`af-pr-summarizer`** — what the change does, per-area breakdown, interface surface (endpoints, migrations, enums, config, events), and where the risk concentrates. Describes; does not judge. |
| always | **`af-reviewer`** — cross-cutting: fan-out completeness tables, "same commit set" drift (docs, website, connector pins, locale parity, backend↔frontend validation parity). |
| always | **`af-security-reviewer`** — proxy bypass, row-security failing open, self-approval and tenant scoping, credential handling, the auth surface, SSRF, audit tamper-evidence, plugin supply chain. |
| any path under `backend/` or `engines/` | **`af-java-reviewer`** — CLAUDE.md backend rules + the backend/engine pattern checklists. |
| any path under `frontend/` or `e2e/` | **`af-frontend-reviewer`** — the frontend non-negotiables + the frontend/e2e pattern checklists. |
| `--verify` only | **`af-verifier`** — maps touched paths onto the gates that apply and runs them. |

Every agent prompt **must** carry these two overrides verbatim, because each agent's body assumes it
is reviewing a local branch in the primary working tree:

> Your review root is `<absolute worktree path>`. Begin every Bash call with
> `cd <absolute worktree path>` and treat every relative path in your instructions as relative to
> it. Wherever your instructions say `$(git merge-base HEAD origin/main)`, use the literal commit
> `<base.sha>` instead — this is PR #`<n>` against `<baseRefName>`, and on a merged PR the
> `merge-base ... origin/main` form silently yields an empty diff. Your diff is
> `git diff <base.sha>...HEAD`, and it covers `<n>` files.

Also pass the PR title and body, so a reviewer can tell whether an omission is deliberate.

If you get `Agent type '…' not found`, the session predates the agent file — agents load at session
start. Tell the user to restart; do not silently drop that reviewer from the run.

**Say which agents you dispatched and which you skipped, and why.** A frontend-only PR should
produce "`af-java-reviewer` not dispatched — no `backend/` or `engines/` paths", not silence.

### 4. PR hygiene pass

Do this yourself from the step-1 metadata — no agent has it, and none of it needs the code:

- **CI**: the `gh pr checks` result. A failing required check outranks every style finding below.
- **Branch name** matches `(feature|fix|chore)/AF-<n>-description` per CLAUDE.md → Git Workflow.
- **Body references the issue** (`Closes #<n>` or an `AF-<n>` token).
- **Size**: flag anything over ~800 changed lines as hard to review in one pass, and say which files
  dominate it.
- **Commit subjects**: imperative mood, ≤ 72 chars — `git -C .claude/worktrees/pr-<n> log --oneline <base.sha>..HEAD`.
- **Drift declared**: if the diff touches user-facing surface, does the body list the touched
  `docs/` and `website/` files, per `impl-gh-issue`'s definition of done? (Whether they *should*
  have been touched is `af-reviewer`'s call — you only check whether the description says so.)

### 5. Merge the reports

Collate everything into one set:

- Two reviewers on the same `path:line` → keep one. The **specialist** beats `af-reviewer`;
  **`af-security-reviewer` beats the specialist** when both claim it, because the security framing
  is the one that decides severity.
- The overall verdict is the **strictest** returned: `revise` > `approve-with-concerns` > `approve`.
- An agent's `NOT CHECKED` items are **not** clean. Carry them into your summary as unverified.
- **`af-pr-summarizer` contributes no findings.** Its output is the briefing in step 6, never an
  entry in the numbered list. If it noted something that smells wrong, that is a lead for a
  reviewer, not a finding of its own.

### 6. Lead with what the PR does

Relay `af-pr-summarizer`'s briefing **before any finding** — what it does, why, the per-area
breakdown, the interface surface, notable decisions, and where the risk concentrates. This is the
part someone reads to decide whether they even agree with the change; findings about a change
nobody understands are noise.

Two things from it are load-bearing and must not be dropped when you relay it:

- **`DESCRIPTION ACCURACY`.** If the PR body claims something the diff does not do, or the diff
  does something the body never mentions, that goes near the top in its own right. Every human
  reviewer after you is anchored on that description.
- **`INTERFACE SURFACE`.** Endpoints, migrations, enum values, config knobs, events, engine pins.
  These are what break other people, and an explicit "no migrations, no new endpoints" is as useful
  as a list.

Scale it to the change: a three-file PR gets a short paragraph, not a filled-in template with
"none" six times.

### 7. Present the findings

One numbered list, **numbering continuous across severities** so a selection like `3,7-9` is
unambiguous. Group Blockers → Concerns → Nits, hygiene findings included and numbered like any
other. Each entry: the `path:line`, what is wrong, the reviewer that raised it, and the one-line
consequence.

Close with the merged verdict, the CI status, and anything left unverified.

### 8. Let the user select

Ask for a selection and accept any of:

```
1,3,7-9      blockers      blockers+concerns      all      none
```

Follow-up edits are part of this step — "drop 5", "keep 3 but reword it", "merge 8 and 9". Iterate
until they are happy.

Say plainly, once: **nothing has been sent anywhere, and nothing will be.**

### 9. Render — do not post

Open with two or three sentences of the **what it does** summary, so the pasted comment stands on
its own for anyone reading it on GitHub, then the **selected findings only**, grouped by severity.
Include `DESCRIPTION ACCURACY` if it flagged a real mismatch — that is a comment worth making even
when every other finding is dropped. Link each finding to its exact line at the reviewed commit:

```
https://github.com/bablsoft/accessflow/blob/<headRefOid>/<path>#L<line>
```

Use the commit SHA, not the branch name — a branch-relative link rots the moment the author pushes.

Then do all three:
- write it to the scratchpad as `pr-<n>-review.md` and send it with `SendUserFile`,
- pipe it to `pbcopy` so it is on the clipboard ready to paste,
- print it in the transcript as one fenced block.

**Non-negotiable: this skill never runs `gh pr comment`, `gh pr review`, `gh api -X POST`, or any
other GitHub write.** Not when asked mid-run either — if the user wants it posted, say that this
skill does not post and let them paste, or let them ask for a posting step as a separate,
explicitly-confirmed action.

### 10. Clean up

Ask once whether to keep the worktree — after a review with real Blockers, digging around in the
actual code is often the next thing wanted. Otherwise:

```bash
git worktree remove .claude/worktrees/pr-<n> --force
git worktree prune
```

**Never leave a worktree behind without saying where it is.** If the user keeps it, tell them the
path and the cleanup command.

## Acceptance checklist

- [ ] PR resolved by number/URL; merged or closed state surfaced before spending agents.
- [ ] Head checked out into `.claude/worktrees/pr-<n>`; the user's branch and working tree untouched.
- [ ] `af-pr-summarizer`, `af-reviewer` and `af-security-reviewer` dispatched; path-matched
      specialists dispatched; `af-verifier` only under `--verify`; all in one message.
- [ ] Summary relayed **before** the findings, carrying `DESCRIPTION ACCURACY` and
      `INTERFACE SURFACE`.
- [ ] `base.sha` resolved and its `git diff` shortstat reconciled against `gh pr view`'s totals.
- [ ] Every agent prompt carried the review-root and `<base.sha>` overrides.
- [ ] Skipped agents named explicitly, with the reason.
- [ ] Hygiene pass done from PR metadata: CI, branch name, issue link, size, commit subjects,
      declared drift.
- [ ] Findings merged, deduped by owner precedence, numbered continuously, strictest verdict stated.
- [ ] `NOT CHECKED` items carried through as unverified, not dropped.
- [ ] User selected; selection honoured exactly.
- [ ] Markdown rendered to scratchpad + clipboard + transcript. **Nothing posted to GitHub.**
- [ ] Worktree removed, or its path and cleanup command handed to the user.

## Anti-patterns

- **Reviewing from `gh pr diff` alone.** Most real findings come from repo-wide greps and reading
  full files — the fan-out sweeps, the `@JsonIgnore` sweep, the `permitAll` ↔ docs diff. A diff
  cannot produce any of them. The worktree is the point.
- **`gh pr checkout` in the user's tree.** Switches their branch, needs a clean tree, and strands
  them somewhere else if the skill errors mid-run.
- **Posting anything.** Including "just a quick summary comment". The user chose to paste.
- **Padding the list.** An empty review is a real result. Say "no Blockers" and mean it.
- **Reporting `NOT CHECKED` as clean.** A gate that was not run is `NOT_RUN`, never `PASS` — the
  same rule the agents live by applies to your summary of them.
- **Reviewing a stale worktree** from a previous run of the same PR number.
- **Diffing against `origin/main` on a merged PR.** The head is already an ancestor, the diff is
  empty, and every agent returns a confident `approve` on nothing. Always `base.sha`.
- **Letting a failing CI check get buried** under nits. It goes at the top.
- **Opening with findings.** A list of defects about a change the reader cannot yet describe is
  unreadable. The summary comes first, always.
- **Relaying the PR description as the summary.** The description is a claim; the diff is the
  evidence. Reporting the two agree, when they do, is a real result — asserting it without checking
  is not.
