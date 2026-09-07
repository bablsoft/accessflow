# AccessFlow Help Corpus

The **help corpus** is the versioned documentation bundle the in-app help chat agent answers from
(epic [#899](https://github.com/bablsoft/accessflow/issues/899)). It is generated from the public
documentation, committed to the repository, and bundled into the backend JAR on the classpath as
`help-corpus/**` — the same mechanism the [connector catalog](../connectors/README.md) uses.

Bundling it is deliberate: the corpus then always matches the running application version, works
in an air-gapped install, and needs no outbound call. An install never answers from documentation
for a version it is not running.

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

Included: `website/docs/**`, `website/features/**`, `website/connectors/**`,
`website/security/`, `website/use-cases/`, `website/ai-agents/`, `website/index.html`, and
`docs/09-deployment.md` (the operator env-var reference, which nothing on the website covers).

Excluded on purpose:

- **`website/roadmap/`** — it describes unbuilt work, and an agent that retrieves it will
  confidently explain features the user does not have.
- **`website/changelog/`** — version-specific.
- **Every page's shared nav, sidebar and footer** — otherwise ~10k words of near-duplicate link
  text would dominate similarity search across all 50 sources.

## How the generator works

Content is taken from each page's `<main>` element, minus its `<aside>`, `<nav>`, `<script>`,
`<style>`, `<svg>` and `<button>` boilerplate, then split on `<h2>` / `<h3>` boundaries (`##` /
`###` for markdown). The heading's `id` becomes the chunk `anchor` when it has one. Any section
over 800 tokens — the same budget `RagProperties.chunkSize` uses at runtime — is split further on
paragraph, then line, then word boundaries.

The script asserts loudly rather than silently emitting a broken bundle: it fails when the chunk
count leaves the 350–600 range, when any chunk exceeds the token budget, when two chunks collide
on `id`, when an excluded path appears, or when a page has no `<main>`, no `<h1>` or no canonical
URL.

The route table and lifecycle summary that `quick-reference.txt` is rendered from are declared at
the bottom of `.github/scripts/build-help-corpus.mjs`. Adding a route to the application means
adding a line there and regenerating.

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
