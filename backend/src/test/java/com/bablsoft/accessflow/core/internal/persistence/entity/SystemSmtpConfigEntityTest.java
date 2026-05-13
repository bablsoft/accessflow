package com.bablsoft.accessflow.core.internal.persistence.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SystemSmtpConfigEntityTest {

    @Test
    void gettersReturnSetValues() {
        var entity = new SystemSmtpConfigEntity();
        var id = UUID.randomUUID();
        var orgId = UUID.randomUUID();
        var now = Instant.now();

        entity.setId(id);
        entity.setOrganizationId(orgId);
        entity.setHost("smtp.example.com");
        entity.setPort(587);
        entity.setUsername("user");
        entity.setPasswordEncrypted("CIPHER");
        entity.setTls(true);
        entity.setFromAddress("from@example.com");
        entity.setFromName("From");
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        assertThat(entity.getId()).isEqualTo(id);
        assertThat(entity.getOrganizationId()).isEqualTo(orgId);
        assertThat(entity.getHost()).isEqualTo("smtp.example.com");
        assertThat(entity.getPort()).isEqualTo(587);
        assertThat(entity.getUsername()).isEqualTo("user");
        assertThat(entity.getPasswordEncrypted()).isEqualTo("CIPHER");
        assertThat(entity.isTls()).isTrue();
        assertThat(entity.getFromAddress()).isEqualTo("from@example.com");
        assertThat(entity.getFromName()).isEqualTo("From");
        assertThat(entity.getCreatedAt()).isEqualTo(now);
        assertThat(entity.getUpdatedAt()).isEqualTo(now);
    }
}
