#!/usr/bin/env node
// Builds the versioned in-app help documentation corpus (issue #900, epic #899).
// Dependency-free by design, like validate-connectors.mjs and check-engine-pins.mjs.
//
// Reads the hand-authored public documentation (website/**) plus the operator env-var
// reference (docs/09-deployment.md), chunks it on heading boundaries, and writes:
//   help-corpus/corpus.jsonl        one JSON chunk per line, embedded by the app at runtime
//   help-corpus/manifest.json       schema/corpus version, chunk count, per-source digests
//   help-corpus/quick-reference.txt orientation block used when retrieval is unavailable
//
// The artifact is chunked TEXT, never precomputed embeddings: each install embeds it with its
// own configured embedding model (epic decision 1). `corpusVersion` is content-derived —
// sha256(corpus.jsonl)[0..12] — so a typo fix re-embeds one chunk, not the whole corpus
// (decision 7).
//
// Output is deterministic: no wall-clock timestamps reach corpus.jsonl or quick-reference.txt,
// and manifest.json's `generatedAt` / `sourceCommit` are carried over verbatim from the existing
// manifest whenever the content is unchanged. Re-running the script therefore produces
// byte-identical files, which is what the CI drift guard (`git diff --exit-code help-corpus/`)
// relies on.
//
// Run from the repository root:  node .github/scripts/build-help-corpus.mjs
import { readdirSync, readFileSync, writeFileSync, existsSync, mkdirSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import path from 'node:path';

const ROOT = process.cwd();
const OUT_DIR = path.join(ROOT, 'help-corpus');
const SCHEMA_VERSION = 1;

// Matches RagProperties.chunkSize (ai/internal/config/RagProperties.java) — the same budget the
// runtime TokenTextSplitter uses, so a chunk that fits here fits there.
const MAX_CHUNK_TOKENS = 800;
// Guard rails: a broken selector must fail loudly rather than silently emit three chunks.
const MIN_CHUNKS = 350;
const MAX_CHUNKS = 600;
// Below this a "section" is a label and a date stamp, not an answer.
const MIN_SECTION_TOKENS = 40;
const MIN_QUICK_REF_TOKENS = 1500;
const MAX_QUICK_REF_TOKENS = 4000;

const SHA256_RE = /^[0-9a-f]{64}$/;
const GITHUB_BLOB = 'https://github.com/bablsoft/accessflow/blob/main/';

// ---------------------------------------------------------------------------------------------
// Sources
// ---------------------------------------------------------------------------------------------

// Every .html file under website/ is ingested unless it is explicitly excluded below, and every
// ingested file must match a section rule. The script FAILS on a page that is neither, so a new
// documentation area cannot be silently dropped from the corpus — the failure mode this design is
// most exposed to, because the agent would then answer "I have no documentation on that" about a
// chapter that exists.
//
// Longest matching prefix wins; the label becomes the page's breadcrumb section.
const SECTION_RULES = [
  ['website/docs/guides/', 'Guides'],
  ['website/docs/configuration/', 'Reference'],
  ['website/docs/workflows/', 'Reference'],
  ['website/docs/iac/', 'Reference'],
  ['website/docs/', 'Documentation'],
  ['website/features/', 'Features'],
  ['website/connectors/', 'Connectors'],
  ['website/security/', 'Security'],
  ['website/use-cases/', 'Use cases'],
  ['website/ai-agents/', 'AI agents'],
  ['website/index.html', 'Overview'],
];

// Deliberate exclusions, each with the reason it is not documentation to answer from.
const EXCLUDED_PREFIXES = [
  // Describes unbuilt work; an agent that retrieves it will confidently explain features the user
  // does not have.
  'website/roadmap/',
  // Version-specific; the corpus already ships with the version it documents.
  'website/changelog/',
];
const EXCLUDED_FILES = [
  'website/404.html',                    // error page, no prose
  'website/googlef4908e4bf779aae8.html', // search-console verification stub
];

// The ~168 operator env vars, which nothing on the website covers.
// `title` overrides the file's own h1, which is a chapter number rather than a readable name.
const MARKDOWN_FILES = [
  { file: 'docs/09-deployment.md', section: 'Deployment', title: 'Deploying and configuring AccessFlow' },
];

// ---------------------------------------------------------------------------------------------
// Small helpers
// ---------------------------------------------------------------------------------------------

const sha256 = (s) => createHash('sha256').update(s, 'utf8').digest('hex');

const problems = [];
const fail = (msg) => problems.push(msg);

/** Rough cl100k-style token estimate: ~4 characters per word-piece, one token per punctuation mark. */
function estimateTokens(text) {
  let n = 0;
  for (const m of text.matchAll(/[A-Za-z0-9_]+|[^\sA-Za-z0-9_]/g)) {
    n += Math.max(1, Math.ceil(m[0].length / 4));
  }
  return n;
}

const NAMED_ENTITIES = {
  amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ', ensp: ' ', emsp: ' ',
  mdash: '—', ndash: '–', hellip: '…', rarr: '→', larr: '←', times: '×', copy: '©',
  lsquo: '‘', rsquo: '’', ldquo: '“', rdquo: '”',
};

function decodeEntities(s) {
  return s.replace(/&(#x?[0-9a-fA-F]+|[a-zA-Z]+);/g, (whole, body) => {
    if (body.startsWith('#')) {
      const code = body[1] === 'x' || body[1] === 'X'
        ? Number.parseInt(body.slice(2), 16)
        : Number.parseInt(body.slice(1), 10);
      return Number.isFinite(code) ? String.fromCodePoint(code) : whole;
    }
    const named = NAMED_ENTITIES[body];
    return named === undefined ? whole : named;
  });
}

/** Collapses runs of spaces but keeps paragraph structure, so lists and tables stay readable. */
function normaliseWhitespace(s) {
  return s
    .replace(/[^\S\n]+/g, ' ')
    .replace(/ ?\n ?/g, '\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}

/** Strips markup from an already block-delimited fragment and decodes entities. */
function stripTags(html) {
  return normaliseWhitespace(decodeEntities(html.replace(/<[^>]+>/g, '')));
}

/** A heading or page title, collapsed onto one line — several carry a <br /> for hero layout. */
function titleText(html) {
  return stripTags(html.replace(/<br\s*\/?>/gi, ' ')).replace(/\s+/g, ' ').replace(/\.$/, '');
}

// The site's two markers for decorative, non-prose content: the accessibility attribute, and the
// `mock` class it names its animated product demos with.
const DECORATIVE_OPEN =
  /<([a-z]+)\b[^>]*(?:\baria-hidden="true"|\bclass="(?:[^"]*\s)?mock(?:\s[^"]*)?")[^>]*>/i;

/**
 * Removes every decorative element, contents included, by matching its opening tag to its own
 * closing tag. Regex alone cannot do this — the containers nest.
 *
 * This is not cosmetic. The homepage's animated editor demo flattens to ~685 tokens of invented
 * query ids, users and row counts, and the use-cases mock-ups add two more; dense with `audit`,
 * `QUERY_EXECUTED` and `HMAC-SHA256`, they rank near the top for "what does the audit log
 * record?" — and the agent would then recite `alice@co` and `q_42081` back to a user as if they
 * were documentation. Epic decision 3 makes the agent a documentation reader, not a data surface;
 * fabricated data dressed as documentation is the same failure by another route.
 */
function stripDecorative(html) {
  const openRe = DECORATIVE_OPEN;
  let out = html;
  for (;;) {
    const match = openRe.exec(out);
    if (!match) return out;
    const tag = match[1].toLowerCase();
    if (/\/>\s*$/.test(match[0])) {
      out = out.slice(0, match.index) + out.slice(match.index + match[0].length);
      continue;
    }
    const scanner = new RegExp(`<(/?)${tag}\\b[^>]*>`, 'gi');
    scanner.lastIndex = match.index + match[0].length;
    let depth = 1;
    let end = -1;
    let step;
    while ((step = scanner.exec(out)) !== null) {
      depth += step[1] === '/' ? -1 : 1;
      if (depth === 0) {
        end = scanner.lastIndex;
        break;
      }
    }
    // Unbalanced markup: drop to the end rather than loop forever.
    out = out.slice(0, match.index) + (end === -1 ? '' : out.slice(end));
  }
}

/** HTML → plain text, preserving list items, table cells and block boundaries. */
function htmlToText(html) {
  const withBreaks = html
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<li[^>]*>/gi, '\n- ')
    .replace(/<\/(p|div|li|ul|ol|section|pre|table|tr|h4|h5|h6|blockquote|details|summary|figure|figcaption)>/gi, '\n')
    .replace(/<\/(td|th)>/gi, ' | ');
  return stripTags(withBreaks);
}

/** GitHub-flavoured heading slug, so the manifest URL resolves on github.com. */
function slugify(text) {
  return text
    .toLowerCase()
    .replace(/[^\p{L}\p{N} _-]/gu, '')
    .trim()
    .replace(/\s+/g, '-');
}

function listHtmlFiles(dir) {
  const abs = path.join(ROOT, dir);
  const out = [];
  for (const entry of readdirSync(abs, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
    const rel = `${dir}/${entry.name}`;
    if (entry.isDirectory()) out.push(...listHtmlFiles(rel));
    else if (entry.name.endsWith('.html')) out.push(rel);
  }
  return out;
}

// ---------------------------------------------------------------------------------------------
// Section extraction
// ---------------------------------------------------------------------------------------------

/**
 * Splits a page into `{ anchor, title, body }` sections on h2/h3 boundaries. The `id` attribute is
 * captured as the anchor when present — most guide chapters carry one, hub and marketing pages do
 * not, and those still chunk correctly with an empty anchor.
 */
function splitHtmlSections(mainHtml) {
  const headingRe = /<h([23])\b([^>]*)>([\s\S]*?)<\/h\1>/gi;
  const sections = [];
  let cursor = 0;
  let pending = null;
  let match;
  while ((match = headingRe.exec(mainHtml)) !== null) {
    const bodyHtml = mainHtml.slice(cursor, match.index);
    if (pending) {
      pending.body = htmlToText(bodyHtml);
      sections.push(pending);
    } else {
      const intro = htmlToText(bodyHtml);
      if (intro) sections.push({ anchor: '', title: null, body: intro });
    }
    const idAttr = /\bid="([^"]+)"/.exec(match[2]);
    pending = { anchor: idAttr ? idAttr[1] : '', title: titleText(match[3]), body: '' };
    cursor = headingRe.lastIndex;
  }
  if (pending) {
    pending.body = htmlToText(mainHtml.slice(cursor));
    sections.push(pending);
  } else {
    const only = htmlToText(mainHtml.slice(cursor));
    if (only) sections.push({ anchor: '', title: null, body: only });
  }
  return sections;
}

function splitMarkdownSections(md) {
  const lines = md.split('\n');
  const sections = [];
  let current = { anchor: '', title: null, lines: [] };
  let inFence = false;
  for (const line of lines) {
    if (/^\s*```/.test(line)) inFence = !inFence;
    const heading = inFence ? null : /^(#{2,3})\s+(.*)$/.exec(line);
    if (heading) {
      sections.push(current);
      const title = titleText(heading[2]).replace(/`/g, '');
      current = { anchor: slugify(title), title, lines: [] };
    } else {
      current.lines.push(line);
    }
  }
  sections.push(current);
  return sections
    .map((s) => ({ anchor: s.anchor, title: s.title, body: s.lines.join('\n').replace(/\n{3,}/g, '\n\n').trim() }))
    .filter((s) => s.title !== null || s.body);
}

