package com.bablsoft.accessflow.ai.internal.help;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.ai.api.AiConfigService;
import com.bablsoft.accessflow.ai.internal.persistence.entity.AiConfigEntity;
import com.bablsoft.accessflow.ai.internal.persistence.entity.HelpAgentConfigEntity;
import com.bablsoft.accessflow.ai.internal.persistence.repo.AiConfigRepository;
import com.bablsoft.accessflow.ai.internal.persistence.repo.HelpAgentConfigRepository;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Schema-level guarantees of {@code help_agent_config} that only a real Postgres can prove: the
 * defaults the migration writes, the one-row-per-org UNIQUE, and the deliberately weak
 * {@code ON DELETE SET NULL} binding to {@code ai_config}.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class HelpAgentConfigIntegrationTest {

    @Autowired HelpAgentConfigRepository repository;
    @Autowired AiConfigRepository aiConfigRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired AiConfigService aiConfigService;

    private UUID organizationId;

    @BeforeEach
    void setUp() {
        var org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Help Agent Org " + UUID.randomUUID());
        org.setSlug("help-agent-" + UUID.randomUUID());
        organizationRepository.save(org);
        organizationId = org.getId();
    }

    @Test
    void persistsReloadsAndUpdatesARow() {
        var saved = repository.save(row(organizationId));

        var reloaded = repository.findByOrganizationId(organizationId).orElseThrow();
        assertThat(reloaded.getId()).isEqualTo(saved.getId());
        assertThat(reloaded.isEnabled()).isFalse();
        assertThat(reloaded.isRetrievalEnabled()).isTrue();
        assertThat(reloaded.getTopK()).isEqualTo(6);
        assertThat(reloaded.getSimilarityThreshold()).isEqualTo(0.4);
        assertThat(reloaded.getMaxHistoryTurns()).isEqualTo(8);
        assertThat(reloaded.getMaxQuestionChars()).isEqualTo(2000);
        assertThat(reloaded.isSendUserContext()).isTrue();
        assertThat(reloaded.getRetentionDays()).isEqualTo(90);
        assertThat(reloaded.getPerUserRequestsPerMinute()).isEqualTo(6);
        assertThat(reloaded.getIndexedCorpusVersion()).isNull();
        assertThat(reloaded.getIndexedAt()).isNull();
        assertThat(reloaded.getIndexError()).isNull();

        reloaded.setEnabled(true);
        reloaded.setRetentionDays(30);
        reloaded.setIndexedCorpusVersion("c0ac599ef7fc");
        reloaded.setIndexedAt(Instant.now());
        repository.saveAndFlush(reloaded);

        var updated = repository.findByOrganizationId(organizationId).orElseThrow();
        assertThat(updated.isEnabled()).isTrue();
        assertThat(updated.getRetentionDays()).isEqualTo(30);
        assertThat(updated.getIndexedCorpusVersion()).isEqualTo("c0ac599ef7fc");
        assertThat(updated.getVersion()).isEqualTo(saved.getVersion() + 1);
    }

    @Test
    void rejectsASecondRowForTheSameOrganization() {
        repository.saveAndFlush(row(organizationId));

        assertThatThrownBy(() -> repository.saveAndFlush(row(organizationId)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findAllByEnabledTrueReturnsOnlySwitchedOnRows() {
        var off = repository.saveAndFlush(row(organizationId));
        var otherOrg = new OrganizationEntity();
        otherOrg.setId(UUID.randomUUID());
        otherOrg.setName("Help Agent Org " + UUID.randomUUID());
        otherOrg.setSlug("help-agent-" + UUID.randomUUID());
        organizationRepository.save(otherOrg);
        var on = row(otherOrg.getId());
        on.setEnabled(true);
        repository.saveAndFlush(on);

        assertThat(repository.findAllByEnabledTrue())
                .extracting(HelpAgentConfigEntity::getId)
                .contains(on.getId())
                .doesNotContain(off.getId());
    }

    @Test
    void deletingTheBoundAiConfigClearsTheBindingInsteadOfBlockingTheDelete() {
        var aiConfig = aiConfigRepository.saveAndFlush(aiConfig(organizationId));
        var helpConfig = row(organizationId);
        helpConfig.setAiConfigId(aiConfig.getId());
        helpConfig.setEnabled(true);
        repository.saveAndFlush(helpConfig);

        // A help binding must never join the AI_CONFIG_IN_USE guard that datasource bindings do.
        assertThatCode(() -> aiConfigService.delete(aiConfig.getId(), organizationId))
                .doesNotThrowAnyException();

        var reloaded = repository.findByOrganizationId(organizationId).orElseThrow();
        assertThat(reloaded.getAiConfigId()).isNull();
        assertThat(aiConfigRepository.findById(aiConfig.getId())).isEmpty();
    }

    private static HelpAgentConfigEntity row(UUID organizationId) {
        var entity = new HelpAgentConfigEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(organizationId);
        return entity;
    }

    private static AiConfigEntity aiConfig(UUID organizationId) {
        var config = new AiConfigEntity();
        config.setId(UUID.randomUUID());
        config.setOrganizationId(organizationId);
        config.setName("help-agent-" + UUID.randomUUID());
        config.setProvider(AiProviderType.OPENAI);
        config.setModel("gpt-4o");
        return config;
    }
}
