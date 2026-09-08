package com.bablsoft.accessflow.ai.internal.help;

import com.bablsoft.accessflow.ai.api.HelpChatAnswer;
import com.bablsoft.accessflow.ai.api.HelpChatCitation;
import com.bablsoft.accessflow.ai.api.HelpChatQuestionRequiredException;
import com.bablsoft.accessflow.ai.api.HelpChatRequest;
import com.bablsoft.accessflow.ai.api.HelpChatService;
import com.bablsoft.accessflow.ai.api.HelpChatUnavailableException;
import com.bablsoft.accessflow.ai.internal.AiAnalyzerStrategyHolder;
import com.bablsoft.accessflow.ai.internal.AiRateLimiter;
import com.bablsoft.accessflow.ai.internal.persistence.entity.HelpAgentConfigEntity;
import com.bablsoft.accessflow.ai.internal.persistence.repo.HelpAgentConfigRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Answers one help question: enforce both rate limits, retrieve, render, call the model, then resolve
 * the citations the model claimed against the chunks it was actually given (AF-903).
 *
 * <p><strong>Citations are resolved server-side, from indices only.</strong> The model emits
 * {@code [n]}; this class maps each one back to the retrieved chunk at that position and builds the
 * {@link HelpChatCitation} from the corpus metadata. An index outside the retrieved set is dropped
 * rather than rendered, and a URL the model wrote in its prose is never read as a citation — it is
 * just text, and the client renders text without auto-linking. That is what keeps a free-text model
 * endpoint from becoming a phishing surface inside the product (epic AF-899 decision 6).
 *
 * <p><strong>Retrieval failing is a mode, not an error.</strong> Retrieval switched off, an
 * unavailable store, a stale or failed index, or a search that simply matched nothing all fall back
 * to the bundled quick-reference block. The user still gets an answer; it just cannot cite a section.
 *
 * <p><strong>No persistence.</strong> The conversation arrives in the request and leaves in the
 * answer. Storing it, and charging its tokens to a session, is the caller's job (AF-904).
 */
@Service
@RequiredArgsConstructor
public class DefaultHelpChatService implements HelpChatService {

    private static final Logger log = LoggerFactory.getLogger(DefaultHelpChatService.class);

    /**
     * A bracketed citation: {@code [1]}, or the grouped {@code [2, 3]} form the preamble also permits.
     * Bounded to three digits so a stray number in prose cannot make the scan quadratic.
     */
    private static final Pattern CITATION = Pattern.compile("\\[\\s*(\\d{1,3}(?:\\s*,\\s*\\d{1,3})*)\\s*]");

    private final HelpAgentConfigRepository configRepository;
    private final HelpCorpusRetrieverFactory retrieverFactory;
    private final HelpQuickReference quickReference;
    private final HelpChatPromptRenderer promptRenderer;
    private final AiRateLimiter aiRateLimiter;
    private final HelpChatRateLimiter helpChatRateLimiter;
    // Lazily resolved: integration tests that @MockitoBean AiAnalyzerStrategy replace the holder bean
    // with a bare interface mock, which is not assignable to the concrete type an eager field needs.
    private final ObjectProvider<AiAnalyzerStrategyHolder> strategyHolder;

    @Override
    public HelpChatAnswer answer(HelpChatRequest request) {
        var config = loadAnswerableConfig(request);
        var question = HelpChatPromptRenderer.truncate(request.question(),
                config.getMaxQuestionChars());
        if (question.isBlank()) {
            throw new HelpChatQuestionRequiredException();
        }
        // Both counters increment before the model call, so a turn that then fails still counts
        // against the window — otherwise a client retrying on error is never limited at all.
        aiRateLimiter.enforce(request.organizationId());
        helpChatRateLimiter.enforce(request.organizationId(), request.userId(),
                config.getPerUserRequestsPerMinute());

        var chunks = retrieve(config, question);
        var prompt = promptRenderer.render(config, request, question, chunks,
                chunks.isEmpty() ? quickReference.text() : null);
        var invocation = strategyHolder.getObject().chatFor(config.getOrganizationId(),
                config.getAiConfigId(), prompt.systemPreamble(), prompt.conversation());
        var text = invocation.text().strip();
        return new HelpChatAnswer(text, resolveCitations(text, prompt.citableChunks()),
                !chunks.isEmpty(), invocation.model(), invocation.promptTokens(),
                invocation.completionTokens());
    }