/**
 * Splits an over-budget section body into pieces that each fit `budget` tokens, preferring the
 * coarsest boundary that works: paragraphs, then lines, then words. The word fallback is not
 * theoretical — a single `<li>` in the datasources chapter is ~800 tokens on its own.
 */
function splitByTokens(body, budget) {
  const SEPARATORS = ['\n\n', '\n', ' '];

  const segment = (text, level) => {
    if (estimateTokens(text) <= budget) return [text];
    if (level >= SEPARATORS.length) return [text];
    const separator = SEPARATORS[level];
    const blocks = text.split(separator);
    const pieces = [];
    let buffer = '';
    for (const block of blocks) {
      const candidate = buffer ? buffer + separator + block : block;
      if (estimateTokens(candidate) <= budget) {
        buffer = candidate;
        continue;
      }
      if (buffer) pieces.push(buffer);
      buffer = '';
      const deeper = segment(block, level + 1);
      // All but the last piece are final; the last one keeps accumulating.
      pieces.push(...deeper.slice(0, -1));
      buffer = deeper[deeper.length - 1] ?? '';
    }
    if (buffer) pieces.push(buffer);
    return pieces;
  };

  const pieces = segment(body, 0).map((p) => p.trim()).filter(Boolean);
  return pieces.length > 0 ? pieces : [''];
}

