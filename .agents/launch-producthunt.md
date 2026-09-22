# Product Hunt launch plan — AccessFlow

Context: [`product-marketing.md`](product-marketing.md) · progress: [`marketing-log.md`](marketing-log.md)
Written 2026-09-21; **launch postponed 2026-09-22, no new date set** — the plan below is written relative to **T-0**, the launch day, so it survives any date. Starting position: new PH account, no supporter list, no email list, 6 GitHub stars, @AccessFlowIO under X's new-account reach limit.

## Recommendation

**Pick a Tuesday, three weeks out, that lands on or just after a release tag.** Tue–Thu are the strongest PH days; a launch goes live at 00:01 PT; and three weeks is the minimum to warm a new PH account and build a supporter list from nothing. The nearest milestones are 2.8.0 (due 2026-10-13), 2.9.0 (2026-10-27) and 2.10.0 (2026-11-10) — any of them works as the peg. The listing never has to name a version; it is "AccessFlow".

Do **not** launch inside a week of deciding: a cold account with nobody lined up is the one failure mode entirely within your control.

**Honest expectation.** With zero network the realistic win is a polished listing, 50–150 upvotes, a dozen substantive comments, a permanent backlink and the first batch of customer language — not Product of the Day. Treat it as the first of many launches (every release is another one).

## Readiness gate (SLC)

- Simple — one thing: a request is reviewed before it runs. ✔
- Lovable — the editor + AI + live rules + masked results demo shows it. ✔
- Complete — install to first reviewed query works via `docker compose up` and the first-run wizard. ✔
- Not in "one more feature" territory: launch on whatever 2.8.0 ships; don't hold for 2.9.

## Assets

| Asset | Status | Where |
|---|---|---|
| Demo video, 53 s, captioned, 1440×900 H.264 | **Done** | `~/Downloads/accessflow-ph-launch/accessflow-demo-captioned.mp4` |
| Same, no captions (for a voiceover) + `.srt` | Done | same folder |
| Gallery stills (editor+AI, pending detail, review queue, masked results, audit log) | Done | `gallery-*.png`, same folder |
| Recording script (re-runnable against the e2e stack) | Done | `record-demo.mts` — needs `E2E_API_BASE=http://localhost:18080`, the WireMock override and the acme.io users; see marketing-log 2026-09-21 |
| Hero/thumbnail 240×240 + gallery 1270×760 crops | TODO | crop from `og-image.png` (after the v2.6 regen) and the stills |
| Video hosted (PH takes a YouTube/Loom link) | TODO | upload captioned MP4 to YouTube as unlisted, or add a voiceover to the clean cut first |
| `website/og-image.png` | **Done 2026-09-22** | re-cut at v2.6 (procedure: `website/README.md` → "Regenerating og-image.png"); re-cut again if the version pill or engine count moves before launch |
| Changelog entry for the pegged release | ships with the release | `website/changelog/` via prep-gh-release |

Known blemish in the video and the two feature screenshots: the sidebar header reads `v0.0.0` (e2e build) and the demo results show `display_name NULL` for the seeded users. Both are small; re-capture against a versioned build if you want the real version string.

Re-running the capture scripts needs three manual steps that are **not** in the scripts (see the marketing log entry for 2026-09-21): remap the backend to host port 18080 via a compose override, register the realistic WireMock AI mapping (the stock e2e mock answers "Mock analysis for AF-347"), and set the mock `ai_config`'s API key + name.

## Listing copy (draft — edit freely)

**Name:** AccessFlow
**Tagline** (≤60 chars): `Open-source approval proxy for databases, APIs and CI/CD` (56)
 alt: `Every production query reviewed before it runs. Open source.` (60)
**Description** (≤260 chars, 253):
> Self-hosted access proxy that puts AI risk review, human approval, masking, row-level security and a hash-chained audit log in front of 18 databases, outbound APIs and CI/CD deploys. Reviews the statement, not the session. Apache 2.0, docker compose up.

**Topics (3):** Open Source · Developer Tools · Security (alternates: Databases, Privacy)
**Pricing:** Free / open source. **Links:** https://accessflow.io, https://github.com/bablsoft/accessflow
**Gallery order:** 1 hero (OG) → 2 editor with live rule findings + AI verdict → 3 review queue → 4 masked results → 5 audit log → 6 pending detail with timeline. Video first if PH allows.

**First (maker) comment** — post within 5 minutes of going live:
> Hi PH — maker here.
>
> Most teams pick one of two bad defaults for production data access: a shared credential in a password manager (fast, unbounded blast radius, no record) or a ticket queue a DBA owns (safe, but slow enough that engineers route around it).
>
> AccessFlow is the middle: an open-source proxy you submit a query to. It's parsed at the AST, risk-scored by an AI you choose (Anthropic, OpenAI, Ollama, Hugging Face — or none), flagged by deterministic SQL rules, routed to a human approver, and only then executed — with masking, row-level security and row caps enforced at run time. Every step lands in an INSERT-only, hash-chained audit log.
>
> The same pipeline covers outbound REST/SOAP/GraphQL/gRPC calls and CI/CD deploys (a fail-closed gate your pipeline blocks on), and AI agents go through it too via a built-in MCP server — no side door.
>
> Honest limits: it's a proxy you submit to, not a transparent gateway your psql session runs through — if you want that, Teleport or hoop.dev fit better today, and our comparison pages say so. 18 engines, Apache 2.0, `docker compose up`.
>
> I'd love to hear: which of the two defaults does your team live with, and what would it take to switch? I'll be here all day.

