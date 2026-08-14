package com.bablsoft.accessflow.scim.internal.web.scim;

import com.bablsoft.accessflow.core.api.QuotaExceededException;
import com.bablsoft.accessflow.core.api.QuotaType;
import com.bablsoft.accessflow.scim.internal.protocol.ScimInvalidFilterException;
import com.bablsoft.accessflow.scim.internal.protocol.ScimResourceNotFoundException;
import com.bablsoft.accessflow.scim.internal.protocol.ScimUniquenessException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ScimErrorHandlerTest {

    private final ScimErrorHandler handler = new ScimErrorHandler();

    @Test
    void protocolExceptionsMapToTheirStatusAndScimType() {
        var notFound = handler.handleProtocol(
                new ScimResourceNotFoundException("User", "abc"));
        assertThat(notFound.getStatusCode().value()).isEqualTo(404);
        assertThat(notFound.getBody().scimType()).isNull();

        var conflict = handler.handleProtocol(new ScimUniquenessException("dup"));
        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        assertThat(conflict.getBody().scimType()).isEqualTo("uniqueness");

        var badFilter = handler.handleProtocol(new ScimInvalidFilterException("bad"));
        assertThat(badFilter.getStatusCode().value()).isEqualTo(400);
        assertThat(badFilter.getBody().scimType()).isEqualTo("invalidFilter");
        assertThat(badFilter.getBody().schemas())
                .containsExactly("urn:ietf:params:scim:api:messages:2.0:Error");
    }

    @Test
    void quotaMapsTo403() {
        var response = handler.handleQuota(
                new QuotaExceededException(QuotaType.USER, UUID.randomUUID(), 5, 5));

        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void unexpectedFailuresBecomeOpaque500() {
        var response = handler.handleUnexpected(new IllegalStateException("secret detail"));

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().detail()).isEqualTo("Internal error");
    }
}
