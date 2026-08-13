package com.bablsoft.accessflow.scim.internal.web.scim;

import com.bablsoft.accessflow.core.api.QuotaExceededException;
import com.bablsoft.accessflow.scim.internal.protocol.ScimError;
import com.bablsoft.accessflow.scim.internal.protocol.ScimProtocolException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders every {@code /scim/v2/**} failure as the SCIM error envelope (#621) — never RFC 9457
 * ProblemDetail. Scoped by {@code assignableTypes}, so the rest of the application keeps its
 * ProblemDetail contract. Detail strings are intentionally not localized (IdP consumer).
 */
@RestControllerAdvice(assignableTypes = {
        ScimUserController.class, ScimGroupController.class, ScimDiscoveryController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
class ScimErrorHandler {

    @ExceptionHandler(ScimProtocolException.class)
    ResponseEntity<ScimError> handleProtocol(ScimProtocolException ex) {
        return ResponseEntity.status(ex.status())
                .contentType(ScimMediaTypes.SCIM_JSON)
                .body(ScimError.of(ex.status(), ex.scimType(), ex.getMessage()));
    }

    @ExceptionHandler(QuotaExceededException.class)
    ResponseEntity<ScimError> handleQuota(QuotaExceededException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .contentType(ScimMediaTypes.SCIM_JSON)
                .body(ScimError.of(403, null, "Organization user quota exceeded"));
    }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<ScimError> handleUnexpected(RuntimeException ex) {
        log.error("Unexpected error handling SCIM request", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(ScimMediaTypes.SCIM_JSON)
                .body(ScimError.of(500, null, "Internal error"));
    }
}
