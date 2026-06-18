package com.bablsoft.accessflow.core.api;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OrganizationNotFoundExceptionTest {

    @Test
    void messageContainsId() {
        var id = UUID.randomUUID();
        var ex = new OrganizationNotFoundException(id);

        assertThat(ex).isInstanceOf(OrganizationAdminException.class);
        assertThat(ex.getMessage()).contains(id.toString());
    }
}
