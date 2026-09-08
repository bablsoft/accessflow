package com.bablsoft.accessflow.ai.api;

/**
 * One documentation section the answer cited, resolved <em>server-side</em> from a {@code [n]} index
 * the model emitted back to the chunk that was actually retrieved for this turn (AF-903).
 *
 * <p>This record is the reason the model is told to emit indices and never URLs (epic AF-899
 * decision 6). Every field here comes from the corpus the server retrieved, so a jailbroken model
 * cannot turn the panel into a phishing surface or exfiltrate the prompt's route and permission
 * context through a crafted query string. An index the model invented — one outside the retrieved
 * set — produces no citation at all; it is dropped rather than rendered.
 *
 * @param index   the {@code [n]} index as it appears in the answer text, 1-based
 * @param chunkId content-hash id of the chunk in {@code corpus.jsonl}
 * @param title   heading of the documentation section
 * @param section documentation area label (Guides, Connectors, Reference, …)
 * @param anchor  heading anchor within the page, or empty
 * @param url     public documentation URL of the section, from the corpus and never from the model
 */
public record HelpChatCitation(
        int index,
        String chunkId,
        String title,
        String section,
        String anchor,
        String url) {
}