    /**
     * The row, or a refusal naming which of the three unanswerable states it is in. The unbound one
     * is not a misconfiguration an admin made — it is what deleting the bound {@code ai_config}
     * leaves behind ({@code ON DELETE SET NULL}) — so it gets its own message.
     *
     * <p>No {@code @Transactional} anywhere on this path on purpose: the single row read needs none,
     * and the model call that follows it takes seconds, which is not something to hold a pooled
     * connection across.
     */
    private HelpAgentConfigEntity loadAnswerableConfig(HelpChatRequest request) {
        var config = configRepository.findByOrganizationId(request.organizationId())
                .orElseThrow(() -> new HelpChatUnavailableException("error.help_chat.disabled"));
        if (!config.isEnabled()) {
            throw new HelpChatUnavailableException("error.help_chat.disabled");
        }
        if (config.getAiConfigId() == null) {
            throw new HelpChatUnavailableException("error.help_chat.unbound");
        }
        return config;
    }

    /** Retrieved chunks, or empty for every degraded path — the caller substitutes quick reference. */
    private List<RetrievedChunk> retrieve(HelpAgentConfigEntity config, String question) {
        if (!quickReference.usable(config)) {
            log.debug("Answering from the quick-reference block for organization {}: the help index "
                    + "is off, stale or errored", config.getOrganizationId());
            return List.of();
        }
        return retrieverFactory.retriever(config)
                .map(retriever -> retriever.retrieve(question))
                .orElseGet(List::of);
    }

    /**
     * Maps every {@code [n]} the answer emitted back onto the chunk numbered {@code n} in the prompt.
     * Indices the model invented — out of range, or any index at all in quick-reference mode, where
     * nothing was numbered — resolve to nothing and are dropped. Order follows first appearance in
     * the answer, so the panel lists them the way the reader meets them.
     */
    /**
     * The citation URL, or empty when the corpus offered something that is not a documentation link.
     *
     * <p>This value becomes an {@code href} by design — it is the only link the client is allowed to
     * render — and it reaches here from vector-store metadata written from {@code corpus.jsonl}. That
     * file ships in the JAR and is checksum-verified today, so the check is redundant now; it stops
     * being redundant the moment a corpus can be refreshed from a remote bundle (AF-907). The citation
     * itself survives with its title and section, because a section a reader can find by name is still
     * worth showing.
     */
    private static String documentationUrl(String url) {
        return url != null && url.startsWith("https://") ? url : "";
    }

    private List<HelpChatCitation> resolveCitations(String answer, List<RetrievedChunk> chunks) {
        if (chunks.isEmpty()) {
            return List.of();
        }
        Set<Integer> seen = new LinkedHashSet<>();
        var matcher = CITATION.matcher(answer);
        while (matcher.find()) {
            for (var token : matcher.group(1).split(",")) {
                seen.add(Integer.parseInt(token.strip()));
            }
        }
        var citations = new ArrayList<HelpChatCitation>(seen.size());
        for (var index : seen) {
            if (index < 1 || index > chunks.size()) {
                log.debug("Dropping citation [{}]: only {} chunks were retrieved", index,
                        chunks.size());
                continue;
            }
            var chunk = chunks.get(index - 1);
            citations.add(new HelpChatCitation(index, chunk.chunkId(), chunk.title(),
                    chunk.section(), chunk.anchor(), documentationUrl(chunk.url())));
        }
        return List.copyOf(citations);
    }
}
