package com.bablsoft.accessflow.core.internal.persistence.repo;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.internal.persistence.entity.DataClassificationTagEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class DataClassificationTagRepositoryIntegrationTest {

    @Autowired DataClassificationTagRepository tagRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired OrganizationRepository organizationRepository;

    private OrganizationEntity organization;
    private DatasourceEntity datasource;

    @BeforeEach
    void setUp() {
        tagRepository.deleteAll();
        datasourceRepository.deleteAll();
        organizationRepository.deleteAll();
        organization = new OrganizationEntity();
        organization.setId(UUID.randomUUID());
        organization.setName("org-" + organization.getId());
        organization.setSlug("org-" + organization.getId());
        organization = organizationRepository.save(organization);
        datasource = saveDatasource();
    }

    @Test
    void existsByDistinguishesColumnAndClassification() {
        tagRepository.save(tag("users", "email", DataClassification.PII));

        assertThat(tagRepository
                .existsByOrganizationIdAndDatasourceIdAndTableNameAndColumnNameAndClassification(
                        organization.getId(), datasource.getId(), "users", "email", DataClassification.PII))
                .isTrue();
        assertThat(tagRepository
                .existsByOrganizationIdAndDatasourceIdAndTableNameAndColumnNameAndClassification(
                        organization.getId(), datasource.getId(), "users", "email", DataClassification.GDPR))
                .isFalse();
    }

    @Test
    void uniqueIndexRejectsDuplicateColumnTag() {
        tagRepository.saveAndFlush(tag("users", "email", DataClassification.PII));

        assertThatThrownBy(() -> tagRepository.saveAndFlush(tag("users", "email", DataClassification.PII)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void uniqueIndexRejectsDuplicateTableLevelTagDespiteNullColumn() {
        tagRepository.saveAndFlush(tag("orders", null, DataClassification.PCI));

        // COALESCE(column_name,'') collapses the two NULL column rows so the duplicate is rejected.
        assertThatThrownBy(() -> tagRepository.saveAndFlush(tag("orders", null, DataClassification.PCI)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findAllByOrganizationOrdersByCreatedAt() {
        tagRepository.save(tag("users", "email", DataClassification.PII));
        tagRepository.save(tag("orders", null, DataClassification.PCI));

        assertThat(tagRepository.findAllByOrganizationIdOrderByCreatedAtAsc(organization.getId()))
                .hasSize(2);
    }

    private DataClassificationTagEntity tag(String table, String column, DataClassification classification) {
        var entity = new DataClassificationTagEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(organization.getId());
        entity.setDatasourceId(datasource.getId());
        entity.setTableName(table);
        entity.setColumnName(column);
        entity.setClassification(classification);
        return entity;
    }

    private DatasourceEntity saveDatasource() {
        var ds = new DatasourceEntity();
        ds.setId(UUID.randomUUID());
        ds.setOrganization(organization);
        ds.setName("DS");
        ds.setDbType(DbType.POSTGRESQL);
        ds.setHost("nope.invalid");
        ds.setPort(65000);
        ds.setDatabaseName("appdb");
        ds.setUsername("svc");
        ds.setPasswordEncrypted("enc");
        ds.setSslMode(SslMode.DISABLE);
        ds.setConnectionPoolSize(10);
        ds.setMaxRowsPerQuery(1000);
        ds.setRequireReviewReads(false);
        ds.setRequireReviewWrites(true);
        ds.setAiAnalysisEnabled(false);
        ds.setActive(true);
        return datasourceRepository.save(ds);
    }
}
