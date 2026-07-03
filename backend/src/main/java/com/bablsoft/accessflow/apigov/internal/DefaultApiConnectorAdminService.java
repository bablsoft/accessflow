package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiConnectionTestResult;
import com.bablsoft.accessflow.apigov.api.ApiConnectorAdminService;
import com.bablsoft.accessflow.apigov.api.ApiConnectorGroupPermissionView;
import com.bablsoft.accessflow.apigov.api.ApiConnectorNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiConnectorPermissionNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiConnectorPermissionView;
import com.bablsoft.accessflow.apigov.api.ApiConnectorView;
import com.bablsoft.accessflow.apigov.api.ApiAuthMethod;
import com.bablsoft.accessflow.apigov.api.ApiExecutionException;
import com.bablsoft.accessflow.apigov.api.CreateApiConnectorCommand;
import com.bablsoft.accessflow.apigov.api.DuplicateApiConnectorNameException;
import com.bablsoft.accessflow.apigov.api.GrantApiConnectorGroupPermissionCommand;
import com.bablsoft.accessflow.apigov.api.GrantApiConnectorPermissionCommand;
import com.bablsoft.accessflow.apigov.api.Oauth2ClientAuth;
import com.bablsoft.accessflow.apigov.api.Oauth2GrantType;
import com.bablsoft.accessflow.apigov.api.UpdateApiConnectorCommand;
import com.bablsoft.accessflow.apigov.api.UpdateApiConnectorGroupPermissionCommand;
import com.bablsoft.accessflow.apigov.api.UpdateApiConnectorPermissionCommand;
import com.bablsoft.accessflow.apigov.internal.client.ApiConnectorProber;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorGroupPermissionEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorUserPermissionEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorGroupPermissionRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorUserPermissionRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiSchemaRepository;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.ReviewPlanLookupService;
import com.bablsoft.accessflow.core.api.ReviewPlanNotFoundException;
import com.bablsoft.accessflow.core.api.UserGroupService;
import com.bablsoft.accessflow.core.api.UserGroupView;
import com.bablsoft.accessflow.core.api.UserNotFoundException;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserView;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DefaultApiConnectorAdminService implements ApiConnectorAdminService {

    private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() {
    };

    private final ApiConnectorRepository connectorRepository;
    private final ApiSchemaRepository schemaRepository;
    private final ApiConnectorUserPermissionRepository permissionRepository;
    private final ApiConnectorGroupPermissionRepository groupPermissionRepository;
    private final EffectiveApiConnectorPermissionResolver permissionResolver;
    private final UserGroupService userGroupService;
    private final CredentialEncryptionService encryptionService;
    private final UserQueryService userQueryService;
    private final ReviewPlanLookupService reviewPlanLookupService;
    private final ApiConnectorProber prober;
    private final ConnectorOAuth2TokenService oauth2TokenService;
    private final MessageSource messageSource;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ApiConnectorView> listForAdmin(UUID organizationId, PageRequest pageRequest) {
        var page = connectorRepository.findByOrganizationId(organizationId, toPageable(pageRequest));
        return toPageResponse(page.map(this::toView));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ApiConnectorView> listForUser(UUID organizationId, UUID userId,
                                                      PageRequest pageRequest) {
        // Union of the user's direct and group grants (AF-530).
        var granted = permissionResolver.connectorIdsFor(userId);
        var views = granted.stream()
                .map(id -> connectorRepository.findByIdAndOrganizationId(id, organizationId))
                .flatMap(Optional::stream)
                .filter(ApiConnectorEntity::isActive)
                .map(this::toView)
                .toList();
        return new PageResponse<>(views, 0, Math.max(views.size(), 1), views.size(), 1);
    }

    @Override
    @Transactional(readOnly = true)
    public ApiConnectorView getForAdmin(UUID id, UUID organizationId) {
        return toView(require(id, organizationId));
    }

    @Override
    @Transactional(readOnly = true)
    public ApiConnectorView getForUser(UUID id, UUID organizationId, UUID userId) {
        var connector = require(id, organizationId);
        // Effective permission = union of the user's direct and group grants (AF-530).
        var permission = permissionResolver.resolve(id, userId)
                .orElseThrow(() -> new ApiConnectorNotFoundException(id));
        if (!permission.canRead() && !permission.canWrite()) {
            throw new ApiConnectorNotFoundException(id);
        }
        return toView(connector);
    }

    @Override
    @Transactional
    public ApiConnectorView create(CreateApiConnectorCommand command) {
        if (connectorRepository.existsByOrganizationIdAndName(command.organizationId(), command.name())) {
            throw new DuplicateApiConnectorNameException(command.name());
        }
        var entity = new ApiConnectorEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(command.organizationId());
        entity.setName(command.name());
        entity.setProtocol(command.protocol());
        entity.setBaseUrl(command.baseUrl());
        entity.setDefaultHeaders(writeJson(command.defaultHeaders()));
        if (command.traceHeaderMapping() != null) {
            entity.setTraceHeaderMapping(writeJson(command.traceHeaderMapping()));
        }
        entity.setTimeoutMs(command.timeoutMs() != null ? command.timeoutMs() : 30000);
        entity.setTlsVerify(command.tlsVerify() == null || command.tlsVerify());
        entity.setAuthMethod(command.authMethod() != null ? command.authMethod() : ApiAuthMethod.NONE);
        entity.setAuthCredentialsEncrypted(encryptCredentials(command.authMethod(), command.credentials()));
        entity.setOauth2TokenUri(command.oauth2TokenUri());
        entity.setOauth2ClientId(command.oauth2ClientId());
        entity.setOauth2ClientSecretEncrypted(encryptSecret(command.oauth2ClientSecret()));
        entity.setOauth2Scopes(command.oauth2Scopes());
        entity.setOauth2Audience(command.oauth2Audience());
        entity.setOauth2RefreshTokenEncrypted(encryptSecret(command.oauth2RefreshToken()));
        entity.setOauth2Username(command.oauth2Username());
        entity.setOauth2PasswordEncrypted(encryptSecret(command.oauth2Password()));
        entity.setOauth2GrantType(command.oauth2GrantType() != null
                ? command.oauth2GrantType() : Oauth2GrantType.CLIENT_CREDENTIALS);
        entity.setOauth2ClientAuth(command.oauth2ClientAuth() != null
                ? command.oauth2ClientAuth() : Oauth2ClientAuth.CLIENT_SECRET_BASIC);
        requireReviewPlanInOrganization(command.reviewPlanId(), command.organizationId());
        entity.setReviewPlanId(command.reviewPlanId());
        entity.setAiAnalysisEnabled(command.aiAnalysisEnabled() == null || command.aiAnalysisEnabled());
        entity.setAiConfigId(command.aiConfigId());
        entity.setTextToApiEnabled(Boolean.TRUE.equals(command.textToApiEnabled()));
        entity.setRequireReviewReads(Boolean.TRUE.equals(command.requireReviewReads()));
        entity.setRequireReviewWrites(command.requireReviewWrites() == null || command.requireReviewWrites());
        entity.setMaxResponseBytes(command.maxResponseBytes() != null ? command.maxResponseBytes() : 10_485_760L);
        entity.setActive(true);
        return toView(connectorRepository.save(entity));
    }

    @Override
    @Transactional
    public ApiConnectorView update(UUID id, UUID organizationId, UpdateApiConnectorCommand command) {
        var entity = require(id, organizationId);
        if (command.name() != null && !command.name().equals(entity.getName())) {
            if (connectorRepository.existsByOrganizationIdAndName(organizationId, command.name())) {
                throw new DuplicateApiConnectorNameException(command.name());
            }
            entity.setName(command.name());
        }
        if (command.baseUrl() != null) {
            entity.setBaseUrl(command.baseUrl());
        }
        if (command.defaultHeaders() != null) {
            entity.setDefaultHeaders(writeJson(command.defaultHeaders()));
        }
        if (command.traceHeaderMapping() != null) {
            entity.setTraceHeaderMapping(writeJson(command.traceHeaderMapping()));
        }
        if (command.timeoutMs() != null) {
            entity.setTimeoutMs(command.timeoutMs());
        }
        if (command.tlsVerify() != null) {
            entity.setTlsVerify(command.tlsVerify());
        }
        if (command.authMethod() != null) {
            entity.setAuthMethod(command.authMethod());
        }
        if (command.credentials() != null) {
            entity.setAuthCredentialsEncrypted(encryptCredentials(entity.getAuthMethod(), command.credentials()));
        }
        if (command.oauth2TokenUri() != null) {
            entity.setOauth2TokenUri(command.oauth2TokenUri());
        }
        if (command.oauth2ClientId() != null) {
            entity.setOauth2ClientId(command.oauth2ClientId());
        }
        if (command.oauth2ClientSecret() != null) {
            entity.setOauth2ClientSecretEncrypted(encryptSecret(command.oauth2ClientSecret()));
        }
        if (command.oauth2Scopes() != null) {
            entity.setOauth2Scopes(command.oauth2Scopes());
        }
        if (command.oauth2Audience() != null) {
            entity.setOauth2Audience(command.oauth2Audience());
        }
        if (command.oauth2RefreshToken() != null) {
            entity.setOauth2RefreshTokenEncrypted(encryptSecret(command.oauth2RefreshToken()));
        }
        if (command.oauth2Username() != null) {
            entity.setOauth2Username(command.oauth2Username());
        }
        if (command.oauth2Password() != null) {
            entity.setOauth2PasswordEncrypted(encryptSecret(command.oauth2Password()));
        }
        if (command.oauth2GrantType() != null) {
            entity.setOauth2GrantType(command.oauth2GrantType());
        }
        if (command.oauth2ClientAuth() != null) {
            entity.setOauth2ClientAuth(command.oauth2ClientAuth());
        }
        if (Boolean.TRUE.equals(command.clearReviewPlan())) {
            entity.setReviewPlanId(null);
        } else if (command.reviewPlanId() != null) {
            requireReviewPlanInOrganization(command.reviewPlanId(), organizationId);
            entity.setReviewPlanId(command.reviewPlanId());
        }
        if (command.aiAnalysisEnabled() != null) {
            entity.setAiAnalysisEnabled(command.aiAnalysisEnabled());
        }
        if (command.aiConfigId() != null) {
            entity.setAiConfigId(command.aiConfigId());
        }
        if (command.textToApiEnabled() != null) {
            entity.setTextToApiEnabled(command.textToApiEnabled());
        }
        if (command.requireReviewReads() != null) {
            entity.setRequireReviewReads(command.requireReviewReads());
        }
        if (command.requireReviewWrites() != null) {
            entity.setRequireReviewWrites(command.requireReviewWrites());
        }
        if (command.maxResponseBytes() != null) {
            entity.setMaxResponseBytes(command.maxResponseBytes());
        }
        if (command.active() != null) {
            entity.setActive(command.active());
        }
        var saved = connectorRepository.save(entity);
        // Any cached token may now be stale (token endpoint / creds / grant changed) — drop it.
        oauth2TokenService.evict(saved.getId());
        return toView(saved);
    }

    @Override
    @Transactional
    public void delete(UUID id, UUID organizationId) {
        var entity = require(id, organizationId);
        connectorRepository.delete(entity);
        oauth2TokenService.evict(id);
    }

    @Override
    @Transactional(readOnly = true)
    public ApiConnectionTestResult test(UUID id, UUID organizationId) {
        var entity = require(id, organizationId);
        if (entity.getAuthMethod() == ApiAuthMethod.OAUTH2_CLIENT_CREDENTIALS) {
            try {
                oauth2TokenService.fetchFresh(entity);
            } catch (ApiExecutionException ex) {
                return new ApiConnectionTestResult(false, messageSource.getMessage(
                        "apigov.test.oauth2_failed", new Object[]{ex.getMessage()},
                        LocaleContextHolder.getLocale()));
            }
        }
        return prober.probe(entity.getProtocol(), entity.getBaseUrl(), entity.getTimeoutMs());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ApiConnectorPermissionView> listPermissions(UUID connectorId, UUID organizationId) {
        require(connectorId, organizationId);
        return permissionRepository.findByConnectorId(connectorId).stream()
                .map(this::toPermissionView)
                .toList();
    }

    @Override
    @Transactional
    public ApiConnectorPermissionView grantPermission(UUID connectorId, UUID organizationId,
                                                      UUID grantedByUserId,
                                                      GrantApiConnectorPermissionCommand command) {
        require(connectorId, organizationId);
        var target = userQueryService.findById(command.userId())
                .filter(u -> organizationId.equals(u.organizationId()))
                .orElseThrow(() -> new UserNotFoundException(command.userId()));
        var entity = permissionRepository.findByConnectorIdAndUserId(connectorId, command.userId())
                .orElseGet(() -> {
                    var fresh = new ApiConnectorUserPermissionEntity();
                    fresh.setId(UUID.randomUUID());
                    fresh.setConnectorId(connectorId);
                    fresh.setUserId(command.userId());
                    fresh.setCreatedBy(grantedByUserId);
                    return fresh;
                });
        entity.setCanRead(command.canRead());
        entity.setCanWrite(command.canWrite());
        entity.setCanBreakGlass(command.canBreakGlass());
        entity.setExpiresAt(command.expiresAt());
        entity.setAllowedOperations(toArray(command.allowedOperations()));
        entity.setRestrictedResponseFields(toArray(command.restrictedResponseFields()));
        return toPermissionView(permissionRepository.save(entity), target);
    }

    @Override
    @Transactional
    public ApiConnectorPermissionView updatePermission(UUID connectorId, UUID organizationId,
                                                       UUID permissionId,
                                                       UpdateApiConnectorPermissionCommand command) {
        require(connectorId, organizationId);
        var entity = permissionRepository.findById(permissionId)
                .filter(p -> p.getConnectorId().equals(connectorId))
                .orElseThrow(() -> new ApiConnectorPermissionNotFoundException(permissionId));
        entity.setCanRead(command.canRead());
        entity.setCanWrite(command.canWrite());
        entity.setCanBreakGlass(command.canBreakGlass());
        entity.setExpiresAt(command.expiresAt());
        entity.setAllowedOperations(toArray(command.allowedOperations()));
        entity.setRestrictedResponseFields(toArray(command.restrictedResponseFields()));
        return toPermissionView(permissionRepository.save(entity));
    }

    @Override
    @Transactional
    public void revokePermission(UUID connectorId, UUID organizationId, UUID permissionId) {
        require(connectorId, organizationId);
        var entity = permissionRepository.findById(permissionId)
                .filter(p -> p.getConnectorId().equals(connectorId))
                .orElseThrow(() -> new ApiConnectorPermissionNotFoundException(permissionId));
        permissionRepository.delete(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ApiConnectorGroupPermissionView> listGroupPermissions(UUID connectorId,
                                                                      UUID organizationId) {
        require(connectorId, organizationId);
        return groupPermissionRepository.findByConnectorId(connectorId).stream()
                .map(this::toGroupPermissionView)
                .toList();
    }

    @Override
    @Transactional
    public ApiConnectorGroupPermissionView grantGroupPermission(
            UUID connectorId, UUID organizationId, UUID grantedByUserId,
            GrantApiConnectorGroupPermissionCommand command) {
        require(connectorId, organizationId);
        // Validates the group exists in this organization (throws UserGroupNotFoundException → 404).
        var group = userGroupService.getGroup(command.groupId(), organizationId);
        var entity = groupPermissionRepository.findByConnectorIdAndGroupId(connectorId, command.groupId())
                .orElseGet(() -> {
                    var fresh = new ApiConnectorGroupPermissionEntity();
                    fresh.setId(UUID.randomUUID());
                    fresh.setOrganizationId(organizationId);
                    fresh.setConnectorId(connectorId);
                    fresh.setGroupId(command.groupId());
                    fresh.setCreatedBy(grantedByUserId);
                    return fresh;
                });
        entity.setCanRead(command.canRead());
        entity.setCanWrite(command.canWrite());
        entity.setCanBreakGlass(command.canBreakGlass());
        entity.setExpiresAt(command.expiresAt());
        entity.setAllowedOperations(toArray(command.allowedOperations()));
        entity.setRestrictedResponseFields(toArray(command.restrictedResponseFields()));
        return toGroupPermissionView(groupPermissionRepository.save(entity), group);
    }

    @Override
    @Transactional
    public ApiConnectorGroupPermissionView updateGroupPermission(
            UUID connectorId, UUID organizationId, UUID permissionId,
            UpdateApiConnectorGroupPermissionCommand command) {
        require(connectorId, organizationId);
        var entity = groupPermissionRepository.findById(permissionId)
                .filter(p -> p.getConnectorId().equals(connectorId))
                .orElseThrow(() -> new ApiConnectorPermissionNotFoundException(permissionId));
        entity.setCanRead(command.canRead());
        entity.setCanWrite(command.canWrite());
        entity.setCanBreakGlass(command.canBreakGlass());
        entity.setExpiresAt(command.expiresAt());
        entity.setAllowedOperations(toArray(command.allowedOperations()));
        entity.setRestrictedResponseFields(toArray(command.restrictedResponseFields()));
        return toGroupPermissionView(groupPermissionRepository.save(entity));
    }

    @Override
    @Transactional
    public void revokeGroupPermission(UUID connectorId, UUID organizationId, UUID permissionId) {
        require(connectorId, organizationId);
        var entity = groupPermissionRepository.findById(permissionId)
                .filter(p -> p.getConnectorId().equals(connectorId))
                .orElseThrow(() -> new ApiConnectorPermissionNotFoundException(permissionId));
        groupPermissionRepository.delete(entity);
    }

    private ApiConnectorEntity require(UUID id, UUID organizationId) {
        return connectorRepository.findByIdAndOrganizationId(id, organizationId)
                .orElseThrow(() -> new ApiConnectorNotFoundException(id));
    }

    /** A cross-org plan id must read as "not found" — never reveal that the id exists elsewhere. */
    private void requireReviewPlanInOrganization(UUID reviewPlanId, UUID organizationId) {
        if (reviewPlanId == null) {
            return;
        }
        reviewPlanLookupService.findById(reviewPlanId)
                .filter(plan -> organizationId.equals(plan.organizationId()))
                .orElseThrow(() -> new ReviewPlanNotFoundException(reviewPlanId));
    }

    private ApiConnectorView toView(ApiConnectorEntity e) {
        boolean hasCredentials = e.getAuthMethod() != ApiAuthMethod.NONE
                && e.getAuthCredentialsEncrypted() != null && !e.getAuthCredentialsEncrypted().isBlank();
        boolean schemaPresent = schemaRepository.findFirstByConnectorIdOrderByCreatedAtDesc(e.getId()).isPresent();
        return new ApiConnectorView(
                e.getId(), e.getOrganizationId(), e.getName(), e.getProtocol(), e.getBaseUrl(),
                readJson(e.getDefaultHeaders()), readJson(e.getTraceHeaderMapping()),
                e.getTimeoutMs(), e.isTlsVerify(), e.getAuthMethod(),
                hasCredentials, e.getOauth2TokenUri(), e.getOauth2ClientId(), e.getOauth2Scopes(),
                e.getOauth2Audience(), e.getOauth2Username(), e.getOauth2GrantType(), e.getOauth2ClientAuth(),
                configured(e.getOauth2ClientSecretEncrypted()), configured(e.getOauth2RefreshTokenEncrypted()),
                configured(e.getOauth2PasswordEncrypted()), e.getReviewPlanId(), e.isAiAnalysisEnabled(),
                e.getAiConfigId(), e.isTextToApiEnabled(), e.isRequireReviewReads(), e.isRequireReviewWrites(),
                e.getMaxResponseBytes(), e.isActive(), schemaPresent, e.getCreatedAt());
    }

    private static boolean configured(String encrypted) {
        return encrypted != null && !encrypted.isBlank();
    }

    private String encryptSecret(String raw) {
        return raw == null || raw.isBlank() ? null : encryptionService.encrypt(raw);
    }

    private ApiConnectorPermissionView toPermissionView(ApiConnectorUserPermissionEntity e) {
        var user = userQueryService.findById(e.getUserId()).orElse(null);
        return toPermissionView(e, user);
    }

    private ApiConnectorPermissionView toPermissionView(ApiConnectorUserPermissionEntity e, UserView user) {
        return new ApiConnectorPermissionView(
                e.getId(), e.getConnectorId(), e.getUserId(),
                user != null ? user.email() : null,
                user != null ? user.displayName() : null,
                e.isCanRead(), e.isCanWrite(), e.isCanBreakGlass(), e.getExpiresAt(),
                e.getAllowedOperations() != null ? List.of(e.getAllowedOperations()) : List.of(),
                e.getRestrictedResponseFields() != null ? List.of(e.getRestrictedResponseFields()) : List.of(),
                e.getCreatedAt());
    }

    private ApiConnectorGroupPermissionView toGroupPermissionView(ApiConnectorGroupPermissionEntity e) {
        var group = userGroupService.getGroup(e.getGroupId(), e.getOrganizationId());
        return toGroupPermissionView(e, group);
    }

    private ApiConnectorGroupPermissionView toGroupPermissionView(ApiConnectorGroupPermissionEntity e,
                                                                  UserGroupView group) {
        return new ApiConnectorGroupPermissionView(
                e.getId(), e.getConnectorId(), e.getGroupId(),
                group != null ? group.name() : null,
                group != null ? group.memberCount() : 0,
                e.isCanRead(), e.isCanWrite(), e.isCanBreakGlass(), e.getExpiresAt(),
                e.getAllowedOperations() != null ? List.of(e.getAllowedOperations()) : List.of(),
                e.getRestrictedResponseFields() != null ? List.of(e.getRestrictedResponseFields()) : List.of(),
                e.getCreatedAt());
    }

    private String encryptCredentials(ApiAuthMethod method, Map<String, String> credentials) {
        if (method == null || method == ApiAuthMethod.NONE || credentials == null || credentials.isEmpty()) {
            return null;
        }
        return encryptionService.encrypt(writeJson(credentials));
    }

    private String writeJson(Map<String, String> map) {
        return objectMapper.writeValueAsString(map == null ? Map.of() : map);
    }

    private Map<String, String> readJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(json, MAP_TYPE);
    }

    private static String[] toArray(List<String> list) {
        return list == null || list.isEmpty() ? null : list.toArray(String[]::new);
    }

    private static Pageable toPageable(PageRequest pageRequest) {
        return org.springframework.data.domain.PageRequest.of(pageRequest.page(), pageRequest.size());
    }

    private static <T> PageResponse<T> toPageResponse(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(),
                page.getSize() <= 0 ? 1 : page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
