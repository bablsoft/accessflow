package com.bablsoft.accessflow.ai.internal.help;

import java.util.List;

/**
 * One consistent view of the documentation corpus a process is answering from.
 *
 * <p>It exists because the active corpus can be replaced at runtime by an optional remote refresh
 * (AF-907). Reading {@code corpusVersion} and {@code chunks} through two separate calls would let a
 * swap land between them, storing the new corpus's text stamped with the previous version — a lie
 * that survives until the next indexing pass. Taking one snapshot removes the possibility rather
 * than narrowing the window.
 *
 * @param corpusVersion  {@code sha256(corpus.jsonl)[0..12]} of the corpus these chunks came from
 * @param chunks         the corpus itself, already immutable
 * @param quickReference the orientation block used when retrieval is unavailable
 */
public record HelpCorpusSnapshot(String corpusVersion, List<HelpCorpusChunk> chunks,
                                 String quickReference) {
}