// ---------------------------------------------------------------------------------------------
// Chunk building
// ---------------------------------------------------------------------------------------------

const chunks = [];
const sources = [];

function addPage({ relPath, section, pageTitle, baseUrl, sections }) {
  const before = chunks.length;
  for (const raw of sections) {
    const body = raw.body.trim();
    // A floor, not just a non-empty check: ~23 sections are a chapter label plus a "Last updated"
    // stamp and no prose. They carry no answer, they are near-identical across pages (exactly the
    // duplicate text similarity search must not be dominated by), and each one churns on every
    // dateModified bump.
    if (estimateTokens(body) < MIN_SECTION_TOKENS) continue;
    const headingTitle = raw.title ?? pageTitle;
    const crumbs = ['AccessFlow Docs', section, pageTitle];
    if (raw.title && raw.title !== pageTitle) crumbs.push(raw.title);
    // The breadcrumb prefix is embedded with the chunk, so it has to come out of the budget.
    // 12 tokens covers the widest " (part n of m)" suffix a split can add.
    const budget = MAX_CHUNK_TOKENS - estimateTokens(crumbs.join(' > ')) - 12;
    const parts = splitByTokens(body, budget);
    // `order` restarts per section, so `id` depends only on that section's own anchor and part
    // index. With a page-global counter, inserting a section renumbers every later one and
    // re-embeds the whole page; this way an edit re-embeds the sections it actually touched.
    // The anchor falls back to a slug of the heading for the pages that ship no heading ids.
    const anchorKey = raw.anchor || slugify(raw.title ?? '') || 'intro';
    parts.forEach((part, order) => {
      const suffix = parts.length > 1 ? ` (part ${order + 1} of ${parts.length})` : '';
      const text = `${crumbs.join(' > ')}${suffix}\n\n${part}`;
      const id = sha256(`${relPath}#${anchorKey}:${order}`).slice(0, 16);
      chunks.push({
        id,
        path: relPath,
        url: raw.anchor ? `${baseUrl}#${raw.anchor}` : baseUrl,
        anchor: raw.anchor,
        title: headingTitle,
        section,
        order,
        tokens: estimateTokens(text),
        text,
      });
    });
  }
  return chunks.length - before;
}

