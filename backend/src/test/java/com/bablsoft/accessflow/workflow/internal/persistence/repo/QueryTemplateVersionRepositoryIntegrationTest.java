package com.bablsoft.accessflow.workflow.internal.persistence.repo;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.workflow.api.QueryTemplateChangeType;
import com.bablsoft.accessflow.workflow.api.QueryTemplateVisibility;
import com.bablsoft.accessflow.workflow.internal.persistence.entity.QueryTemplateEntity;
import com.bablsoft.accessflow.workflow.internal.persistence.entity.QueryTemplateVersionEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class QueryTemplateVersionRepositoryIntegrationTest {

    @Autowired QueryTemplateVersionRepository versionRepository;
    @Autowired QueryTemplateRepository queryTemplateRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;

    private OrganizationEntity organization;
    private UserEntity owner;
    private QueryTemplateEntity template;

    @DynamicPropertySource
    static void env(DynamicPropertyRegistry registry) throws Exception {
        var kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        var kp = kpg.generateKeyPair();
        var pk = (RSAPrivateCrtKey) kp.getPrivate();
        var pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(pk.getEncoded())
                + "\n-----END PRIVATE KEY-----";
        registry.add("accessflow.jwt.private-key", () -> pem);
        registry.add("accessflow.encryption-key", () ->
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    }

    @BeforeEach
    void setUp() {
        versionRepository.deleteAll();
        queryTemplateRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();
        organization = organizationRepository.save(newOrg());
        owner = userRepository.save(newUser(organization, "alice@x.com"));
        template = queryTemplateRepository.save(newTemplate());
    }

    @Test
    void persistsTagsArrayAndBothEnums() {
        var version = newVersion(1, QueryTemplateChangeType.CREATED);
        version.setTags(new String[]{"billing", "ops"});
        version.setVisibility(QueryTemplateVisibility.TEAM);
        versionRepository.save(version);

        var loaded = versionRepository.findById(version.getId()).orElseThrow();
        assertThat(loaded.getTags()).containsExactly("billing", "ops");
        assertThat(loaded.getVisibility()).isEqualTo(QueryTemplateVisibility.TEAM);
        assertThat(loaded.getChangeType()).isEqualTo(QueryTemplateChangeType.CREATED);
    }

    @Test
    void listsNewestFirstAndFindsLatest() {
        versionRepository.save(newVersion(1, QueryTemplateChangeType.CREATED));
        versionRepository.save(newVersion(2, QueryTemplateChangeType.UPDATED));
        versionRepository.save(newVersion(3, QueryTemplateChangeType.RESTORED));

        var page = versionRepository.findByTemplateIdOrderByVersionNumberDesc(
                template.getId(), PageRequest.of(0, 10));
        assertThat(page.getContent()).extracting(QueryTemplateVersionEntity::getVersionNumber)
                .containsExactly(3, 2, 1);

        var latest = versionRepository.findTopByTemplateIdOrderByVersionNumberDesc(template.getId());
        assertThat(latest).isPresent();
        assertThat(latest.get().getVersionNumber()).isEqualTo(3);
    }

    @Test
    void uniqueIndexOnTemplateAndVersionNumber() {
        versionRepository.save(newVersion(1, QueryTemplateChangeType.CREATED));
        versionRepository.flush();

        var duplicate = newVersion(1, QueryTemplateChangeType.UPDATED);
        assertThatThrownBy(() -> {
            versionRepository.save(duplicate);
            versionRepository.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingTemplateCascadesVersions() {
        versionRepository.save(newVersion(1, QueryTemplateChangeType.CREATED));
        queryTemplateRepository.delete(template);
        queryTemplateRepository.flush();

        assertThat(versionRepository.findByTemplateIdOrderByVersionNumberDesc(
                template.getId(), PageRequest.of(0, 10)).getContent()).isEmpty();
    }

    private QueryTemplateVersionEntity newVersion(int number, QueryTemplateChangeType changeType) {
        var version = new QueryTemplateVersionEntity();
        version.setId(UUID.randomUUID());
        version.setTemplateId(template.getId());
        version.setOrganizationId(organization.getId());
        version.setVersionNumber(number);
        version.setName("Top users");
        version.setBody("SELECT * FROM users");
        version.setVisibility(QueryTemplateVisibility.PRIVATE);
        version.setChangeType(changeType);
        version.setTags(new String[0]);
        version.setAuthorId(owner.getId());
        version.setCreatedAt(Instant.now());
        return version;
    }

    private QueryTemplateEntity newTemplate() {
        var entity = new QueryTemplateEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(organization.getId());
        entity.setOwnerId(owner.getId());
        entity.setName("Top users");
        entity.setBody("SELECT * FROM users");
        entity.setVisibility(QueryTemplateVisibility.PRIVATE);
        entity.setTags(new String[0]);
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        return entity;
    }

    private OrganizationEntity newOrg() {
        var org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("org-" + org.getId());
        org.setSlug("org-" + org.getId());
        return org;
    }

    private UserEntity newUser(OrganizationEntity org, String email) {
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setDisplayName(email);
        user.setOrganization(org);
        user.setRole(UserRoleType.ANALYST);
        user.setActive(true);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setPasswordHash("hash");
        user.setCreatedAt(Instant.now());
        return user;
    }
}
