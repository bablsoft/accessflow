package com.partqam.accessflow.core.internal.persistence.repo;

import com.partqam.accessflow.TestcontainersConfig;
import com.partqam.accessflow.core.api.DbType;
import com.partqam.accessflow.core.api.EditionType;
import com.partqam.accessflow.core.api.SslMode;
import com.partqam.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.partqam.accessflow.core.internal.persistence.entity.OrganizationEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class DatasourceRepositoryIntegrationTest {

    @Autowired DatasourceRepository datasourceRepository;
    @Autowired OrganizationRepository organizationRepository;

    private OrganizationEntity organization;

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
        datasourceRepository.deleteAll();
        organizationRepository.deleteAll();
        organization = new OrganizationEntity();
        organization.setId(UUID.randomUUID());
        organization.setName("org-" + organization.getId());
        organization.setSlug("org-" + organization.getId());
        organization.setEdition(EditionType.COMMUNITY);
        organization = organizationRepository.save(organization);
    }

    @ParameterizedTest
    @EnumSource(DbType.class)
    void persistsEveryDbTypeEnumValue(DbType dbType) {
        var entity = new DatasourceEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganization(organization);
        entity.setName("ds-" + dbType.name());
        entity.setDbType(dbType);
        entity.setHost("db");
        entity.setPort(1);
        entity.setDatabaseName("d");
        entity.setUsername("u");
        entity.setPasswordEncrypted("ENC(x)");
        entity.setSslMode(SslMode.DISABLE);
        entity.setConnectionPoolSize(1);
        entity.setMaxRowsPerQuery(1);
        entity.setRequireReviewReads(false);
        entity.setRequireReviewWrites(false);
        entity.setAiAnalysisEnabled(false);
        entity.setActive(true);

        var saved = datasourceRepository.save(entity);
        var reloaded = datasourceRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getDbType()).isEqualTo(dbType);
    }
}
