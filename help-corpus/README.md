# AccessFlow Help Corpus

The **help corpus** is the versioned documentation bundle the in-app help agent answers from
(epic [#899](https://github.com/bablsoft/accessflow/issues/899)). It is generated from the public
documentation, committed to the repository, and bundled into the backend JAR on the classpath as
`help-corpus/**` — the same mechanism the [connector catalog](../connectors/README.md) uses.

Bundling it is deliberate: the corpus then always matches the running application version, works
in an air-gapped install, and needs no outbound call. By default an install never answers from
documentation for a version it is not running.

The one exception is the opt-in remote refresh (AF-907), off unless an operator sets
`ACCESSFLOW_HELP_CORPUS_REMOTE_REFRESH_ENABLED=true`, which lets an install pick up a documentation
*correction* published between releases. Every release publishes this folder to `gh-pages` as
`help-corpus/help-corpus-<version>.tar.gz`, and a GA release also moves a `help-corpus-index.json`
pointer that pins the archive by SHA-256 — see
[docs/09-deployment.md](../docs/09-deployment.md#remote-corpus-refresh-and-why-it-is-off).

**This folder is generated output. Never hand-edit it.** Edit the documentation, then regenerate:

```bash
node .github/scripts/build-help-corpus.mjs
```

Run it from the repository root and commit the result in the same change. The `help-corpus` CI job
re-runs the generator and fails the build when the committed bundle has drifted from its sources.

## Files

| File | What it is |
|---|---|
| `corpus.jsonl` | One JSON chunk per line: `{id, path, url, anchor, title, section, order, tokens, text}`. Each `text` opens with a breadcrumb line (`AccessFlow Docs > Guides > Run your first governed query > 5. Submit a query`) before the section body — a cheap recall win when the chunk is embedded. |
| `manifest.json` | `{schemaVersion, corpusVersion, generatedAt, sourceCommit, chunkCount, sha256, quickReferenceSha256, sources[]}`. |
| `quick-reference.txt` | A ~2,300-token orientation block — the query lifecycle, the rules that never bend, what each route in the app is for, and what the documentation covers. Substituted for retrieved context whenever retrieval is unavailable (no pgvector, no embedding provider, or an Anthropic-only install), so the agent still answers correctly; it just cannot cite a section. |

The artifact is chunked **text**, never precomputed embeddings. Vector dimensions and the embedding
model are the operator's choice — OpenAI `text-embedding-3-small` is 1536, Ollama
`nomic-embed-text` is 768, and Anthropic ships no embedding API at all — so a release cannot know
which. Each install embeds this text locally with its own configured model.

`corpusVersion` is `sha256(corpus.jsonl)[0..12]`: content-derived, not the application version.
Re-ingestion is therefore an idempotent string compare across replicas and restarts, and a
documentation typo fix re-embeds one chunk rather than all of them — chunk `id` is
`sha256(path + '#' + anchor + ':' + order)[0..16]`.

## What goes in, and what does not

Included: **every** `.html` file under `website/`, plus `docs/09-deployment.md` (the operator
env-var reference, which nothing on the website covers). The generator walks the tree rather than
naming folders, and **fails on any page it cannot classify** — so a new documentation area has to
be given a `SECTION_RULES` section label or an explicit exclusion, and cannot be silently dropped.

Excluded on purpose:

- **`website/roadmap/`** — it describes unbuilt work, and an agent that retrieves it will
  confidently explain features the user does not have.
- **`website/changelog/`** — version-specific.
- **Every page's shared nav, sidebar and footer** — otherwise ~10k words of near-duplicate link
  text would dominate similarity search across all 50 sources.
- **Decorative product mock-ups** (`aria-hidden="true"`, or the site's `mock` class) — the
  homepage's animated editor demo alone flattens to ~685 tokens of invented query ids, users and
  row counts. Dense with `audit`, `QUERY_EXECUTED` and `HMAC-SHA256`, it would rank near the top
  for "what does the audit log record?", and the agent would recite fabricated data back as
  documentation.
- **Sections under 40 tokens** — a chapter label plus a "Last updated" stamp is not an answer, and
  ~23 of them were near-identical across pages.

## How the generator works

Content is taken from each page's `<main>` element, minus its `<aside>`, `<nav>`, `<script>`,
`<style>`, `<svg>` and `<button>` boilerplate and its decorative mock-ups, then split on `<h2>` /
`<h3>` boundaries (`##` / `###` for markdown). The heading's `id` becomes the chunk `anchor` when
it has one. Any section over 800 tokens — the same budget `RagProperties.chunkSize` uses at
runtime — is split further on paragraph, then line, then word boundaries.

`order` counts parts **within a section**, not across the page, so a chunk's `id` depends only on
its own anchor and part index. Inserting a section therefore does not renumber the ones after it,
which is what makes a documentation edit re-embed the sections it touched rather than the page.

The script asserts loudly rather than silently emitting a broken bundle. It fails when a page
under `website/` matches no section rule and no exclusion, when the chunk count leaves the 350–600
range, when any chunk exceeds the token budget, when two chunks collide on `id`, when an excluded
path appears, or when a page has no `<main>`, no `<h1>` or no canonical URL.

The route table and lifecycle summary that `quick-reference.txt` is rendered from are declared at
the bottom of `.github/scripts/build-help-corpus.mjs`. That table is **cross-checked against
`frontend/src/App.tsx` in both directions**: a route the application serves that nobody described
fails the build, and so does a description of a route that no longer exists. Adding a route means
adding a line saying what the screen is for (or listing it in `ROUTES_NOT_LISTED`) — otherwise the
orientation block the agent falls back to would quietly describe an app that no longer exists.

## Determinism

Running the generator twice in a row produces byte-identical files, which is what the CI drift
guard (`git diff --exit-code help-corpus/`) relies on. No wall-clock value reaches `corpus.jsonl`
or `quick-reference.txt`, and `manifest.json`'s `generatedAt` / `sourceCommit` are carried over
verbatim from the existing manifest whenever the content digests are unchanged — so a regeneration
of untouched sources is a no-op in git.

The carry-over compares against the manifest already on disk, so one local sequence leaves a
cosmetic diff: edit a source, regenerate, revert the edit, regenerate again, and `generatedAt`
refreshes even though the content came back to where it started. `git checkout -- help-corpus/`
clears it. CI never hits this — it regenerates once from a committed tree.
