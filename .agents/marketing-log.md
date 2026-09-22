# Marketing Log

Running record of marketing work for AccessFlow — what shipped, what is in flight, what is next.
Context every marketing skill reads first: [`product-marketing.md`](product-marketing.md).
Newest entries first inside each section. Dates are ISO (YYYY-MM-DD).

## Channels & assets

| Channel | Handle / location | State |
|---------|-------------------|-------|
| X (company) | https://x.com/AccessFlowIO | Live since 2026-09; new-account reach limit ("graduated access") until X sees organic engagement |
| GitHub | https://github.com/bablsoft/accessflow (+ Discussions, Issues) | 6 stars / 3 forks on 2026-09-21 |
| Website | https://accessflow.io — `website/` in this repo, static, no build | SEO meta + JSON-LD on every page; sourced comparison pages under `/compare/` |
| Social share image | `website/og-image.png` | Re-cut at v2.6 on 2026-09-22. Re-cut whenever the version pill or engine count moves (`website/README.md` → "Regenerating og-image.png") |
| Product Hunt | account: new/barely used (warm up daily) | **Launch postponed 2026-09-22, no new date.** Plan is date-independent (T-0 relative) — see [`launch-producthunt.md`](launch-producthunt.md) |
| Founder personal account | TBD | Not yet used for AccessFlow content |
| LinkedIn / Bluesky / Mastodon / Reddit / HN | — | Not started |

## Done

- **2026-09-22 — v2.6.0 release thread posted.** Eight tweets, light-theme screenshots on 2, 3 and 6; the explainer and API/deploy-trace tweets are text-only because both features are API-only (no UI to show). https://x.com/AccessFlowIO/status/2102367885322567833
- **2026-09-22 — Demo assets re-cut in light theme** and two feature screenshots added (SQL review ruleset editor, privileged-access report) via `capture-shots.mts`. All in `~/Downloads/accessflow-ph-launch/`.
- **2026-09-22 — `website/og-image.png` re-cut at v2.6** (was v2.4). Rebuilt from a throwaway `_og-template.html` per `website/README.md`; 1200×630, 62 KB, template deleted.
- **2026-09-22 — Product Hunt launch postponed**, no new date. Plan rewritten date-independent.

- **2026-09-21 — Product Hunt launch plan + demo video.** Plan, listing copy, maker comment, 3-week supporter plan and launch-day runbook in `launch-producthunt.md`. 53 s captioned demo recorded with Playwright against the e2e stack (API remapped to 18080 to dodge `lst-backend`; realistic WireMock AI mapping registered at runtime; six `@acme.io` users seeded so masking shows; mock AI config renamed to `OpenAI (gpt-4o)`). Assets + re-runnable `record-demo.mts` in `~/Downloads/accessflow-ph-launch/`.

- **2026-09-21 — Website footer social link.** X icon link in the footer bar of all 59 pages + `https://x.com/AccessFlowIO` in the `Organization` JSON-LD `sameAs`. Branch `chore/AF-website-footer-x-link`, commit `369c40fa` (not yet pushed / PR'd at time of writing).
- **2026-09-21 — First X thread (product intro, company voice).** 7 tweets, "missing middle" hook, images from `website/images/docs/`. https://x.com/AccessFlowIO/status/2101946284990984227. Intended as the evergreen pinned intro (not yet pinned). Draft text in the session scratchpad only; canonical copy is the live thread.
- **2026-09-21 — Marketing context bootstrapped.** `.agents/product-marketing.md` v1 auto-drafted from the codebase.
- **2026-09-15 — v2.6.0 released** (help assistant, SQL review rules, access explainer, policy simulator, privileged-access report). No release announcement posted anywhere yet.

## In flight

- Nothing blocking. Next PH step is picking a launch Tuesday; week-1 tasks in `launch-producthunt.md` start three weeks before it.

## Next (suggested, unscheduled)

1. **Get out of X's new-account reach limit** — daily 30-min engagement routine from the social skill: follow ~20–50 accounts in DB/security/platform, reply with substance, quote-post from a founder account.
2. **Fill the TBDs in `product-marketing.md`** — customer language, founder voice, business-model intent. Sources: GitHub discussions, X replies, Reddit/HN threads. Consider `/marketing-skills:customer-research`.
3. **Content pillars + 2–4 week calendar** (`/marketing-skills:social` → "ongoing content plan"). Candidate pillars: the reviewed-statement idea; AI-agent governance / MCP; per-engine deep dives (18 connector pages already exist to repurpose); release notes; honest comparisons.
4. **Launch posts beyond X** — Show HN the Tuesday after PH, then r/devops / r/PostgreSQL / r/dataengineering, and LinkedIn for the security/compliance buyer.
5. **Social listening** — set up `.agents/listening-sources.md` (social skill's `references/listening.md`) for "shared prod credentials", "database access approval", "StrongDM alternative", "Teleport database access".
6. **Release cadence hook** — each `prep-gh-release` run should also produce a changelog-derived social post; milestones 2.7.0 (2026-09-30), 2.8.0 (10-13), 2.9.0 (10-27), 2.10.0 (11-10).

## Decisions & lessons

- **Company voice on X first**, founder voice TBD — chosen 2026-09-21. Revisit once a founder account is in play; founder-voice posts usually outperform company accounts for OSS.
- **No invented metrics, customers or testimonials** in any copy — social proof is thin (6 stars), lead with mechanism.
- **Honest competitor framing**: every comparison names where the other tool wins (`website/compare/`). Keep that in social copy too — AccessFlow is a proxy you *submit* to, not a transparent gateway.
- **Posting via Claude-in-Chrome works** (compose thread, attach images, stop before "Post all"). Uploads must come from a path the session can read — `~/Downloads` is not one; the repo tree and the session scratchpad are. No X MCP server is configured; adding one would need an X developer app with write scope.
- **Re-running the capture scripts needs three manual steps** not in the scripts: a compose override remapping the backend to host 18080 (8080 is taken by the user's `lst-backend`), a realistic WireMock AI mapping registered at runtime (the stock e2e mock answers "Mock analysis for AF-347"), and setting the mock `ai_config`'s API key and display name.
- **Shared footer/nav edits are site-wide mechanical changes** — no `lastmod`/`dateModified` bumps (see `.claude/patterns/website-drift.md`).
