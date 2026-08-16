package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.CreateReviewDelegationCommand;
import com.bablsoft.accessflow.core.api.DelegationScopeKind;
import com.bablsoft.accessflow.core.api.IllegalReviewDelegationException;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.ReviewDelegateCandidate;
import com.bablsoft.accessflow.core.api.ReviewDelegationFilter;
import com.bablsoft.accessflow.core.api.ReviewDelegationNotFoundException;
import com.bablsoft.accessflow.core.api.ReviewDelegationScopeResolver;
import com.bablsoft.accessflow.core.api.ReviewDelegationService;
import com.bablsoft.accessflow.core.api.ReviewDelegationStatus;
import com.bablsoft.accessflow.core.api.ReviewDelegationView;
import com.bablsoft.accessflow.core.internal.persistence.entity.ReviewDelegationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.ReviewDelegationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Create / revoke / list for out-of-office reviewer delegations (#622).
 *
 * <p>Validation here is a convenience, not the security boundary: every rule that matters is
 * re-applied at read time by {@link DefaultReviewDelegationLookupService}, so a row that becomes
 * invalid after creation (a party deactivated, a scoped resource deleted) simply stops conferring
 * eligibility rather than lingering as authority nobody revoked.
 */
@Service
public class DefaultReviewDelegationService implements ReviewDelegationService {

    private final ReviewDelegationRepository delegationRepository;
    private final UserRepository userRepository;
    private final Map<DelegationScopeKind, ReviewDelegationScopeResolver> scopeResolvers;
    private final Clock clock;
    private final MessageSource messageSource;
    private final int maxOpenPerDelegator;

    DefaultReviewDelegationService(
            ReviewDelegationRepository delegationRepository,
            UserRepository userRepository,
            List<ReviewDelegationScopeResolver> scopeResolvers,
            Clock clock,
            MessageSource messageSource,
            @Value("${accessflow.review.delegation.max-open-per-delegator:10}") int maxOpenPerDelegator) {
        this.delegationRepository = delegationRepository;
        this.userRepository = userRepository;
        this.scopeResolvers = new EnumMap<>(DelegationScopeKind.class);
        scopeResolvers.forEach(resolver -> this.scopeResolvers.put(resolver.supportedKind(), resolver));
        this.clock = clock;
        this.messageSource = messageSource;
        this.maxOpenPerDelegator = maxOpenPerDelegator;
    }

    @Override
    @Transactional
    public ReviewDelegationView create(CreateReviewDelegationCommand command) {
        validateWindow(command);
        validateParties(command);
        var scopeName = validateScope(command);
        validateCap(command);

        var entity = new ReviewDelegationEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(command.organizationId());
        entity.setDelegatorId(command.delegatorUserId());
        entity.setDelegateId(command.delegateUserId());
        entity.setScopeKind(command.scopeKind());
        entity.setScopeId(command.scopeId());
        entity.setReason(command.reason());
        entity.setStartsAt(command.startsAt());
        entity.setEndsAt(command.endsAt());
        entity.setCreatedBy(command.delegatorUserId());
        var saved = delegationRepository.save(entity);
        return toView(saved, scopeName);
    }

    @Override
    @Transactional
    public void revoke(UUID delegationId, UUID organizationId, UUID actingUserId) {
        var entity = delegationRepository.findByIdAndOrganizationId(delegationId, organizationId)
                .orElseThrow(() -> new ReviewDelegationNotFoundException(delegationId));
        // Only the delegator may revoke: the delegate declining cover is a different action, and
        // letting them clear the row would erase the delegator's own record of it.
        if (!entity.getDelegatorId().equals(actingUserId)) {
            throw new ReviewDelegationNotFoundException(delegationId);
        }
        if (entity.getRevokedAt() != null) {
            return;
        }
        entity.setRevokedAt(clock.instant());
        entity.setRevokedBy(actingUserId);
        entity.setUpdatedAt(clock.instant());
        delegationRepository.save(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReviewDelegateCandidate> listDelegateCandidates(UUID organizationId, UUID callerId) {
        return userRepository.findAllByOrganization_Id(organizationId).stream()
                .filter(UserEntity::isActive)
                .filter(user -> !user.getId().equals(callerId))
                .map(user -> new ReviewDelegateCandidate(user.getId(), user.getEmail(),
                        user.getDisplayName()))
                .sorted((left, right) -> String.CASE_INSENSITIVE_ORDER.compare(
                        left.displayName() == null ? left.email() : left.displayName(),
                        right.displayName() == null ? right.email() : right.displayName()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReviewDelegationView> listGrantedBy(UUID organizationId, UUID delegatorUserId) {
        return toViews(delegationRepository
                .findByOrganizationIdAndDelegatorIdOrderByCreatedAtDesc(organizationId, delegatorUserId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReviewDelegationView> listReceivedBy(UUID organizationId, UUID delegateUserId) {
        return toViews(delegationRepository
                .findByOrganizationIdAndDelegateIdOrderByCreatedAtDesc(organizationId, delegateUserId));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ReviewDelegationView> listForOrganization(UUID organizationId,
                                                                  ReviewDelegationFilter filter,
                                                                  PageRequest pageRequest) {
        var page = delegationRepository.search(organizationId, filter.delegatorUserId(),
                filter.delegateUserId(), filter.activeOnly(), clock.instant(),
                PageAdapter.toSpringPageable(pageRequest));
        return new PageResponse<>(toViews(page.getContent()), page.getNumber(),
                page.getSize() <= 0 ? 1 : page.getSize(), page.getTotalElements(),
                page.getTotalPages());
    }

    private String msg(String key, Object... args) {
        return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
    }

    private void validateWindow(CreateReviewDelegationCommand command) {
        if (command.startsAt() == null || command.endsAt() == null
                || !command.endsAt().isAfter(command.startsAt())) {
            throw new IllegalReviewDelegationException(msg("error.review_delegation.window_inverted"));
        }
        if (!command.endsAt().isAfter(clock.instant())) {
            throw new IllegalReviewDelegationException(msg("error.review_delegation.window_closed"));
        }
    }

    private void validateParties(CreateReviewDelegationCommand command) {
        if (command.delegatorUserId().equals(command.delegateUserId())) {
            throw new IllegalReviewDelegationException(msg("error.review_delegation.self"));
        }
        requireActiveMember(command.organizationId(), command.delegatorUserId(), "delegator");
        requireActiveMember(command.organizationId(), command.delegateUserId(), "delegate");
    }

    private void requireActiveMember(UUID organizationId, UUID userId, String label) {
        var user = userRepository.findById(userId)
                .filter(UserEntity::isActive)
                .filter(candidate -> candidate.getOrganization() != null
                        && organizationId.equals(candidate.getOrganization().getId()));
        if (user.isEmpty()) {
            throw new IllegalReviewDelegationException(msg("error.review_delegation." + label + "_not_member"));
        }
    }

    /** @return the scoped resource's display name, or null for an unrestricted delegation */
    private String validateScope(CreateReviewDelegationCommand command) {
        var kind = command.scopeKind();
        var scopeId = command.scopeId();
        if (kind == null && scopeId == null) {
            return null;
        }
        if (kind == null || scopeId == null) {
            throw new IllegalReviewDelegationException(msg("error.review_delegation.scope_incomplete"));
        }
        return resolveScopeName(command.organizationId(), kind, scopeId)
                .orElseThrow(() -> new IllegalReviewDelegationException(
                        msg("error.review_delegation.scope_unresolved", kind.name())));
    }

    private void validateCap(CreateReviewDelegationCommand command) {
        var open = delegationRepository.countOpenForDelegator(command.organizationId(),
                command.delegatorUserId(), clock.instant());
        if (open >= maxOpenPerDelegator) {
            throw new IllegalReviewDelegationException(
                    msg("error.review_delegation.cap_reached", maxOpenPerDelegator));
        }
    }

    private Optional<String> resolveScopeName(UUID organizationId, DelegationScopeKind kind,
                                              UUID scopeId) {
        var resolver = scopeResolvers.get(kind);
        // A kind with no resolver registered fails closed rather than silently accepting an
        // unvalidated reference.
        return resolver == null ? Optional.empty() : resolver.resolveName(organizationId, scopeId);
    }

    private List<ReviewDelegationView> toViews(List<ReviewDelegationEntity> rows) {
        return rows.stream()
                .map(row -> toView(row, row.getScopeKind() == null ? null
                        : resolveScopeName(row.getOrganizationId(), row.getScopeKind(),
                                row.getScopeId()).orElse(null)))
                .toList();
    }

    private ReviewDelegationView toView(ReviewDelegationEntity row, String scopeName) {
        var delegator = userRepository.findById(row.getDelegatorId()).orElse(null);
        var delegate = userRepository.findById(row.getDelegateId()).orElse(null);
        return new ReviewDelegationView(
                row.getId(),
                row.getOrganizationId(),
                row.getDelegatorId(),
                delegator == null ? null : delegator.getDisplayName(),
                delegator == null ? null : delegator.getEmail(),
                row.getDelegateId(),
                delegate == null ? null : delegate.getDisplayName(),
                delegate == null ? null : delegate.getEmail(),
                row.getScopeKind(),
                row.getScopeId(),
                scopeName,
                row.getReason(),
                row.getStartsAt(),
                row.getEndsAt(),
                row.getRevokedAt(),
                statusOf(row, clock.instant()),
                row.getCreatedAt());
    }

    /** Derived, never stored — so a delegation ages from SCHEDULED to ACTIVE to EXPIRED with no job. */
    private static ReviewDelegationStatus statusOf(ReviewDelegationEntity row, Instant now) {
        if (row.getRevokedAt() != null) {
            return ReviewDelegationStatus.REVOKED;
        }
        if (!row.getEndsAt().isAfter(now)) {
            return ReviewDelegationStatus.EXPIRED;
        }
        if (row.getStartsAt().isAfter(now)) {
            return ReviewDelegationStatus.SCHEDULED;
        }
        return ReviewDelegationStatus.ACTIVE;
    }
}