function ingestHtml(relPath, section) {
  const html = readFileSync(path.join(ROOT, relPath), 'utf8');
  const canonical = /<link rel="canonical" href="([^"]+)"/.exec(html);
  if (!canonical) {
    fail(`${relPath}: no <link rel="canonical"> to derive the chunk URL from`);
    return;
  }
  const main = /<main\b[^>]*>([\s\S]*?)<\/main>/i.exec(html);
  if (!main) {
    fail(`${relPath}: no <main> element — the shared nav/footer cannot be separated from the content`);
    return;
  }
  // Drop the shared boilerplate: the sidebar table of contents, the breadcrumb trail and every
  // other in-page nav. Left in, ~10k words of near-duplicate link text would dominate similarity
  // search across all 49 pages.
  const withoutChrome = main[1]
    .replace(/<aside\b[^>]*>[\s\S]*?<\/aside>/gi, '')
    .replace(/<nav\b[^>]*>[\s\S]*?<\/nav>/gi, '')
    .replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, '')
    .replace(/<style\b[^>]*>[\s\S]*?<\/style>/gi, '')
    .replace(/<svg\b[^>]*>[\s\S]*?<\/svg>/gi, '')
    .replace(/<button\b[^>]*>[\s\S]*?<\/button>/gi, '')
    .replace(/<!--[\s\S]*?-->/g, '');
  const body = stripDecorative(withoutChrome);
  const h1 = /<h1[^>]*>([\s\S]*?)<\/h1>/i.exec(body);
  if (!h1) {
    fail(`${relPath}: no <h1> to title the page with`);
    return;
  }
  const pageTitle = titleText(h1[1]);
  const count = addPage({
    relPath,
    section,
    pageTitle,
    baseUrl: canonical[1],
    sections: splitHtmlSections(body.replace(/<h1[^>]*>[\s\S]*?<\/h1>/i, '')),
  });
  sources.push({ path: relPath, title: pageTitle, url: canonical[1], section, chunks: count, sha256: sha256(html) });
}

