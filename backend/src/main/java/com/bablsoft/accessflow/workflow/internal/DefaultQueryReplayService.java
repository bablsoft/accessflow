package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.DatabaseSchemaView;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceView;
import com.bablsoft.accessflow.core.api.SubmissionReason;
import com.bablsoft.accessflow.proxy.api.DatasourceUnavailableException;
import com.bablsoft.accessflow.workflow.api.QueryReplayService;
import com.bablsoft.accessflow.workflow.api.QuerySnapshotNotFoundException;
import com.bablsoft.accessflow.workflow.api.QuerySnapshotService;
import com.bablsoft.accessflow.workflow.api.QuerySnapshotView;
import com.bablsoft.accessflow.workflow.api.QuerySubmissionService;
import com.bablsoft.accessflow.workflow.api.QuerySubmissionService.SubmissionInput;
import com.bablsoft.accessflow.workflow.api.ReplaySchemaIncompatibleException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
class DefaultQueryReplayService implements QueryReplayService {

    private final QuerySnapshotService querySnapshotService;
    private final QuerySubmissionService querySubmissionService;
    private final DatasourceAdminService datasourceAdminService;
    private final SchemaHasher schemaHasher;
    private final MessageSource messageSource;

    @Override
    public ReplayResult replay(ReplayCommand command) {
        var snapshot = querySnapshotService
                .find(command.originalQueryId(), command.callerOrganizationId())
                .orElseThrow(() -> new QuerySnapshotNotFoundException(command.originalQueryId()));

        var target = resolveTarget(command);
        if (!target.active()) {
            throw new DatasourceUnavailableException(msg("error.datasource_unavailable_inactive"));
        }
        if (target.dbType() != snapshot.dbType()) {
            throw ReplaySchemaIncompatibleException.dbTypeMismatch(
                    target.id(), snapshot.dbType(), target.dbType());
        }

        var targetSchema = introspectTarget(target);
        var missing = ReplaySchemaMatcher.missingTables(snapshot.referencedTables(), targetSchema);
        if (!missing.isEmpty()) {
            throw ReplaySchemaIncompatibleException.missingTables(target.id(), missing);
        }
        var targetSchemaHash = schemaHasher.hash(targetSchema);

        var result = querySubmissionService.submit(new SubmissionInput(
                target.id(),
                snapshot.sqlText(),
                justification(command.originalQueryId()),
                command.callerUserId(),
                command.callerOrganizationId(),
                command.isAdmin(),
                null,
                SubmissionReason.USER_SUBMITTED,
                command.ipAddress(),
                command.userAgent(),
                false));

        return new ReplayResult(result.id(), result.status(), snapshot.schemaHash(),
                targetSchemaHash, snapshot.datasourceId(), target.id());
    }

    private DatasourceView resolveTarget(ReplayCommand command) {
        return command.isAdmin()
                ? datasourceAdminService.getForAdmin(command.targetDatasourceId(),
                        command.callerOrganizationId())
                : datasourceAdminService.getForUser(command.targetDatasourceId(),
                        command.callerOrganizationId(), command.callerUserId());
    }

    private DatabaseSchemaView introspectTarget(DatasourceView target) {
        try {
            return datasourceAdminService.introspectSchemaForSystem(
                    target.id(), target.organizationId());
        } catch (RuntimeException ex) {
            log.warn("Replay rejected: cannot introspect target datasource {} schema",
                    target.id(), ex);
            throw ReplaySchemaIncompatibleException.targetSchemaUnavailable(target.id());
        }
    }

    private String justification(java.util.UUID originalQueryId) {
        return msg("replay.justification", new Object[]{originalQueryId});
    }

    private String msg(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    private String msg(String key, Object[] args) {
        return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
    }
}
