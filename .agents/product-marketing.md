# Product Marketing Context

**Document version:** v1
**Last updated:** 2026-09-21

> Auto-drafted from the codebase (`README.md`, `website/llms.txt`, `website/compare/`,
> `website/use-cases/`, `website/README.md`). Anything marked **TBD** is not derivable from the
> repo and needs a human answer. Progress and upcoming work live in
> [`marketing-log.md`](marketing-log.md).

## Product Overview
**One-liner:** Open-source access proxy that puts AI review, human approval and a tamper-evident audit log in front of databases, outbound APIs and CI/CD deployments.
**What it does:** A user (or an AI agent) submits a query, API call or deploy request to AccessFlow instead of running it directly. AccessFlow parses and validates it, risk-scores it with a configurable AI provider, routes it through policy-driven human approval, and only then executes it — under schema allow-lists, dynamic masking, row-level security and row caps. Every step lands in an INSERT-only, hash-chained audit log.
**Product category:** Database access governance / privileged data access (people also search "database access proxy", "open-source StrongDM alternative", "Teleport database access alternative", "DAM").
**Product type:** Self-hosted open-source software (Docker Compose or Helm). Apache 2.0.
**Business model:** Free, Apache 2.0, single edition — no paid tier or hosted offering today. **TBD:** any planned commercial layer.

## Target Audience
**Target companies:** Engineering orgs with production data that more than a handful of people need to touch — SaaS scale-ups through enterprise platform teams, especially regulated or audit-exposed ones (SOC 2, ISO 27001, GDPR, HIPAA, PCI).
**Decision-makers:** Head of Platform / DevOps / SRE, Security engineering lead, Lead DBA / data platform owner, CISO or compliance owner (sponsor).
**Primary use case:** Replace shared production credentials and ticket-queue DBA access with governed, self-service, reviewed access.
**Jobs to be done:**
- Let engineers get to production data quickly without handing out unbounded credentials.
- Produce audit evidence of who ran what, who approved it, and why — as a download, not a project.
- Put the same approval gate in front of outbound API calls and CI/CD releases.
**Use cases** (the seven on `/use-cases/`): production access without shared credentials; just-in-time access that expires on its own; review at scale without a ticket queue; audit evidence as a download; privacy obligations (retention, erasure, pseudonymization) as running processes; the same governance for APIs; releases that wait for an approval.

## Personas
| Persona | Cares about | Challenge | Value we promise |
|---------|-------------|-----------|------------------|
| Platform / DevOps lead (champion) | Engineer velocity, fewer 3 a.m. incidents | Engineers route around the DBA queue; shared creds in Slack | Self-service access with review that doesn't slow people down |
| Security engineer (technical influencer) | Least privilege, no side doors, auditability | Can't see or stop what runs against prod; AI agents are a new blind spot | Every statement reviewed before execution; masking + RLS at run time; agents through the same gate |
| DBA / data platform owner (user + reviewer) | Not being the bottleneck; no destructive queries | Manual ticket queue; reviewing raw SQL by eye | AI risk triage, deterministic SQL rules, one review hub, break-glass with retro-review |
| CISO / compliance (decision maker) | Evidence for auditors, recertification, DLP | Access reviews and audit trails are spreadsheets | Tamper-evident log, signed compliance exports, recertification campaigns |
| Engineer / analyst (end user) | Getting an answer today | Waiting days for access, or fear of breaking prod | Submit, get approved, run — with masking so they only see what they should |

## Problems & Pain Points
**Core problem:** Teams pick one of two bad defaults for production access — shared credentials (fast, unbounded blast radius, no record) or a DBA ticket queue (safe, slow enough that people route around it).
**Why alternatives fall short:**
- Session-level gateways (Teleport, StrongDM, hoop.dev) decide *who may connect*, not *whether this statement may run*.
- Schema-migration tools (Bytebase) review DDL moving through environments, not the everyday reads and DML.
- VPNs/bastions and homegrown scripts have no approval workflow and no tamper-evident trail.
**What it costs them:** Incidents from an unbounded `DELETE`; audit findings; engineer hours lost to queues; shadow access paths.
**Emotional tension:** The security lead's fear of the one query nobody saw; the engineer's dread of asking for access; the DBA's resentment of being the bottleneck.

## Competitive Landscape
Sourced comparison pages live at `website/compare/` — every claim about another product cites its docs, and each page names where the other tool is the better fit. Keep that honesty in all copy.
**Direct:** hoop.dev — a gateway your existing clients connect through; falls short when you want a decision before execution for *everything*. Bytebase — schema change management; falls short when reads need review, not just writes.
**Secondary:** StrongDM — hosted control plane; falls short when you need to read the source or run with no outside dependency. Teleport Database Access — session-level; falls short when the decision is about the statement.
**Indirect:** Shared credentials; ticket-driven DBA queue; VPN/bastion; per-database native RBAC alone.
**Where they win (say it plainly):** migrations-as-code → Bytebase; one gateway for hosts, clusters and desktops you already run → Teleport / hoop.dev; not wanting to run any control plane → StrongDM.