function ingestMarkdown(relPath, section, titleOverride) {
  const md = readFileSync(path.join(ROOT, relPath), 'utf8');
  const h1 = /^#\s+(.*)$/m.exec(md);
  const pageTitle = titleOverride ?? (h1 ? titleText(h1[1]) : path.basename(relPath));
  const baseUrl = `${GITHUB_BLOB}${relPath}`;
  const count = addPage({
    relPath,
    section,
    pageTitle,
    baseUrl,
    sections: splitMarkdownSections(md.replace(/^#\s+.*$/m, '')),
  });
  sources.push({ path: relPath, title: pageTitle, url: baseUrl, section, chunks: count, sha256: sha256(md) });
}

const isExcluded = (rel) =>
  EXCLUDED_FILES.includes(rel) || EXCLUDED_PREFIXES.some((prefix) => rel.startsWith(prefix));

/** Longest matching prefix wins, so website/docs/guides/ beats website/docs/. */
const sectionFor = (rel) =>
  SECTION_RULES.filter(([prefix]) => rel === prefix || rel.startsWith(prefix))
    .sort((a, b) => b[0].length - a[0].length)
    .map(([, label]) => label)[0];

for (const rel of listHtmlFiles('website')) {
  if (isExcluded(rel)) continue;
  const section = sectionFor(rel);
  if (!section) {
    fail(`${rel} matches no SECTION_RULES entry — add it to a section, or to EXCLUDED_FILES /`
      + ' EXCLUDED_PREFIXES with the reason it is not documentation the help agent answers from');
    continue;
  }
  ingestHtml(rel, section);
}
for (const entry of MARKDOWN_FILES) ingestMarkdown(entry.file, entry.section, entry.title);

// ---------------------------------------------------------------------------------------------
// Quick reference (epic decision 9): substituted for retrieved context when retrieval is
// unavailable — no pgvector, no embedding provider, or an Anthropic-only install. Generated here
// rather than hand-maintained in Java so it ships and versions with the corpus.
// ---------------------------------------------------------------------------------------------

// Routes with no place in an orientation block: the catch-all, the two SSO callbacks the browser
// only passes through, and the tab URLs of the unified review hub (which ROUTES names directly,
// because the pre-#772 paths they replaced are redirect stubs).
const ROUTES_NOT_LISTED = ['*', '/auth/oauth/callback', '/auth/saml/callback', '/api-reviews', '/deployment-reviews'];

const ROUTES = [
  ['/', 'Lands on the dashboard once signed in.'],
  ['/dashboard', 'Personalized home: summary tiles, query trends, AI suggestions, weekly digest.'],
  ['/editor', 'SQL editor. Pick a datasource, write a query, submit it for review.'],
  ['/queries', 'Queries the signed-in user submitted, with status and AI risk. A query admin sees the whole organization here.'],
  ['/queries/:id', 'One query: SQL, AI analysis, approval chain, results, audit trail.'],
  ['/reviews', 'Unified review hub. Tabs for queries, API calls, deployments and rollbacks.'],
  ['/reviews/:id/decide', 'Approve or reject one request. The decision is re-authenticated — it asks for your password or TOTP code. A comment is optional.'],
  ['/reviews/attestations', 'Access recertification worklist: certify or revoke standing grants.'],
  ['/request-groups', 'Grouped requests that bundle ordered query and API-call members.'],
  ['/request-groups/:id', 'One grouped request: its members, their order, and the aggregated approval.'],
  ['/request-groups/new', 'Build a grouped request and order its members.'],
  ['/request-groups/:id/edit', 'Change a grouped request before it is submitted.'],
  ['/request-groups/reviews', 'Review queue for grouped requests.'],
  ['/datasources', 'Databases the user may query, and their connection health.'],
  ['/datasources/new', 'Register a database: engine, host, credentials, SSL mode.'],
  ['/datasources/:id/settings', 'Per-datasource schema, masking, row security and ER diagram.'],
  ['/api-connectors', 'Governed outbound REST, SOAP, GraphQL and gRPC connectors.'],
  ['/api-connectors/:id/settings', 'Per-connector schema, permissions, response masking and classification tags.'],
  ['/api-editor', 'Compose a governed API call and submit it for review.'],
  ['/api-requests', 'API calls the user submitted, with status and AI risk.'],
  ['/api-requests/:id', 'One API call: request, AI analysis, approval chain, response.'],
  ['/reviews?tab=api', 'Review queue for API calls. The older /api-reviews URL redirects here.'],
  ['/deployments', 'Deployment requests raised by CI/CD pipelines.'],
  ['/deployments/:id', 'One deployment request: what it releases, its analysis and its decisions.'],
  ['/reviews?tab=deployments', 'Review queue for deployments; ?tab=rollbacks is the rollback worklist. The older /deployment-reviews URL redirects here.'],
  ['/deployment-versions', 'What version each environment is running, and where it has drifted.'],
  ['/deployment-versions/:pipelineId', 'The same matrix for one pipeline, plus per-environment history.'],
  ['/access-requests', 'Ask for access to a datasource, or track a request already made.'],
  ['/lifecycle/erasure', 'Right-to-erasure requests over personal data.'],
  ['/lifecycle/erasure-reviews', 'Review queue for erasure requests.'],
  ['/profile', 'Own account: display name, password, two-factor (TOTP), review delegation while you are away, API keys, Slack account link.'],
  ['/setup', 'First-run wizard. Shown until an active admin exists.'],
  ['/login', 'Sign in with password, OAuth 2.0 / OIDC or SAML 2.0 SSO.'],
  ['/forgot-password', 'Ask for a password-reset email.'],
  ['/reset-password/:token', 'Set a new password from the link in that email.'],
  ['/invite/:token', 'Accept an invitation and choose a password.'],
  ['/admin/users', 'Create, deactivate and re-invite users; assign roles and permissions.'],
  ['/admin/groups', 'User groups and the grants attached to them.'],
  ['/admin/groups/:id', 'One group: its members and the permissions it grants them.'],
  ['/admin/roles', 'Roles and the permissions each one carries.'],
  ['/admin/organizations', 'Organizations (tenants) and their settings.'],
  ['/admin/organizations/:id', 'One organization and its settings.'],
  ['/admin/languages', 'Which of the seven interface languages are offered.'],
  ['/admin/access-requests', 'Approve or reject incoming access requests.'],
  ['/admin/break-glass', 'Break-glass grants and the mandatory retro-review of each use.'],
  ['/admin/review-plans', 'Review plans: approval stages, approvers, timeouts, escalation.'],
  ['/admin/routing-policies', 'Typed conditions that auto-approve, auto-reject or route a request.'],
  ['/admin/attestation', 'Scheduled attestation campaigns over standing grants.'],
  ['/admin/attestation/:id', 'One campaign: its scope, progress and evidence export.'],
  ['/admin/ai-configs', 'AI providers: OpenAI, Anthropic, Ollama, OpenAI-compatible, Hugging Face.'],
  ['/admin/ai-configs/new', 'Add an AI provider configuration.'],
  ['/admin/ai-configs/:id', 'Edit one AI provider configuration, its prompt and its knowledge base.'],
  ['/admin/ai-analyses', 'History of every AI analysis, with tokens and latency.'],
  ['/admin/anomalies', 'User-behaviour anomalies the AI flagged.'],
  ['/admin/langfuse', 'Langfuse tracing for AI calls.'],
  ['/admin/help-agent', 'In-app help assistant: bind it to an AI provider, tune retrieval and retention, and re-index the bundled documentation.'],
  ['/admin/connectors', 'Connector catalog: engines available and their driver pins.'],
  ['/admin/drivers', 'Uploaded JDBC driver JARs for custom engines.'],
  ['/admin/datasource-health', 'Connection health across every registered datasource.'],
  ['/admin/data-classifications', 'Classification tags and the masking they derive.'],
  ['/admin/deployment-pipelines', 'CI/CD pipelines, environments, freeze windows and permissions.'],
  ['/admin/deployment-pipelines/:id', 'One pipeline: environments, permissions, freeze windows, routing policies and the CI snippet.'],
  ['/admin/notifications', 'Notification channels: email, Slack, webhooks, Discord, Telegram, Microsoft Teams, PagerDuty, ServiceNow and Jira.'],
  ['/admin/slack', 'Slack workspace connection.'],
  ['/admin/oauth2', 'OAuth 2.0 / OIDC sign-in providers.'],
  ['/admin/saml', 'SAML 2.0 single sign-on.'],
  ['/admin/scim', 'SCIM 2.0 provisioning tokens and attribute mapping.'],
  ['/admin/audit-log', 'The tamper-evident audit log, filterable and exportable.'],
  ['/admin/audit-sinks', 'Where audit rows are streamed for long-term retention.'],
  ['/admin/auditor', 'Compliance reports and signed PDF / CSV exports.'],
  ['/admin/over-provisioned-access', 'Grants nobody has used, suggested for revocation.'],
  ['/admin/lifecycle/policies', 'Data retention and erasure policies.'],
];

/** Drops a trailing "— what it does" clause, which every connector page repeats verbatim. */
const shortTitle = (title) => {
  const head = title.split(' — ')[0];
  return head.length >= 12 ? head : title;
};

const LIFECYCLE = [
  '1. Submit — a user picks a datasource and writes a query. AccessFlow parses it before anything runs, and rejects what it cannot parse or what reads a table that user is not allowed to read.',
  '2. Analyse — if the datasource has AI analysis enabled, a model scores the query for risk and explains what it does. Status: PENDING_AI.',
  '3. Route — the query can be approved without a human: by a routing policy, or because the user already holds a standing time-bound grant that pre-approves it. Otherwise the datasource review plan decides who must approve. Status: PENDING_REVIEW.',
  '4. Approve — every stage of the approval chain must clear. Nobody can approve their own query, not even an admin. Status: APPROVED, REJECTED or TIMED_OUT.',
  '5. Execute — the proxy runs the query under masking, row-level security and row caps, and records the result. Status: EXECUTED or FAILED.',
];

const RULES = [
  'A user can never approve their own request, regardless of role.',
  'Break-glass bypasses AI and review, but only for someone holding the break-glass permission on that datasource; it pages every admin and opens a mandatory retro-review.',
  'The audit log is insert-only — the application database role has no UPDATE or DELETE on it.',
  'Datasource credentials are AES-256-GCM encrypted at rest and are never returned by any endpoint.',
  'The help agent reads documentation only. It cannot see queries, results, audit rows, schemas or datasources, and it cannot act on your behalf.',
];

// The route table above is hand-written prose, but it must not drift from the router. Cross-check
// it against App.tsx and fail both ways: a route the application serves but nobody described, and
// a description of a route that no longer exists. Without this the quick reference rots silently —
// the generator's inputs would not change, so the CI drift guard would stay green.
const APP_ROUTES_FILE = 'frontend/src/App.tsx';
const appRoutes = new Set(
  [...readFileSync(path.join(ROOT, APP_ROUTES_FILE), 'utf8').matchAll(/path="([^"]+)"/g)]
    .map((m) => m[1])
    .filter((route) => !ROUTES_NOT_LISTED.includes(route)),
);
const describedRoutes = new Set(ROUTES.map(([route]) => route.split('?')[0]));
for (const route of appRoutes) {
  if (!describedRoutes.has(route)) {
    fail(`${APP_ROUTES_FILE} serves ${route} but ROUTES in this script does not describe it —`
      + ' add a line saying what the screen is for, or add it to ROUTES_NOT_LISTED');
  }
}
for (const route of describedRoutes) {
  if (!appRoutes.has(route)) {
    fail(`ROUTES describes ${route}, which ${APP_ROUTES_FILE} no longer serves — remove or update it`);
  }
}

const quickReference = [
  'AccessFlow — quick reference',
  '',
  'AccessFlow is an open-source database access governance platform. It sits as a full query',
  'proxy between people and the databases, warehouses, outbound APIs and CI/CD pipelines they',
  'need, and enforces configurable AI analysis and human approval before anything runs. Every',
  'decision and execution lands in a tamper-evident audit log.',
  '',
  'The query lifecycle',
  ...LIFECYCLE.map((line) => `  ${line}`),
  '',
  'Rules that never bend',
  ...RULES.map((line) => `  - ${line}`),
  '',
  'Where things are in the app',
  ...ROUTES.map(([route, purpose]) => `  ${route.padEnd(34)} ${purpose}`),
  '',
  // Titles only, no URLs: the model emits [n] citation indices and the server resolves them to
  // {title, url} (epic decision 6). Feeding it link targets would invite it to write its own.
  'What the documentation covers',
  ...[...new Set(sources.map((s) => s.section))].map(
    (section) => `  ${section}: ${sources.filter((s) => s.section === section).map((s) => shortTitle(s.title)).join('; ')}`,
  ),
  '',
].join('\n');

// ---------------------------------------------------------------------------------------------
// Assertions
// ---------------------------------------------------------------------------------------------

if (chunks.length < MIN_CHUNKS || chunks.length > MAX_CHUNKS) {
  fail(`chunkCount ${chunks.length} is outside the expected ${MIN_CHUNKS}-${MAX_CHUNKS} range.`
    + ' A large drop means a selector broke; steady growth past the ceiling as documentation is'
    + ' added is legitimate — widen MAX_CHUNKS in this script.');
}
const seen = new Map();
for (const chunk of chunks) {
  if (chunk.tokens > MAX_CHUNK_TOKENS) {
    fail(`chunk ${chunk.id} (${chunk.path}#${chunk.anchor} "${chunk.title}") is ${chunk.tokens} tokens, over the ${MAX_CHUNK_TOKENS} budget`);
  }
  if (seen.has(chunk.id)) {
    fail(`chunk id ${chunk.id} collides: ${seen.get(chunk.id)} and ${chunk.path}#${chunk.anchor}:${chunk.order}`
      + ' — two sections on one page share a heading slug; give one of them an explicit id');
  }
  seen.set(chunk.id, `${chunk.path}#${chunk.anchor}:${chunk.order}`);
  for (const prefix of EXCLUDED_PREFIXES) {
    if (chunk.path.startsWith(prefix)) fail(`chunk ${chunk.id} came from excluded source ${chunk.path}`);
  }
}
const quickReferenceTokens = estimateTokens(quickReference);
if (quickReferenceTokens < MIN_QUICK_REF_TOKENS || quickReferenceTokens > MAX_QUICK_REF_TOKENS) {
  fail(`quick-reference.txt is ${quickReferenceTokens} tokens, outside the ${MIN_QUICK_REF_TOKENS}-${MAX_QUICK_REF_TOKENS} range`);
}

if (problems.length > 0) {
  console.error('Help corpus build failed:');
  for (const p of problems) console.error(`  - ${p}`);
  process.exit(1);
}

// ---------------------------------------------------------------------------------------------
// Write
// ---------------------------------------------------------------------------------------------

const corpus = `${chunks.map((c) => JSON.stringify(c)).join('\n')}\n`;
const corpusSha = sha256(corpus);
if (!SHA256_RE.test(corpusSha)) {
  console.error(`Refusing to write a manifest with a malformed sha256: ${corpusSha}`);
  process.exit(1);
}
const corpusVersion = corpusSha.slice(0, 12);

let sourceCommit = 'unknown';
try {
  sourceCommit = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: ROOT, encoding: 'utf8' }).trim();
} catch {
  // Not a git checkout (a source tarball, say) — the corpus itself is unaffected.
}

