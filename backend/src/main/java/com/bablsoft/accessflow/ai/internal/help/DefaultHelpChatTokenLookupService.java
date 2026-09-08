package com.bablsoft.accessflow.ai.internal.help;

import com.bablsoft.accessflow.ai.internal.persistence.repo.HelpChatMessageRepository;
import com.bablsoft.accessflow.core.api.HelpChatTokenLookupService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * The {@code ai} side of the monthly token budget (AF-904, epic AF-899 decision 10): help spend, read
 * from the table this module owns, for the budget check that lives in {@code core}.
 */
@Service
@RequiredArgsConstructor
public class DefaultHelpChatTokenLookupService implements HelpChatTokenLookupService {

    private final HelpChatMessageRepository messageRepository;

    @Override
    @Transactional(readOnly = true)
    public long sumTokensSince(UUID organizationId, Instant since) {
        return messageRepository.sumTokensSince(organizationId, since);
    }
}
