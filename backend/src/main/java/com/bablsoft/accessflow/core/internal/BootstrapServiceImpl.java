package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.BootstrapService;
import com.bablsoft.accessflow.core.api.CreateUserCommand;
import com.bablsoft.accessflow.core.api.SetupAlreadyCompletedException;
import com.bablsoft.accessflow.core.api.SetupCommand;
import com.bablsoft.accessflow.core.api.SetupResult;
import com.bablsoft.accessflow.core.api.UserAdminService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class BootstrapServiceImpl implements BootstrapService {

    private static final int MAX_SLUG_LENGTH = 100;
    private static final int SUFFIX_LENGTH = 6;
    private static final String SUFFIX_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final UserAdminService userAdminService;

    @Override
    @Transactional(readOnly = true)
    public boolean isSetupRequired() {
        return !userRepository.existsByRoleAndActive(UserRoleType.ADMIN, true);
    }

    @Override
    @Transactional
    public SetupResult performSetup(SetupCommand command) {
        if (userRepository.existsByRoleAndActive(UserRoleType.ADMIN, true)) {
            throw new SetupAlreadyCompletedException();
        }

        var organization = new OrganizationEntity();
        organization.setId(UUID.randomUUID());
        organization.setName(command.organizationName());
        organization.setSlug(uniqueSlugFor(command.organizationName()));
        organization.setCreatedAt(Instant.now());
        organization.setUpdatedAt(Instant.now());
        var savedOrg = organizationRepository.save(organization);

        var createdUser = userAdminService.createUser(new CreateUserCommand(
                savedOrg.getId(),
                command.email(),
                command.displayName(),
                command.passwordHash(),
                UserRoleType.ADMIN
        ));

        return new SetupResult(savedOrg.getId(), createdUser.id());
    }

    private String uniqueSlugFor(String name) {
        var base = slugify(name);
        if (!organizationRepository.existsBySlug(base)) {
            return base;
        }
        for (int attempt = 0; attempt < 5; attempt++) {
            var candidate = appendSuffix(base);
            if (!organizationRepository.existsBySlug(candidate)) {
                return candidate;
            }
        }
        // Extremely unlikely; fall back to a UUID-derived suffix that is essentially collision-free.
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

    private static String appendSuffix(String base) {
        var sb = new StringBuilder(SUFFIX_LENGTH);
        for (int i = 0; i < SUFFIX_LENGTH; i++) {
            sb.append(SUFFIX_ALPHABET.charAt(RANDOM.nextInt(SUFFIX_ALPHABET.length())));
        }
        return appendSuffix(base, sb.toString());
    }

    private static String appendSuffix(String base, String suffix) {
        var separator = "-";
        var maxBase = MAX_SLUG_LENGTH - suffix.length() - separator.length();
        var trimmed = base.length() > maxBase ? base.substring(0, maxBase) : base;
        return trimmed + separator + suffix;
    }
}
