package com.bablsoft.accessflow.deploygov.api;

import com.bablsoft.accessflow.core.api.RiskLevel;

import java.time.LocalTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The condition set of a deployment routing policy: a flat AND of the leaves that are present. An
 * absent or empty component is unconstrained, so {@link #NONE} matches every deployment.
 *
 * <p>Typed rather than a raw JSON blob because #691 ships admin CRUD for these policies: a typo'd
 * key silently meaning "unconstrained" would be a routine admin mistake, and a policy the admin
 * believes narrows to production would match everything.
 *
 * <p>{@code daysOfWeek} uses ISO numbering (1 = Monday … 7 = Sunday). The time window is
 * {@code [startTime, endTime)} evaluated as wall-clock in {@code timezone} (an IANA zone id;
 * {@code null} means UTC); an {@code endTime} before {@code startTime} spans midnight, with day
 * membership belonging to the day the window <em>starts</em>.
 */
public record DeploymentRoutingConditions(
        List<String> environments,
        List<String> providers,
        RiskLevel minRiskLevel,
        List<String> versionGlobs,
        Set<Integer> daysOfWeek,
        LocalTime startTime,
        LocalTime endTime,
        String timezone) {

    /** Matches every deployment — the value used for an absent or empty {@code conditions} blob. */
    public static final DeploymentRoutingConditions NONE =
            new DeploymentRoutingConditions(null, null, null, null, null, null, null, null);

    public DeploymentRoutingConditions {
        // Null and blank entries are dropped rather than rejected: an admin-authored JSON array may
        // legitimately carry them, and an empty leaf simply means "unconstrained".
        environments = cleaned(environments);
        providers = cleaned(providers);
        versionGlobs = cleaned(versionGlobs);
        daysOfWeek = daysOfWeek == null
                ? Set.of()
                : daysOfWeek.stream().filter(Objects::nonNull)
                        .collect(Collectors.toUnmodifiableSet());
    }

    private static List<String> cleaned(List<String> values) {
        return values == null
                ? List.of()
                : values.stream().filter(v -> v != null && !v.isBlank()).toList();
    }
}
