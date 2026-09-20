package com.bablsoft.accessflow.attestation.internal;

import com.bablsoft.accessflow.attestation.api.AttestationItemView;
import com.bablsoft.accessflow.core.api.PrincipalType;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountLookupService;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves each item's subject at read time (#875): whether it is a person or a service account,
 * and for a service account the human that owns it. Three bounded queries per page at most — one
 * user lookup for the subjects, the organization's service-account rows (only when a subject is
 * one), one user lookup for the owners — never one per row.
 */
@Component
@RequiredArgsConstructor
class AttestationSubjectResolver {

    private final UserQueryService userQueryService;
    private final ServiceAccountLookupService serviceAccountLookupService;

    List<AttestationItemView> enrich(UUID organizationId, List<AttestationItemView> items) {
        if (items.isEmpty()) {
            return items;
        }
        var subjectIds = items.stream().map(AttestationItemView::subjectUserId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, UserView> subjects = byId(userQueryService.findByIds(subjectIds));

        var serviceAccountIds = subjects.values().stream()
                .filter(u -> u.principalType() == PrincipalType.SERVICE_ACCOUNT)
                .map(UserView::id).collect(Collectors.toSet());
        Map<UUID, ServiceAccountView> accounts = serviceAccountIds.isEmpty() ? Map.of()
                : serviceAccountLookupService.listByOrganization(organizationId).stream()
                        .filter(a -> serviceAccountIds.contains(a.userId()))
                        .collect(Collectors.toMap(ServiceAccountView::userId, Function.identity()));

        var ownerIds = accounts.values().stream().map(ServiceAccountView::ownerUserId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, UserView> owners = ownerIds.isEmpty() ? Map.of() : byId(userQueryService.findByIds(ownerIds));

        return items.stream().map(item -> {
            var subject = subjects.get(item.subjectUserId());
            if (subject == null) {
                return item;
            }
            var account = accounts.get(subject.id());
            var owner = account == null || account.ownerUserId() == null ? null : owners.get(account.ownerUserId());
            return item.withSubject(subject.principalType(),
                    owner == null ? null : owner.email(),
                    owner == null ? null : owner.displayName());
        }).toList();
    }

    private static Map<UUID, UserView> byId(List<UserView> users) {
        var map = new HashMap<UUID, UserView>();
        users.forEach(u -> map.put(u.id(), u));
        return map;
    }
}
