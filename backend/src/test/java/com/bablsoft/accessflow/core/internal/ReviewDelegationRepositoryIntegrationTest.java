package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.DelegationScopeKind;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.ReviewDelegationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.ReviewDelegationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the review-delegation queries against real PostgreSQL (#622).
 *
 * <p>{@code search} in particular uses the {@code (:param is null or column = :param)} form, which
 * can trip Hibernate's parameter-type inference on PostgreSQL ("could not determine data type of
 * parameter") for UUID binds — a failure that only appears at runtime, and only on the admin
 * listing. The unit tests mock the repository, so this is the only thing that would catch it.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class ReviewDelegationRepositoryIntegrationTest {

    @Autowired ReviewDelegationRepository delegationRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;

    private OrganizationEntity organization;
    private UserEntity delegator;
    private UserEntity delegate;

    @DynamicPropertySource
    static void securityProperties(DynamicPropertyRegistry registry) throws Exception {
        var kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        var kp = kpg.generateKeyPair();
        var privateKey = (RSAPrivateCrtKey) kp.getPrivate();
        var pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(privateKey.getEncoded())
                + "\n-----END PRIVATE KEY-----";
        registry.add("accessflow.jwt.private-key", () -> pem);
        registry.add("accessflow.encryption-key", () ->
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    }

    @BeforeEach
    void setUp() {
        cleanup();
        organization = saveOrganization();
        delegator = saveUser("delegator", UserRoleType.REVIEWER, true);
        delegate = saveUser("delegate", UserRoleType.REVIEWER, true);
    }

    @AfterEach
    void cleanup() {
        delegationRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    private static final Instant NOW = Instant.parse("2026-08-16T12:00:00Z");

    @Test
    void searchWithNoFiltersReturnsEveryDelegationInTheOrganization() {
        save(open());

        var page = delegationRepository.search(organization.getId(), null, null, false, NOW,
                PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    void searchNarrowsByDelegatorAndByDelegate() {
        save(open());

        assertThat(delegationRepository.search(organization.getId(), delegator.getId(), null, false,
                NOW, PageRequest.of(0, 20)).getContent()).hasSize(1);
        assertThat(delegationRepository.search(organization.getId(), UUID.randomUUID(), null, false,
                NOW, PageRequest.of(0, 20)).getContent()).isEmpty();
        assertThat(delegationRepository.search(organization.getId(), null, delegate.getId(), false,
                NOW, PageRequest.of(0, 20)).getContent()).hasSize(1);
    }

    @Test
    void searchActiveOnlyExcludesRevokedScheduledAndExpiredRows() {
        save(open());
        var revoked = open();
        revoked.setRevokedAt(NOW.minus(1, ChronoUnit.HOURS));
        save(revoked);
        var scheduled = open();
        scheduled.setStartsAt(NOW.plus(1, ChronoUnit.DAYS));
        scheduled.setEndsAt(NOW.plus(8, ChronoUnit.DAYS));
        save(scheduled);
        var expired = open();
        expired.setStartsAt(NOW.minus(8, ChronoUnit.DAYS));
        expired.setEndsAt(NOW.minus(1, ChronoUnit.DAYS));
        save(expired);

        assertThat(delegationRepository.search(organization.getId(), null, null, true, NOW,
                PageRequest.of(0, 20)).getContent()).hasSize(1);
        assertThat(delegationRepository.search(organization.getId(), null, null, false, NOW,
                PageRequest.of(0, 20)).getContent()).hasSize(4);
    }

    @Test
    void findActiveForDelegateHonoursTheHalfOpenWindow() {
        var row = open();
        row.setStartsAt(NOW);
        row.setEndsAt(NOW.plus(1, ChronoUnit.DAYS));
        save(row);

        // starts_at is inclusive, ends_at exclusive.
        assertThat(delegationRepository.findActiveForDelegate(organization.getId(),
                delegate.getId(), NOW)).hasSize(1);
        assertThat(delegationRepository.findActiveForDelegate(organization.getId(),
                delegate.getId(), NOW.minus(1, ChronoUnit.SECONDS))).isEmpty();
        assertThat(delegationRepository.findActiveForDelegate(organization.getId(),
                delegate.getId(), NOW.plus(1, ChronoUnit.DAYS))).isEmpty();
    }

    @Test
    void findActiveForDelegateDropsTheRowWhenEitherPartyIsDeactivated() {
        save(open());
        assertThat(delegationRepository.findActiveForDelegate(organization.getId(),
                delegate.getId(), NOW)).hasSize(1);

        delegator.setActive(false);
        userRepository.save(delegator);
        assertThat(delegationRepository.findActiveForDelegate(organization.getId(),
                delegate.getId(), NOW)).isEmpty();

        delegator.setActive(true);
        userRepository.save(delegator);
        delegate.setActive(false);
        userRepository.save(delegate);
        assertThat(delegationRepository.findActiveForDelegate(organization.getId(),
                delegate.getId(), NOW)).isEmpty();
    }

    @Test
    void findActiveForDelegateReadsTheScopeBackAsThePgEnum() {
        var scoped = open();
        var datasourceId = UUID.randomUUID();
        scoped.setScopeKind(DelegationScopeKind.DATASOURCE);
        scoped.setScopeId(datasourceId);
        save(scoped);

        assertThat(delegationRepository.findActiveForDelegate(organization.getId(),
                delegate.getId(), NOW)).singleElement().satisfies(row -> {
                    assertThat(row.getScopeKind()).isEqualTo(DelegationScopeKind.DATASOURCE);
                    assertThat(row.getScopeId()).isEqualTo(datasourceId);
                });
    }

    @Test
    void countOpenForDelegatorIgnoresRevokedAndExpiredRows() {
        save(open());
        var revoked = open();
        revoked.setRevokedAt(NOW);
        save(revoked);
        var expired = open();
        expired.setStartsAt(NOW.minus(8, ChronoUnit.DAYS));
        expired.setEndsAt(NOW.minus(1, ChronoUnit.DAYS));
        save(expired);

        assertThat(delegationRepository.countOpenForDelegator(organization.getId(),
                delegator.getId(), NOW)).isEqualTo(1);
    }

    private ReviewDelegationEntity open() {
        var entity = new ReviewDelegationEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(organization.getId());
        entity.setDelegatorId(delegator.getId());
        entity.setDelegateId(delegate.getId());
        entity.setStartsAt(NOW.minus(1, ChronoUnit.DAYS));
        entity.setEndsAt(NOW.plus(1, ChronoUnit.DAYS));
        entity.setCreatedBy(delegator.getId());
        return entity;
    }

    private void save(ReviewDelegationEntity entity) {
        delegationRepository.save(entity);
    }

    private OrganizationEntity saveOrganization() {
        var org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Org-" + UUID.randomUUID());
        org.setSlug("delegation-" + UUID.randomUUID());
        return organizationRepository.save(org);
    }

    private UserEntity saveUser(String prefix, UserRoleType role, boolean active) {
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail(prefix + "-" + UUID.randomUUID() + "@example.com");
        user.setDisplayName(prefix);
        user.setPasswordHash("hash");
        user.setRole(role);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setActive(active);
        user.setOrganization(organization);
        return userRepository.save(user);
    }
}
