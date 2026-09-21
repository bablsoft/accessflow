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
| Social share image | `website/og-image.png` | **Stale — still says v2.4** (site is on v2.6). Regenerate. |
| Founder personal account | TBD | Not yet used for AccessFlow content |
| LinkedIn / Bluesky / Mastodon / Reddit / HN | — | Not started |

## Done

- **2026-09-21 — Website footer social link.** X icon link in the footer bar of all 59 pages + `https://x.com/AccessFlowIO` in the `Organization` JSON-LD `sameAs`. Branch `chore/AF-website-footer-x-link`, commit `369c40fa` (not yet pushed / PR'd at time of writing).
- **2026-09-21 — First X thread (product intro, company voice).** 7 tweets, "missing middle" hook, images from `website/images/docs/`. https://x.com/AccessFlowIO/status/2101946284990984227. Intended as the evergreen pinned intro (not yet pinned). Draft text in the session scratchpad only; canonical copy is the live thread.
- **2026-09-21 — Marketing context bootstrapped.** `.agents/product-marketing.md` v1 auto-drafted from the codebase.
- **2026-09-15 — v2.6.0 released** (help assistant, SQL review rules, access explainer, policy simulator, privileged-access report). No release announcement posted anywhere yet.

## In flight

- Push `chore/AF-website-footer-x-link` and open the PR.

## Next (suggested, unscheduled)

1. **Pin the intro thread** on @AccessFlowIO.
2. **Regenerate `website/og-image.png`** for v2.6 — it is the image on the pinned thread and every social share.
3. **v2.6.0 release thread** on X (help assistant, SQL review rules, explainer, simulator) — the release is a week old and unannounced.
4. **Get out of X's new-account reach limit** — daily 30-min engagement routine from the social skill: follow ~20–50 accounts in DB/security/platform, reply with substance, quote-post from a founder account.
5. **Fill the TBDs in `product-marketing.md`** — customer language, founder voice, business-model intent. Sources: GitHub discussions, X replies, Reddit/HN threads. Consider `/marketing-skills:customer-research`.
6. **Content pillars + 2–4 week calendar** (`/marketing-skills:social` → "ongoing content plan"). Candidate pillars: the reviewed-statement idea; AI-agent governance / MCP; per-engine deep dives (18 connector pages already exist to repurpose); release notes; honest comparisons.
7. **Launch posts beyond X** — Show HN / r/devops / r/PostgreSQL / r/dataengineering (`/marketing-skills:launch`), and LinkedIn for the security/compliance buyer.
8. **Social listening** — set up `.agents/listening-sources.md` (social skill's `references/listening.md`) for "shared prod credentials", "database access approval", "StrongDM alternative", "Teleport database access".
9. **Release cadence hook** — each `prep-gh-release` run should also produce a changelog-derived social post; milestones 2.7.0 (2026-09-30), 2.8.0 (10-13), 2.9.0 (10-27), 2.10.0 (11-10).

## Decisions & lessons

- **Company voice on X first**, founder voice TBD — chosen 2026-09-21. Revisit once a founder account is in play; founder-voice posts usually outperform company accounts for OSS.
- **No invented metrics, customers or testimonials** in any copy — social proof is thin (6 stars), lead with mechanism.
- **Honest competitor framing**: every comparison names where the other tool wins (`website/compare/`). Keep that in social copy too — AccessFlow is a proxy you *submit* to, not a transparent gateway.
- **Posting via Claude-in-Chrome works** (compose thread, attach images, stop before "Post all"). No X MCP server is configured; adding one would need an X developer app with write scope.
- **Shared footer/nav edits are site-wide mechanical changes** — no `lastmod`/`dateModified` bumps (see `.claude/patterns/website-drift.md`).
