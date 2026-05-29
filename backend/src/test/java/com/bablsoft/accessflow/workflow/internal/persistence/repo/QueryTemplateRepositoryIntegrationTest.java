package com.bablsoft.accessflow.workflow.internal.persistence.repo;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.workflow.api.QueryTemplateVisibility;
import com.bablsoft.accessflow.workflow.internal.persistence.entity.QueryTemplateEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class QueryTemplateRepositoryIntegrationTest {

    @Autowired QueryTemplateRepository queryTemplateRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;

    private OrganizationEntity organization;
    private UserEntity owner;

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
        queryTemplateRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();
        organization = organizationRepository.save(newOrg());
        owner = userRepository.save(newUser(organization, "alice@x.com"));
    }

    @Test
    void persistsTagsArrayAndVisibilityEnum() {
        var entity = newTemplate("Top users", QueryTemplateVisibility.TEAM);
        entity.setTags(new String[]{"billing", "ops"});
        queryTemplateRepository.save(entity);

        var loaded = queryTemplateRepository.findById(entity.getId()).orElseThrow();
        assertThat(loaded.getTags()).containsExactly("billing", "ops");
        assertThat(loaded.getVisibility()).isEqualTo(QueryTemplateVisibility.TEAM);
    }

    @Test
    void uniqueIndexOnOrgOwnerLowerNameIsCaseInsensitive() {
        queryTemplateRepository.save(newTemplate("Top USERS", QueryTemplateVisibility.PRIVATE));
        queryTemplateRepository.flush();

        var duplicate = newTemplate("top users", QueryTemplateVisibility.PRIVATE);
        assertThatThrownBy(() -> {
            queryTemplateRepository.save(duplicate);
            queryTemplateRepository.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findByOrgOwnerNameIgnoreCaseFindsRowsRegardlessOfCase() {
        queryTemplateRepository.save(newTemplate("Mixed Case", QueryTemplateVisibility.PRIVATE));

        Optional<QueryTemplateEntity> hit = queryTemplateRepository
                .findByOrganizationIdAndOwnerIdAndNameIgnoreCase(
                        organization.getId(), owner.getId(), "MIXED case");

        assertThat(hit).isPresent();
        assertThat(hit.get().getName()).isEqualTo("Mixed Case");
    }

    private QueryTemplateEntity newTemplate(String name, QueryTemplateVisibility visibility) {
        var entity = new QueryTemplateEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(organization.getId());
        entity.setOwnerId(owner.getId());
        entity.setName(name);
        entity.setBody("SELECT * FROM users WHERE id = :id");
        entity.setVisibility(visibility);
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
