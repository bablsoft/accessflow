package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.CustomJdbcDriverService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.UploadCustomDriverCommand;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.security.internal.web.model.CustomDriverListResponse;
import com.bablsoft.accessflow.security.internal.web.model.CustomDriverResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/datasources/drivers")
@Tag(name = "Custom JDBC Drivers",
        description = "Admin-uploaded JDBC driver JARs for the caller's organization")
@RequiredArgsConstructor
@Validated
@Slf4j
class CustomJdbcDriverController {

    private static final String DRIVER_CLASS_PATTERN =
            "^[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+$";
    private static final String SHA256_PATTERN = "^[a-fA-F0-9]{64}$";

    private final CustomJdbcDriverService customJdbcDriverService;
    private final AuditLogService auditLogService;

    @PostMapping(consumes = "multipart/form-data")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Upload a JDBC driver JAR (multipart)")
    @ApiResponse(responseCode = "201", description = "Driver registered")
    @ApiResponse(responseCode = "409", description = "A driver with this SHA-256 is already registered")
    @ApiResponse(responseCode = "413", description = "JAR exceeds the configured upload limit")
    @ApiResponse(responseCode = "422", description = "SHA-256 mismatch or driver class missing from JAR")
    ResponseEntity<CustomDriverResponse> uploadDriver(
            @RequestParam("jar") MultipartFile jar,
            @RequestParam("vendor_name") @NotBlank @Size(max = 100) String vendorName,
            @RequestParam("target_db_type") @NotNull DbType targetDbType,
            @RequestParam("driver_class") @NotBlank @Size(max = 255)
                @Pattern(regexp = DRIVER_CLASS_PATTERN) String driverClass,
            @RequestParam("expected_sha256") @NotBlank @Pattern(regexp = SHA256_PATTERN)
                String expectedSha256,
            Authentication authentication,
            HttpServletRequest httpRequest) throws IOException {
        var caller = currentClaims(authentication);
        String originalFilename = jar.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            originalFilename = "driver.jar";
        }
        var command = new UploadCustomDriverCommand(
                caller.organizationId(),
                caller.userId(),
                vendorName,
                targetDbType,
                driverClass,
                originalFilename,
                expectedSha256,
                jar.getSize(),
                jar.getInputStream());
        var created = customJdbcDriverService.register(command);

        var metadata = new HashMap<String, Object>();
        metadata.put("vendor_name", created.vendorName());
        metadata.put("target_db_type", created.targetDbType().name());
        metadata.put("jar_filename", created.jarFilename());
        metadata.put("jar_sha256", created.jarSha256());
        metadata.put("jar_size_bytes", created.jarSizeBytes());
        recordAudit(AuditAction.CUSTOM_DRIVER_UPLOADED, AuditResourceType.CUSTOM_JDBC_DRIVER,
                created.id(), caller, httpRequest, metadata);

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(CustomDriverResponse.from(created));
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "List the organization's uploaded JDBC drivers")
    @ApiResponse(responseCode = "200", description = "Drivers listed (may be empty)")
    CustomDriverListResponse listDrivers(Authentication authentication) {
        var caller = currentClaims(authentication);
        return CustomDriverListResponse.from(customJdbcDriverService.list(caller.organizationId()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Get an uploaded JDBC driver by id")
    @ApiResponse(responseCode = "200", description = "Driver details")
    @ApiResponse(responseCode = "404", description = "Driver not found in caller's organization")
    CustomDriverResponse getDriver(@PathVariable UUID id, Authentication authentication) {
        var caller = currentClaims(authentication);
        return CustomDriverResponse.from(
                customJdbcDriverService.get(id, caller.organizationId()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Delete an uploaded JDBC driver")
    @ApiResponse(responseCode = "204", description = "Driver removed")
    @ApiResponse(responseCode = "404", description = "Driver not found")
    @ApiResponse(responseCode = "409", description = "Driver is still referenced by a datasource")
    ResponseEntity<Void> deleteDriver(@PathVariable UUID id, Authentication authentication,
                                       HttpServletRequest httpRequest) {
        var caller = currentClaims(authentication);
        var view = customJdbcDriverService.get(id, caller.organizationId());
        customJdbcDriverService.delete(id, caller.organizationId());

        var metadata = new HashMap<String, Object>();
        metadata.put("vendor_name", view.vendorName());
        metadata.put("target_db_type", view.targetDbType().name());
        metadata.put("jar_sha256", view.jarSha256());
        recordAudit(AuditAction.CUSTOM_DRIVER_DELETED, AuditResourceType.CUSTOM_JDBC_DRIVER, id,
                caller, httpRequest, metadata);
        return ResponseEntity.noContent().build();
    }

    private JwtClaims currentClaims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }

    private void recordAudit(AuditAction action, AuditResourceType resourceType, UUID resourceId,
                             JwtClaims caller, HttpServletRequest httpRequest,
                             Map<String, Object> metadata) {
        try {
            var context = RequestAuditContext.from(httpRequest);
            auditLogService.record(new AuditEntry(
                    action,
                    resourceType,
                    resourceId,
                    caller.organizationId(),
                    caller.userId(),
                    new HashMap<>(metadata),
                    context.ipAddress(),
                    context.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for {} on {} {}", action, resourceType.dbValue(),
                    resourceId, ex);
        }
    }
}
