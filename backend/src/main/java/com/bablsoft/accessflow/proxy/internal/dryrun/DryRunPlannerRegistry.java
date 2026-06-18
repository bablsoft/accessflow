package com.bablsoft.accessflow.proxy.internal.dryrun;

import com.bablsoft.accessflow.core.api.DbType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the {@link DryRunPlanner} for a relational {@link DbType} (issue AF-445). Built from all
 * {@code DryRunPlanner} beans; a {@link DbType} with no registered planner (e.g. {@code CUSTOM})
 * resolves to {@code null}, signalling the caller to degrade gracefully.
 */
@Component
public class DryRunPlannerRegistry {

    private final Map<DbType, DryRunPlanner> byType = new EnumMap<>(DbType.class);

    public DryRunPlannerRegistry(List<DryRunPlanner> planners) {
        for (DryRunPlanner planner : planners) {
            for (DbType type : planner.supportedTypes()) {
                byType.put(type, planner);
            }
        }
    }

    /** The planner for the given type, or {@code null} when the dialect has no plan support. */
    public DryRunPlanner forDbType(DbType dbType) {
        return byType.get(dbType);
    }
}
