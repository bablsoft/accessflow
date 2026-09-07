package com.bablsoft.accessflow.ai.internal.help;

/**
 * The subset of {@code help-corpus/manifest.json} the runtime verifies. The generator also writes
 * {@code generatedAt}, {@code sourceCommit} and a per-source {@code sources[]} array; neither is
 * needed to load or check the bundle, and unknown properties are ignored so a newer generator can add
 * fields without breaking an older reader.
 *
 * @param schemaVersion        bundle format version — a value above what the code understands is
 *                             refused rather than silently read with dropped fields
 * @param corpusVersion        {@code sha256(corpus.jsonl)[0..12]}; content-derived, so re-ingestion is
 *                             an idempotent string compare
 * @param chunkCount           number of lines {@code corpus.jsonl} must contain
 * @param sha256               full digest of {@code corpus.jsonl}
 * @param quickReferenceSha256 full digest of {@code quick-reference.txt}
 */
record HelpCorpusManifest(
        int schemaVersion,
        String corpusVersion,
        int chunkCount,
        String sha256,
        String quickReferenceSha256) {
}