## Differentiation
**Key differentiators:**
- Reviews the **statement**, not the session — parsed at the AST, risk-scored, approved, then executed.
- Guardrails survive approval: schema allow-list, dynamic masking, row-level security, row caps enforced at execution time.
- One pipeline across **18 engines** (SQL, warehouses, NoSQL) **plus** outbound REST/SOAP/GraphQL/gRPC calls **plus** CI/CD deployment gates.
- Tamper-evident audit: INSERT-only, hash-chained log; signed compliance exports; access recertification.
- AI agents get no side door: built-in MCP server routes them through the same approval and masking.
- Bring-your-own AI (Anthropic, OpenAI, Ollama, Hugging Face, any OpenAI-compatible endpoint); works with AI off.
- Explainability: decision trace / access simulator answers "why was this routed here?" without running anything.
- Apache 2.0, self-hosted, no vendor control plane; Terraform provider + reusable CI actions.
**How we do it differently:** A submit → AI → review → execute proxy in front of the datastore, rather than a network gateway or a migration pipeline.
**Why that's better:** Every request is reviewable, every approval traceable, and the obvious mistakes are caught by AI and deterministic rules before a human ever looks.
**Why customers choose us:** **TBD** — no customer interviews yet; hypotheses above.

## Objections
| Objection | Response |
|-----------|----------|
| "It's another hop / it'll slow people down." | Routing policies auto-approve low-risk reads, JIT grants pre-approve covered queries, SQL rules and AI triage mean reviewers only see what needs eyes. Break-glass exists for real emergencies (with retro-review). |
| "My engineers won't change their client." | True — AccessFlow is a proxy you submit to, not a transparent gateway. If a drop-in wire-protocol gateway is the requirement, that's on the roadmap (native wire-protocol gateway) and today Teleport/hoop.dev fit better. |
| "Is the AI making the decision?" | No. AI risk-scores and explains; humans (or explicit deterministic policy) decide. It works with AI switched off. |
| "Another security tool to run." | Docker Compose or Helm, Postgres + Redis, no control plane, no outbound dependency; Apache 2.0 so you can read every line. |

**Anti-persona:** Solo devs / tiny teams with one database and no compliance exposure; teams whose real problem is schema migrations (send them to Bytebase); orgs that need a hosted SaaS with zero self-hosting.

## Switching Dynamics
**Push:** An incident or audit finding from shared credentials; a DBA queue everyone routes around; AI agents needing prod access with no governance story.
**Pull:** Reviewed-statement model, one gate for DB + API + deploy, open source with no control plane, MCP for agents.
**Habit:** psql/DBeaver muscle memory; existing bastion/VPN; "we've always used tickets".
**Anxiety:** Adoption friction (submitting instead of connecting); yet another tool to operate; whether an OSS project this young will last (6 GitHub stars as of 2026-09-21 — social proof is thin, lead with substance).

## Customer Language
**How they describe the problem:** **TBD** — no verbatim customer quotes yet. Collect from GitHub discussions, X replies, Reddit/HN threads.
**How they describe us:** **TBD**.
**Words to use:** access proxy, reviewed before it runs, approval workflow, tamper-evident audit log, break-glass, just-in-time access, row-level security, masking, self-hosted, Apache 2.0, "the missing middle".
**Words to avoid:** "AI decides/approves" (it doesn't); "gateway" or "transparent proxy" (we are not); "zero trust" buzzword without mechanism; "we're thrilled to announce"; invented metrics or customer names.
**Glossary:**
| Term | Meaning |
|------|---------|
| Review plan | Multi-stage approval chain bound to a datasource/permission |
| Routing policy | Policy-as-code rule that auto-approves, auto-rejects or routes a request |
| Break-glass | Emergency execution with instant admin fan-out and mandatory retro-review |
| JIT grant | Time-boxed access request that expires on its own; can pre-approve covered queries |
| Deployment gate | Fail-closed endpoint a CI job blocks on until a release is approved |
| Decision trace / simulator | Replays a hypothetical request through the real evaluators, writes nothing |
| SQL review rules | Fourteen deterministic checks (no-WHERE DELETE, SELECT *, DDL…) at off/warn/block per environment |

## Brand Voice
**Tone:** Confident, concrete, engineer-to-engineer. Honest about limits (the compare pages name where competitors win).
**Style:** Direct and specific — mechanisms over adjectives. Short sentences. No emoji walls (≤1 per post, optional). No hashtags beyond 1–2.
**Personality:** Rigorous, candid, pragmatic, open, unhyped.
**Channels:** Company account @AccessFlowIO on X (created Sept 2026). Founder personal account **TBD**.

## Proof Points
**Metrics:** 18 governed engines; 3 governed surfaces (DB, API, deploy); 14 deterministic SQL rules; 12 MCP tools; releases every ~2 weeks (v2.6.0 on 2026-09-15; milestones 2.7–2.10 scheduled through 2026-11-10). No adoption numbers yet — do not invent any.
**Customers:** **TBD** — none public.
**Testimonials:** **TBD** — none yet.
**Value themes:**
| Theme | Proof |
|-------|-------|
| Reviewed before it runs | AST parse + AI risk + review plan + SQL rules, all before execution |
| Guardrails at execution | Masking, RLS, allow-list, row caps enforced by the proxy |
| Evidence, not a project | Hash-chained audit log, signed exports, recertification campaigns |
| One gate, three surfaces | Same pipeline for queries, API calls, CI/CD releases |
| Agents included | MCP server, scoped API keys, on-behalf-of attribution |
| Nothing to trust blindly | Apache 2.0, self-hosted, sourced comparisons |

## Goals
**Business goal:** Awareness and adoption of the open-source project — GitHub stars, self-hosted installs, contributors, and a community that surfaces customer language.
**Conversion action:** `git clone` + `docker compose up` → first reviewed query; secondary: GitHub star / follow @AccessFlowIO.
**Current metrics (2026-09-21):** 6 GitHub stars, 3 forks; @AccessFlowIO 0 followers, 7 posts, under X's new-account "graduated access" reach limit.

## Changelog
*Newest first. One line per revision: what changed and why.*
- v1 (2026-09-21) — Initial context, auto-drafted from the codebase after the first X thread; customer language, proof points and business model marked TBD.
