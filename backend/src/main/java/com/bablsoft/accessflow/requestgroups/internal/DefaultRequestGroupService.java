package com.bablsoft.accessflow.requestgroups.internal;

import com.bablsoft.accessflow.apigov.api.ApiBodyType;
import com.bablsoft.accessflow.apigov.api.ApiConnectorView;
import com.bablsoft.accessflow.apigov.api.ApiConnectorPermissionLookupService;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.core.api.AiAnalysisLookupService;
import com.bablsoft.accessflow.core.api.DatasourceLookupService;
import com.bablsoft.accessflow.core.api.DatasourceRef;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionLookupService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.QueryDetailView;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.proxy.api.QueryParser;
import com.bablsoft.accessflow.requestgroups.api.CreateRequestGroupCommand;
import com.bablsoft.accessflow.requestgroups.api.IllegalRequestGroupStateException;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupItemInput;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupListFilter;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupNotFoundException;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupPermissionException;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupService;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupStatus;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupSubmissionResult;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupTargetKind;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupValidationException;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupView;
import com.bablsoft.accessflow.requestgroups.api.SubmitRequestGroupCommand;
import com.bablsoft.accessflow.requestgroups.api.UpdateRequestGroupCommand;
import com.bablsoft.accessflow.requestgroups.events.RequestGroupSubmittedEvent;
import com.bablsoft.accessflow.requestgroups.internal.persistence.entity.RequestGroupEntity;
import com.bablsoft.accessflow.requestgroups.internal.persistence.entity.RequestGroupItemEntity;
import com.bablsoft.accessflow.requestgroups.internal.persistence.repo.RequestGroupItemRepository;
import com.bablsoft.accessflow.requestgroups.internal.persistence.repo.RequestGroupRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DefaultRequestGroupService implements RequestGroupService {

    private static final Logger log = LoggerFactory.getLogger(DefaultRequestGroupService.class);
    private static final Set<String> READ_VERBS = Set.of("GET", "HEAD", "OPTIONS");

    private final RequestGroupRepository groupRepository;
    private final RequestGroupItemRepository itemRepository;
    private final RequestGroupStateService stateService;
    private final GroupExecutionService executionService;
    private final QueryParser queryParser;
    private final DatasourceLookupService datasourceLookupService;
    private final AiAnalysisLookupService aiAnalysisLookupService;
    private final DatasourceUserPermissionLookupService datasourcePermissionLookupService;
    private final ApiConnectorPermissionLookupService apiConnectorPermissionLookupService;
    private final com.bablsoft.accessflow.apigov.api.ApiConnectorAdminService apiConnectorAdminService;
    private final UserQueryService userQueryService;
    private final AuditLogService auditLogService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public RequestGroupView createDraft(CreateRequestGroupCommand command) {
        requireItems(command.items());
        var group = new RequestGroupEntity();
        group.setId(UUID.randomUUID());
        group.setOrganizationId(command.organizationId());
        group.setSubmittedBy(command.submitterUserId());
        group.setName(command.name());
        group.setDescription(command.description());
        group.setContinueOnError(command.continueOnError());
        group.setStatus(RequestGroupStatus.DRAFT);
        groupRepository.save(group);
        persistItems(group, command.items(), command.submitterUserId(), command.admin(), false);
        return assembleView(group);
    }

    @Override
    @Transactional
    public RequestGroupView updateDraft(UpdateRequestGroupCommand command) {
        var group = requireOwned(command.requestGroupId(), command.organizationId(),
                command.callerUserId(), command.admin());
        if (group.getStatus() != RequestGroupStatus.DRAFT) {
            throw new IllegalRequestGroupStateException(group.getStatus(),
                    "Only DRAFT groups can be edited");
        }
        requireItems(command.items());
        group.setName(command.name());
        group.setDescription(command.description());
        group.setContinueOnError(command.continueOnError());
        groupRepository.save(group);
        itemRepository.deleteByGroupId(group.getId());
        persistItems(group, command.items(), group.getSubmittedBy(), command.admin(), false);
        return assembleView(group);
    }

    @Override
    @Transactional
    public RequestGroupSubmissionResult submit(SubmitRequestGroupCommand command) {
        var group = requireOwned(command.requestGroupId(), command.organizationId(),
                command.callerUserId(), command.admin());
        if (group.getStatus() != RequestGroupStatus.DRAFT) {
            throw new IllegalRequestGroupStateException(group.getStatus(),
                    "Only DRAFT groups can be submitted");
        }
        var items = itemRepository.findByGroupIdOrderBySequenceOrderAsc(group.getId());
        requireItems(items.stream().map(i -> (Object) i).toList());
        // Re-validate each member's permission at submit (break-glass requires can_break_glass on
        // every target — for everyone, including admins).
        for (RequestGroupItemEntity item : items) {
            validatePermission(item, group.getSubmittedBy(), command.admin(), command.breakGlass());
        }
        group.setScheduledFor(command.scheduledFor());
        group.setSubmittedIp(command.submittedIp());
        group.setSubmittedUserAgent(command.submittedUserAgent());

        if (command.breakGlass()) {
            group.setSubmissionReason(com.bablsoft.accessflow.core.api.SubmissionReason.EMERGENCY_ACCESS);
            stateService.apply(group, RequestGroupStatus.APPROVED);
            audit(AuditAction.REQUEST_GROUP_SUBMITTED, group, command.callerUserId(),
                    Map.of("break_glass", true, "member_count", items.size()));
            executionService.execute(group.getId(), command.callerUserId(), "break_glass");
            var refreshed = groupRepository.findById(group.getId()).orElse(group);
            return new RequestGroupSubmissionResult(refreshed.getId(), refreshed.getStatus());
        }

        stateService.apply(group, RequestGroupStatus.PENDING_AI);
        audit(AuditAction.REQUEST_GROUP_SUBMITTED, group, command.callerUserId(),
                Map.of("member_count", items.size()));
        eventPublisher.publishEvent(new RequestGroupSubmittedEvent(group.getId()));
        return new RequestGroupSubmissionResult(group.getId(), group.getStatus());
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<RequestGroupView> list(RequestGroupListFilter filter, PageRequest pageRequest) {
        var pageable = Pageable.ofSize(Math.max(1, pageRequest.size())).withPage(pageRequest.page());
        var page = groupRepository.findAll(RequestGroupSpecifications.forFilter(filter), pageable);
        var content = page.getContent().stream().map(this::assembleView).toList();
        var rebased = new PageImpl<>(content, pageable, page.getTotalElements());
        return new PageResponse<>(content, rebased.getNumber(),
                rebased.getSize() <= 0 ? 1 : rebased.getSize(),
                rebased.getTotalElements(), rebased.getTotalPages());
    }

    @Override
    @Transactional(readOnly = true)
    public RequestGroupView get(UUID id, UUID organizationId, UUID userId, boolean admin) {
        return assembleView(requireOwned(id, organizationId, userId, admin), true);
    }

    @Override
    @Transactional
    public void deleteDraft(UUID id, UUID organizationId, UUID userId) {
        var group = groupRepository.findByIdAndOrganizationId(id, organizationId)
                .orElseThrow(() -> new RequestGroupNotFoundException(id));
        if (!group.getSubmittedBy().equals(userId)) {
            throw new RequestGroupPermissionException("Only the submitter can delete a group");
        }
        if (group.getStatus() != RequestGroupStatus.DRAFT) {
            throw new IllegalRequestGroupStateException(group.getStatus(),
                    "Only DRAFT groups can be deleted");
        }
        itemRepository.deleteByGroupId(group.getId());
        groupRepository.delete(group);
    }

    @Override
    @Transactional
    public void cancel(UUID id, UUID organizationId, UUID userId) {
        var group = groupRepository.findByIdAndOrganizationId(id, organizationId)
                .orElseThrow(() -> new RequestGroupNotFoundException(id));
        if (!group.getSubmittedBy().equals(userId)) {
            throw new RequestGroupPermissionException("Only the submitter can cancel a group");
        }
        var cancellable = group.getStatus() == RequestGroupStatus.DRAFT
                || group.getStatus() == RequestGroupStatus.PENDING_AI
                || group.getStatus() == RequestGroupStatus.PENDING_REVIEW
                || (group.getStatus() == RequestGroupStatus.APPROVED && group.getScheduledFor() != null
                        && group.getScheduledFor().isAfter(Instant.now()));
        if (!cancellable) {
            throw new IllegalRequestGroupStateException(group.getStatus(),
                    "Group cannot be cancelled in its current state");
        }
        stateService.apply(group, RequestGroupStatus.CANCELLED);
        audit(AuditAction.REQUEST_GROUP_CANCELLED, group, userId, Map.of());
    }

    @Override
    public RequestGroupView execute(UUID id, UUID organizationId, UUID userId, boolean admin) {
        var group = groupRepository.findByIdAndOrganizationId(id, organizationId)
                .orElseThrow(() -> new RequestGroupNotFoundException(id));
        if (!admin && !group.getSubmittedBy().equals(userId)) {
            throw new RequestGroupPermissionException("Only the submitter or an admin can run a group");
        }
        executionService.execute(id, userId, "manual");
        return assembleView(groupRepository.findById(id).orElseThrow(
                () -> new RequestGroupNotFoundException(id)), true);
    }

    // ----- helpers -----

    private void persistItems(RequestGroupEntity group, List<RequestGroupItemInput> inputs,
                              UUID submitterId, boolean admin, boolean breakGlass) {
        int order = 0;
        for (RequestGroupItemInput input : inputs) {
            var item = buildItem(group, input, order++);
            validatePermission(item, submitterId, admin, breakGlass);
            itemRepository.save(item);
        }
    }

    private RequestGroupItemEntity buildItem(RequestGroupEntity group, RequestGroupItemInput input,
                                             int order) {
        var item = new RequestGroupItemEntity();
        item.setId(UUID.randomUUID());
        item.setGroupId(group.getId());
        item.setSequenceOrder(order);
        item.setTargetKind(input.targetKind());
        if (input.targetKind() == RequestGroupTargetKind.QUERY) {
            if (input.datasourceId() == null || input.sqlText() == null || input.sqlText().isBlank()) {
                throw new RequestGroupValidationException("Query member requires a datasource and SQL");
            }
            item.setDatasourceId(input.datasourceId());
            item.setSqlText(input.sqlText());
            item.setTransactional(input.transactional());
            item.setQueryType(classifyQuery(input.datasourceId(), input.sqlText()));
        } else {
            if (input.apiConnectorId() == null || input.verb() == null || input.verb().isBlank()
                    || input.requestPath() == null || input.requestPath().isBlank()) {
                throw new RequestGroupValidationException(
                        "API member requires a connector, verb, and request path");
            }
            item.setApiConnectorId(input.apiConnectorId());
            item.setOperationId(input.operationId());
            item.setVerb(input.verb().toUpperCase(java.util.Locale.ROOT));
            item.setRequestPath(input.requestPath());
            item.setRequestHeaders(writeJson(input.requestHeaders(), "{}"));
            item.setQueryParams(writeJson(input.queryParams(), "{}"));
            item.setBodyType(input.bodyType() == null ? ApiBodyType.RAW
                    : ApiBodyType.valueOf(input.bodyType().name()));
            item.setRequestContentType(input.requestContentType());
            item.setRequestBody(input.requestBody());
            item.setFormFields(writeJson(input.formFields(), "[]"));
            item.setBinaryFilename(input.binaryFilename());
        }
        return item;
    }

    private QueryType classifyQuery(UUID datasourceId, String sql) {
        var dbType = datasourceLookupService.findById(datasourceId)
                .map(d -> d.dbType()).orElse(DbType.POSTGRESQL);
        return queryParser.parse(sql, dbType).type();
    }

    private void validatePermission(RequestGroupItemEntity item, UUID submitterId, boolean admin,
                                    boolean breakGlass) {
        if (item.getTargetKind() == RequestGroupTargetKind.QUERY) {
            var perm = datasourcePermissionLookupService.findFor(submitterId, item.getDatasourceId());
            if (breakGlass) {
                if (perm.isEmpty() || !perm.get().canBreakGlass()) {
                    throw new RequestGroupPermissionException(
                            "Break-glass requires can_break_glass on every member target");
                }
                return;
            }
            if (admin) {
                return;
            }
            var write = item.getQueryType() != QueryType.SELECT;
            var ddl = item.getQueryType() == QueryType.DDL;
            var ok = perm.map(p -> ddl ? p.canDdl() : write ? p.canWrite() : p.canRead()).orElse(false);
            if (!ok) {
                throw new RequestGroupPermissionException(
                        "You are not permitted to run this query on the selected datasource");
            }
        } else {
            var perm = apiConnectorPermissionLookupService.findFor(item.getApiConnectorId(), submitterId);
            if (breakGlass) {
                if (perm.isEmpty() || !perm.get().canBreakGlass()) {
                    throw new RequestGroupPermissionException(
                            "Break-glass requires can_break_glass on every member target");
                }
                return;
            }
            if (admin) {
                return;
            }
            var write = !READ_VERBS.contains(item.getVerb());
            var ok = perm.map(p -> write ? p.canWrite() : p.canRead()).orElse(false);
            if (!ok) {
                throw new RequestGroupPermissionException(
                        "You are not permitted to call this connector");
            }
        }
    }

    private String writeJson(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private RequestGroupEntity requireOwned(UUID id, UUID organizationId, UUID userId, boolean admin) {
        var group = groupRepository.findByIdAndOrganizationId(id, organizationId)
                .orElseThrow(() -> new RequestGroupNotFoundException(id));
        if (!admin && !group.getSubmittedBy().equals(userId)) {
            throw new RequestGroupPermissionException("You do not have access to this group");
        }
        return group;
    }

    private void requireItems(List<?> items) {
        if (items == null || items.isEmpty()) {
            throw new RequestGroupValidationException("A request group must have at least one member");
        }
    }

    private RequestGroupView assembleView(RequestGroupEntity group) {
        return assembleView(group, false);
    }

    private RequestGroupView assembleView(RequestGroupEntity group, boolean includeAnalyses) {
        var items = itemRepository.findByGroupIdOrderBySequenceOrderAsc(group.getId());
        var submitter = userQueryService.findById(group.getSubmittedBy()).orElse(null);
        Map<UUID, DatasourceRef> datasources = new HashMap<>();
        Map<UUID, ApiConnectorView> connectors = new HashMap<>();
        Map<UUID, QueryDetailView.AiAnalysisDetail> analysesByItemId = new HashMap<>();
        for (RequestGroupItemEntity item : items) {
            if (item.getDatasourceId() != null && !datasources.containsKey(item.getDatasourceId())) {
                datasourceLookupService.findRef(item.getDatasourceId())
                        .ifPresent(ref -> datasources.put(item.getDatasourceId(), ref));
            }
            if (item.getApiConnectorId() != null && !connectors.containsKey(item.getApiConnectorId())) {
                try {
                    connectors.put(item.getApiConnectorId(),
                            apiConnectorAdminService.getForAdmin(item.getApiConnectorId(),
                                    group.getOrganizationId()));
                } catch (RuntimeException ex) {
                    log.debug("Connector {} no longer resolvable for group view", item.getApiConnectorId());
                }
            }
            if (includeAnalyses && item.getAiAnalysisId() != null) {
                aiAnalysisLookupService.findDetailById(item.getAiAnalysisId())
                        .ifPresent(detail -> analysesByItemId.put(item.getId(), detail));
            }
        }
        return RequestGroupMapper.toView(group, items, submitter, datasources, connectors,
                analysesByItemId, objectMapper, includeAnalyses);
    }

    private void audit(AuditAction action, RequestGroupEntity group, UUID actorId,
                       Map<String, Object> metadata) {
        try {
            auditLogService.record(new AuditEntry(action, AuditResourceType.REQUEST_GROUP, group.getId(),
                    group.getOrganizationId(), actorId, metadata, group.getSubmittedIp(),
                    group.getSubmittedUserAgent()));
        } catch (RuntimeException ex) {
            log.warn("Failed to record audit {} for group {}: {}", action, group.getId(), ex.getMessage());
        }
    }
}