**Ten answers to have ready** (comments come fast): Is the AI deciding? (no — scores and explains; humans or explicit policy decide; works with AI off) · Why not just Postgres RLS + roles? (no approval step, no cross-engine, no audit chain) · vs Bytebase / Teleport / StrongDM / hoop.dev (link the compare pages) · Does it slow engineers down? (routing auto-approve, JIT grants, break-glass) · Hosted version? (no — self-hosted only) · Business model? (be honest: none yet) · Which engines? (18, link connectors) · Wire-protocol / drop-in? (roadmap) · How is the audit log tamper-evident? (INSERT-only role, hash chain, signed exports) · Can my AI agent use it? (MCP server, scoped keys, on-behalf-of).

## Three-week plan

### Week 1 (T-21 → T-15) · warm up and set up
- **PH account (you, daily, 10 min):** complete profile (photo, bio, links), follow the Open Source / Developer Tools / Security topics, upvote 3–5 products you actually like and leave 1–2 substantive comments per day. New, inactive accounts are weighted down; three weeks of genuine activity is the fix.
- **Set up the PH "Coming soon" teaser page** for AccessFlow so people can subscribe to be notified on launch day; link it from X and GitHub.
- **X:** keep the 30-min daily engagement routine going on the ~40 accounts listed in the 2026-09-21 session. (The intro and v2.6.0 threads are already posted — see the marketing log.)
- **GitHub:** pinned Discussion "AccessFlow launches on Product Hunt <date> — feedback wanted"; a `## Launching` note in the README is optional.
- **Supporter list:** a spreadsheet of 30–50 real people you can DM (ex-colleagues, friends in platform/security, OSS maintainers you've helped, anyone who starred the repo). Column: name, channel, what they'd genuinely say.
- **Hunter:** ask one person with PH history in devtools/security to hunt it. If nobody, self-hunt — it matters less than it used to.
- Decide voiceover vs captions and upload the video to YouTube (unlisted).

### Week 2 (T-14 → T-8) · line people up
- **DM wave 1** (personal, one at a time): "We launch on PH on <date>. No upvote ask — I'd value an honest comment or a question. Here's the 50-second demo." Track replies.
- Ask 5 people to be **first-hour commenters** with a real question each (gives you something to answer and signals a live maker).
- Post in communities you're actually a member of (Slack/Discord groups, a newsletter you're in). Not Reddit/HN — those get their own launch the following week.
- Draft the 2.8.0 blog/changelog copy so launch day has a "what's new" link.
- Dry-run the listing in PH's draft mode; check every link, image size and the video embed.

### Week 3 (T-7 → T-1) · final approach
- Teaser thread on X (T-5 / T-4): the demo clip + "launching Tuesday".
- Reminder DM wave 2 (T-1, short).
- Schedule the launch-day X posts; pre-write the maker comment and the ten answers.
- Tag the pegged release (runs via `prep-gh-release`); confirm `version.json` and the changelog anchor are live.
- Sleep before 00:01 PT — that is 09:01 CEST / 10:01 AMT; plan the day accordingly.

## Launch day runbook (T-0, a Tuesday; times PT)

| Time | Action |
|---|---|
| 00:01 | Listing goes live. Verify links, video, gallery. |
| 00:05 | Post the maker comment. |
| 00:10 | X: launch post from @AccessFlowIO (link in first tweet is fine here), pin it. Quote-post from any personal account. |
| 00:15 | GitHub Discussion + README banner link. |
| 06:00 | DM wave 3 to the supporter list ("we're live"). Say "support" or "feedback", never "upvote". |
| all day | Reply to every comment within 15 min. Ask a follow-up question in each reply. |
| 09:00 | Post in the communities from week 2. |
| 15:00 | Final X post: "12 hours in — what people asked" (real questions, real answers). |
| 23:00 | Last sweep of comments; thank-you post. |

Rules: never explicitly ask for upvotes (PH penalises it and so do people); don't buy or swap votes; don't launch Show HN the same day — do that the following Tuesday with the PH comments as ammunition.

## Post-launch (T+1 → T+7)
- Reply to every remaining comment; DM thank-yous to everyone who showed up.
- Add the PH badge to `website/` (a shared footer/nav element → site-wide mechanical edit) and a line in the README.
- Harvest **customer language** from PH comments into `product-marketing.md` (Customer Language section — currently TBD).
- Show HN the following Tuesday; then r/devops, r/PostgreSQL, r/dataengineering over the next two weeks (one per day, each with a fresh angle).
- Log results in `marketing-log.md`: upvotes, comments, GitHub star delta, site traffic, X followers.