const manifestPath = path.join(OUT_DIR, 'manifest.json');
const manifest = {
  schemaVersion: SCHEMA_VERSION,
  corpusVersion,
  generatedAt: new Date().toISOString(),
  sourceCommit,
  chunkCount: chunks.length,
  sha256: corpusSha,
  quickReferenceSha256: sha256(quickReference),
  sources,
};

// Carry `generatedAt` / `sourceCommit` over when nothing about the content changed, so a
// regeneration of unchanged sources is byte-identical and the CI drift guard stays a pure
// content check.
if (existsSync(manifestPath)) {
  try {
    const previous = JSON.parse(readFileSync(manifestPath, 'utf8'));
    const unchanged = previous.schemaVersion === manifest.schemaVersion
      && previous.sha256 === manifest.sha256
      && previous.quickReferenceSha256 === manifest.quickReferenceSha256
      && previous.chunkCount === manifest.chunkCount
      && JSON.stringify(previous.sources) === JSON.stringify(manifest.sources);
    if (unchanged) {
      manifest.generatedAt = previous.generatedAt;
      manifest.sourceCommit = previous.sourceCommit;
    }
  } catch {
    // Unreadable previous manifest — write a fresh one.
  }
}

mkdirSync(OUT_DIR, { recursive: true });
writeFileSync(path.join(OUT_DIR, 'corpus.jsonl'), corpus);
writeFileSync(path.join(OUT_DIR, 'quick-reference.txt'), quickReference);
writeFileSync(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`);

console.log(
  `Help corpus built: ${chunks.length} chunks from ${sources.length} sources, `
  + `corpusVersion ${corpusVersion}, quick reference ${quickReferenceTokens} tokens.`,
);
