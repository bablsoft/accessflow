package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.OrganizationProvisioningService;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultOrganizationProvisioningService implements OrganizationProvisioningService {

    private static final int MAX_SLUG_LENGTH = 100;
    private static final int SUFFIX_LENGTH = 6;
    private static final String SUFFIX_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OrganizationRepository organizationRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> findBySlug(String slug) {
        if (slug == null || slug.isBlank()) {
            return Optional.empty();
        }
        return organizationRepository.findBySlug(slug).map(OrganizationEntity::getId);
    }

    @Override
    @Transactional
    public UUID create(String name, String requestedSlug) {
        var slug = uniqueSlug(requestedSlug == null || requestedSlug.isBlank() ? slugify(name) : requestedSlug);
        var organization = new OrganizationEntity();
        organization.setId(UUID.randomUUID());
        organization.setName(name);
        organization.setSlug(slug);
        organization.setCreatedAt(Instant.now());
        organization.setUpdatedAt(Instant.now());
        return organizationRepository.save(organization).getId();
    }

    private String uniqueSlug(String base) {
        if (!organizationRepository.existsBySlug(base)) {
            return base;
        }
        for (int attempt = 0; attempt < 5; attempt++) {
            var candidate = appendSuffix(base, randomSuffix());
            if (!organizationRepository.existsBySlug(candidate)) {
                return candidate;
            }
        }
        return appendSuffix(base, UUID.randomUUID().toString().replace("-", "").substring(0, SUFFIX_LENGTH));
    }

    private static String slugify(String input) {
        var lowered = input == null ? "" : input.toLowerCase();
        var sanitized = lowered
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        if (sanitized.isEmpty()) {
            sanitized = "org";
        }
        if (sanitized.length() > MAX_SLUG_LENGTH) {
            sanitized = sanitized.substring(0, MAX_SLUG_LENGTH);
        }
        return sanitized;
    }

    private static String randomSuffix() {
        var sb = new StringBuilder(SUFFIX_LENGTH);
        for (int i = 0; i < SUFFIX_LENGTH; i++) {
            sb.append(SUFFIX_ALPHABET.charAt(RANDOM.nextInt(SUFFIX_ALPHABET.length())));
        }
        return sb.toString();
    }

    private static String appendSuffix(String base, String suffix) {
        var separator = "-";
        var maxBase = MAX_SLUG_LENGTH - suffix.length() - separator.length();
        var trimmed = base.length() > maxBase ? base.substring(0, maxBase) : base;
        return trimmed + separator + suffix;
    }
}
