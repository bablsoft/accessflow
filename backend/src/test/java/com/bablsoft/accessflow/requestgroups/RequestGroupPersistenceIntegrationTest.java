package com.bablsoft.accessflow.requestgroups;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.apigov.api.ApiBodyType;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupItemStatus;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupStatus;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupTargetKind;
import com.bablsoft.accessflow.requestgroups.internal.persistence.entity.RequestGroupEntity;
import com.bablsoft.accessflow.requestgroups.internal.persistence.entity.RequestGroupItemEntity;
import com.bablsoft.accessflow.requestgroups.internal.persistence.repo.RequestGroupItemRepository;
import com.bablsoft.accessflow.requestgroups.internal.persistence.repo.RequestGroupRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates that the V106 migration applies, the request-group JPA entities map to the new tables
 * (enums, jsonb columns), and the native scheduled-due scan executes against real Postgres — booting
 * the full application context (so the new {@code requestgroups} beans wire cleanly).
 */
@SpringBootTest(
        properties = "accessflow.encryption-key=00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff")
@ImportTestcontainers(TestcontainersConfig.class)
class RequestGroupPersistenceIntegrationTest {

    @Autowired
    private RequestGroupRepository groupRepository;
    @Autowired
    private RequestGroupItemRepository itemRepository;

    @DynamicPropertySource
    static void rsaProperties(DynamicPropertyRegistry registry) throws Exception {
        var kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        var privateKey = (RSAPrivateCrtKey) kpg.generateKeyPair().getPrivate();
        var pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(privateKey.getEncoded())
                + "\n-----END PRIVATE KEY-----";
        registry.add("accessflow.jwt.private-key", () -> pem);
    }

    @Test
    void persistsGroupWithQueryAndApiMembersAndScansScheduledDue() {
        var group = new RequestGroupEntity();
        group.setId(UUID.randomUUID());
        group.setOrganizationId(UUID.randomUUID());
        group.setSubmittedBy(UUID.randomUUID());
        group.setName("nightly bundle");
        group.setStatus(RequestGroupStatus.APPROVED);
        group.setScheduledFor(Instant.now().minusSeconds(60));
        groupRepository.saveAndFlush(group);

        var query = new RequestGroupItemEntity();
        query.setId(UUID.randomUUID());
        query.setGroupId(group.getId());
        query.setSequenceOrder(0);
        query.setTargetKind(RequestGroupTargetKind.QUERY);
        query.setDatasourceId(UUID.randomUUID());
        query.setSqlText("SELECT 1");
        query.setStatus(RequestGroupItemStatus.PENDING);
        itemRepository.saveAndFlush(query);

        var api = new RequestGroupItemEntity();
        api.setId(UUID.randomUUID());
        api.setGroupId(group.getId());
        api.setSequenceOrder(1);
        api.setTargetKind(RequestGroupTargetKind.API_CALL);
        api.setApiConnectorId(UUID.randomUUID());
        api.setVerb("POST");
        api.setRequestPath("/orders");
        api.setBodyType(ApiBodyType.RAW);
        api.setRequestHeaders("{\"X-Test\":\"1\"}");
        api.setStatus(RequestGroupItemStatus.PENDING);
        itemRepository.saveAndFlush(api);

        var items = itemRepository.findByGroupIdOrderBySequenceOrderAsc(group.getId());
        assertThat(items).hasSize(2);
        assertThat(items.get(0).getTargetKind()).isEqualTo(RequestGroupTargetKind.QUERY);
        assertThat(items.get(1).getTargetKind()).isEqualTo(RequestGroupTargetKind.API_CALL);

        // Native ::request_group_status cast against real Postgres.
        assertThat(groupRepository.findScheduledDueIds(Instant.now())).contains(group.getId());
    }
}
