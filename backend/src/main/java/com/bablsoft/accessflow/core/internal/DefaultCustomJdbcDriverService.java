package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.CustomDriverDescriptor;
import com.bablsoft.accessflow.core.api.CustomDriverInUseException;
import com.bablsoft.accessflow.core.api.CustomDriverInvalidJarException;
import com.bablsoft.accessflow.core.api.CustomDriverDuplicateException;
import com.bablsoft.accessflow.core.api.CustomDriverNotFoundException;
import com.bablsoft.accessflow.core.api.CustomDriverStorageService;
import com.bablsoft.accessflow.core.api.CustomDriverView;
import com.bablsoft.accessflow.core.api.CustomJdbcDriverService;
import com.bablsoft.accessflow.core.api.DriverCatalogService;
import com.bablsoft.accessflow.core.api.UploadCustomDriverCommand;
import com.bablsoft.accessflow.core.events.CustomJdbcDriverDeletedEvent;
import com.bablsoft.accessflow.core.events.CustomJdbcDriverRegisteredEvent;
import com.bablsoft.accessflow.core.internal.persistence.entity.CustomJdbcDriverEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.CustomJdbcDriverRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Driver;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultCustomJdbcDriverService implements CustomJdbcDriverService {

    static final long MAX_JAR_BYTES = 50L * 1024 * 1024;

    private final CustomJdbcDriverRepository repository;
    private final DatasourceRepository datasourceRepository;
    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final CustomDriverStorageService storage;
    private final DriverCatalogService driverCatalog;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public CustomDriverView register(UploadCustomDriverCommand command) {
        // 1. Check for duplicate uploads in the same org. Cheaper than streaming a 50 MB JAR
        //    to disk just to fail at the unique constraint.
        repository.findByOrganization_IdAndJarSha256(
                command.organizationId(), normalizeSha(command.expectedSha256()))
                .ifPresent(existing -> {
                    throw new CustomDriverDuplicateException(existing.getId(), existing.getJarSha256());
                });

        // 2. Pre-assign the id so storage and DB row share it.
        UUID driverId = UUID.randomUUID();
        String expectedSha = normalizeSha(command.expectedSha256());

        CustomDriverStorageService.StoredCustomDriverJar stored;
        try {
            stored = storage.store(command.organizationId(), driverId, expectedSha,
                    MAX_JAR_BYTES, command.content());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to stream uploaded JDBC driver to disk", e);
        }

        // 3. Probe-load the driver class. Any failure must clean up the on-disk JAR so we
        //    don't leak orphan files for failed uploads.
        try {
            verifyDriverClass(stored.absolutePath().toUri().toURL(), command.driverClass());
        } catch (RuntimeException | IOException ex) {
            storage.delete(stored.relativePath());
            if (ex instanceof CustomDriverInvalidJarException invalid) {
                throw invalid;
            }
            throw new CustomDriverInvalidJarException(command.driverClass(),
                    "Failed to load driver class " + command.driverClass() + " from uploaded JAR",
                    ex);
        }

        var organization = organizationRepository.getReferenceById(command.organizationId());
        var uploader = userRepository.findById(command.uploadedByUserId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Uploader user not found: " + command.uploadedByUserId()));

        var entity = new CustomJdbcDriverEntity();
        entity.setId(driverId);
        entity.setOrganization(organization);
        entity.setVendorName(command.vendorName());
        entity.setTargetDbType(command.targetDbType());
        entity.setDriverClass(command.driverClass());
        entity.setJarFilename(command.jarFilename());
        entity.setJarSha256(expectedSha);
        entity.setJarSizeBytes(stored.sizeBytes());
        entity.setStoragePath(stored.relativePath());
        entity.setUploadedBy(uploader);
        var saved = repository.save(entity);

        eventPublisher.publishEvent(new CustomJdbcDriverRegisteredEvent(
                saved.getId(),
                command.organizationId(),
                command.uploadedByUserId(),
                command.vendorName(),
                command.targetDbType(),
                expectedSha));

        return toView(saved, uploader);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CustomDriverView> list(UUID organizationId) {
        return repository.findAllByOrganization_IdOrderByCreatedAtDesc(organizationId).stream()
                .map(e -> toView(e, e.getUploadedBy()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CustomDriverDescriptor> findById(UUID id, UUID organizationId) {
        return repository.findByIdAndOrganization_Id(id, organizationId)
                .map(DefaultCustomJdbcDriverService::toDescriptor);
    }

    @Override
    @Transactional(readOnly = true)
    public CustomDriverView get(UUID id, UUID organizationId) {
        var entity = repository.findByIdAndOrganization_Id(id, organizationId)
                .orElseThrow(() -> new CustomDriverNotFoundException(id));
        return toView(entity, entity.getUploadedBy());
    }

    @Override
    @Transactional
    public void delete(UUID id, UUID organizationId) {
        var entity = repository.findByIdAndOrganization_Id(id, organizationId)
                .orElseThrow(() -> new CustomDriverNotFoundException(id));
        var referencing = datasourceRepository.findAllByCustomDriver_Id(id);
        if (!referencing.isEmpty()) {
            throw new CustomDriverInUseException(id,
                    referencing.stream()
                            .map(com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity::getId)
                            .toList());
        }
        var storagePath = entity.getStoragePath();
        repository.delete(entity);
        driverCatalog.evictCustom(id);
        storage.delete(storagePath);
        eventPublisher.publishEvent(new CustomJdbcDriverDeletedEvent(
                entity.getId(),
                organizationId,
                entity.getUploadedBy() != null ? entity.getUploadedBy().getId() : null,
                entity.getVendorName(),
                entity.getTargetDbType(),
                entity.getJarSha256()));
    }

    /**
     * Probe-loads {@code driverClass} from the uploaded JAR to confirm it implements
     * {@link java.sql.Driver}. Uses a throwaway {@link URLClassLoader} that is closed before
     * return so the JAR is not kept open after validation.
     */
    private static void verifyDriverClass(URL jarUrl, String driverClass) throws IOException {
        try (var loader = new URLClassLoader(
                "accessflow-custom-driver-probe",
                new URL[]{jarUrl},
                DefaultCustomJdbcDriverService.class.getClassLoader())) {
            Class<?> loaded;
            try {
                loaded = Class.forName(driverClass, false, loader);
            } catch (ClassNotFoundException e) {
                throw new CustomDriverInvalidJarException(driverClass,
                        "Driver class " + driverClass + " not found in uploaded JAR", e);
            }
            if (!Driver.class.isAssignableFrom(loaded)) {
                throw new CustomDriverInvalidJarException(driverClass,
                        "Class " + driverClass + " does not implement java.sql.Driver");
            }
            // Instantiating verifies the class has a public no-arg constructor — required
            // by the JDBC spec for ServiceLoader / Driver instantiation.
            try {
                loaded.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new CustomDriverInvalidJarException(driverClass,
                        "Cannot instantiate driver class " + driverClass, e);
            }
        }
    }

    private static CustomDriverView toView(CustomJdbcDriverEntity entity, UserEntity uploader) {
        return new CustomDriverView(
                entity.getId(),
                entity.getOrganization().getId(),
                entity.getVendorName(),
                entity.getTargetDbType(),
                entity.getDriverClass(),
                entity.getJarFilename(),
                entity.getJarSha256(),
                entity.getJarSizeBytes(),
                uploader != null ? uploader.getId() : null,
                uploader != null ? uploader.getDisplayName() : null,
                entity.getCreatedAt());
    }

    private static CustomDriverDescriptor toDescriptor(CustomJdbcDriverEntity entity) {
        return new CustomDriverDescriptor(
                entity.getId(),
                entity.getOrganization().getId(),
                entity.getTargetDbType(),
                entity.getVendorName(),
                entity.getDriverClass(),
                entity.getJarFilename(),
                entity.getJarSha256(),
                entity.getJarSizeBytes(),
                entity.getStoragePath());
    }

    private static String normalizeSha(String sha) {
        return sha == null ? null : sha.toLowerCase();
    }
}
