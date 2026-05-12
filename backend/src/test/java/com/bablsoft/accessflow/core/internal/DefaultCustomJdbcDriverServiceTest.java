package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.CustomDriverDuplicateException;
import com.bablsoft.accessflow.core.api.CustomDriverInUseException;
import com.bablsoft.accessflow.core.api.CustomDriverInvalidJarException;
import com.bablsoft.accessflow.core.api.CustomDriverNotFoundException;
import com.bablsoft.accessflow.core.api.CustomDriverStorageService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.DriverCatalogService;
import com.bablsoft.accessflow.core.api.UploadCustomDriverCommand;
import com.bablsoft.accessflow.core.events.CustomJdbcDriverDeletedEvent;
import com.bablsoft.accessflow.core.events.CustomJdbcDriverRegisteredEvent;
import com.bablsoft.accessflow.core.internal.persistence.entity.CustomJdbcDriverEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.CustomJdbcDriverRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultCustomJdbcDriverServiceTest {

    @Mock CustomJdbcDriverRepository repository;
    @Mock DatasourceRepository datasourceRepository;
    @Mock OrganizationRepository organizationRepository;
    @Mock UserRepository userRepository;
    @Mock CustomDriverStorageService storage;
    @Mock DriverCatalogService driverCatalog;
    @Mock ApplicationEventPublisher eventPublisher;

    private DefaultCustomJdbcDriverService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultCustomJdbcDriverService(
                repository, datasourceRepository, organizationRepository, userRepository,
                storage, driverCatalog, eventPublisher);
    }

    @Test
    void registerStoresJarVerifiesDriverClassPersistsAndPublishesEvent() throws Exception {
        var driverBytes = TestDriverJar.bytes();
        var sha = sha256(driverBytes);
        var command = uploadCommand(sha, driverBytes);

        when(repository.findByOrganization_IdAndJarSha256(eq(orgId), anyString()))
                .thenReturn(Optional.empty());
        var jarOnDisk = TestDriverJar.writeTo(java.nio.file.Files.createTempDirectory("custom-driver-"),
                "driver.jar");
        when(storage.store(eq(orgId), any(), eq(sha), anyLong(), any(InputStream.class)))
                .thenReturn(new CustomDriverStorageService.StoredCustomDriverJar(
                        "custom/" + orgId + "/file.jar", jarOnDisk, sha, driverBytes.length));
        var org = new OrganizationEntity();
        org.setId(orgId);
        when(organizationRepository.getReferenceById(orgId)).thenReturn(org);
        var uploader = new UserEntity();
        uploader.setId(userId);
        uploader.setDisplayName("Admin");
        when(userRepository.findById(userId)).thenReturn(Optional.of(uploader));
        when(repository.save(any(CustomJdbcDriverEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var view = service.register(command);

        assertThat(view.id()).isNotNull();
        assertThat(view.vendorName()).isEqualTo("Acme");
        assertThat(view.jarSha256()).isEqualTo(sha);
        assertThat(view.uploadedByDisplayName()).isEqualTo("Admin");

        var captor = ArgumentCaptor.forClass(CustomJdbcDriverRegisteredEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().vendorName()).isEqualTo("Acme");
        assertThat(captor.getValue().targetDbType()).isEqualTo(DbType.POSTGRESQL);
    }

    @Test
    void registerRejectsDuplicateOnSameSha() throws Exception {
        var driverBytes = TestDriverJar.bytes();
        var sha = sha256(driverBytes);
        var existing = new CustomJdbcDriverEntity();
        existing.setId(UUID.randomUUID());
        existing.setJarSha256(sha);
        when(repository.findByOrganization_IdAndJarSha256(eq(orgId), eq(sha)))
                .thenReturn(Optional.of(existing));

        var command = uploadCommand(sha, driverBytes);

        assertThatThrownBy(() -> service.register(command))
                .isInstanceOf(CustomDriverDuplicateException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void registerDeletesStoredJarWhenDriverClassMissing() throws Exception {
        var driverBytes = TestDriverJar.bytes();
        var sha = sha256(driverBytes);
        when(repository.findByOrganization_IdAndJarSha256(eq(orgId), anyString()))
                .thenReturn(Optional.empty());
        var jarOnDisk = TestDriverJar.writeTo(java.nio.file.Files.createTempDirectory("custom-driver-"),
                "driver.jar");
        when(storage.store(eq(orgId), any(), eq(sha), anyLong(), any(InputStream.class)))
                .thenReturn(new CustomDriverStorageService.StoredCustomDriverJar(
                        "custom/" + orgId + "/file.jar", jarOnDisk, sha, driverBytes.length));

        var command = new UploadCustomDriverCommand(orgId, userId, "Acme", DbType.POSTGRESQL,
                "com.does.not.Exist", "driver.jar", sha, driverBytes.length,
                new ByteArrayInputStream(driverBytes));

        assertThatThrownBy(() -> service.register(command))
                .isInstanceOf(CustomDriverInvalidJarException.class);
        verify(storage).delete("custom/" + orgId + "/file.jar");
        verify(repository, never()).save(any());
    }

    @Test
    void deleteRejectsWhenDatasourceStillReferencesIt() {
        var driverId = UUID.randomUUID();
        var entity = sampleEntity(driverId);
        when(repository.findByIdAndOrganization_Id(driverId, orgId)).thenReturn(Optional.of(entity));
        var referencing = new DatasourceEntity();
        referencing.setId(UUID.randomUUID());
        when(datasourceRepository.findAllByCustomDriver_Id(driverId)).thenReturn(List.of(referencing));

        assertThatThrownBy(() -> service.delete(driverId, orgId))
                .isInstanceOf(CustomDriverInUseException.class)
                .satisfies(ex -> {
                    var inUse = (CustomDriverInUseException) ex;
                    assertThat(inUse.referencedBy()).containsExactly(referencing.getId());
                });
        verify(storage, never()).delete(anyString());
        verify(driverCatalog, never()).evictCustom(any());
    }

    @Test
    void deleteRemovesRowEvictsCacheAndDeletesFile() {
        var driverId = UUID.randomUUID();
        var entity = sampleEntity(driverId);
        when(repository.findByIdAndOrganization_Id(driverId, orgId)).thenReturn(Optional.of(entity));
        when(datasourceRepository.findAllByCustomDriver_Id(driverId)).thenReturn(List.of());

        service.delete(driverId, orgId);

        verify(repository).delete(entity);
        verify(driverCatalog).evictCustom(driverId);
        verify(storage).delete(entity.getStoragePath());

        var captor = ArgumentCaptor.forClass(CustomJdbcDriverDeletedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().driverId()).isEqualTo(driverId);
    }

    @Test
    void deleteThrowsNotFoundForUnknownDriver() {
        var driverId = UUID.randomUUID();
        when(repository.findByIdAndOrganization_Id(driverId, orgId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(driverId, orgId))
                .isInstanceOf(CustomDriverNotFoundException.class);
    }

    @Test
    void getThrowsNotFoundForUnknownDriver() {
        var driverId = UUID.randomUUID();
        when(repository.findByIdAndOrganization_Id(driverId, orgId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(driverId, orgId))
                .isInstanceOf(CustomDriverNotFoundException.class);
    }

    @Test
    void findByIdMapsToDescriptor() {
        var driverId = UUID.randomUUID();
        var entity = sampleEntity(driverId);
        when(repository.findByIdAndOrganization_Id(driverId, orgId)).thenReturn(Optional.of(entity));

        var descriptor = service.findById(driverId, orgId).orElseThrow();

        assertThat(descriptor.id()).isEqualTo(driverId);
        assertThat(descriptor.targetDbType()).isEqualTo(DbType.POSTGRESQL);
        assertThat(descriptor.driverClass()).isEqualTo("org.postgresql.Driver");
        assertThat(descriptor.storagePath()).isEqualTo(entity.getStoragePath());
    }

    @Test
    void listReturnsViewsForOrg() {
        var entity = sampleEntity(UUID.randomUUID());
        when(repository.findAllByOrganization_IdOrderByCreatedAtDesc(orgId))
                .thenReturn(List.of(entity));

        var result = service.list(orgId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).vendorName()).isEqualTo("Acme");
    }

    private UploadCustomDriverCommand uploadCommand(String sha, byte[] bytes) {
        // The standard fixture loads org.h2.Driver from the test classpath, which always
        // implements java.sql.Driver — keeping the probe step honest.
        return new UploadCustomDriverCommand(orgId, userId, "Acme", DbType.POSTGRESQL,
                "org.postgresql.Driver", "driver.jar", sha, bytes.length, new ByteArrayInputStream(bytes));
    }

    private CustomJdbcDriverEntity sampleEntity(UUID id) {
        var org = new OrganizationEntity();
        org.setId(orgId);
        var uploader = new UserEntity();
        uploader.setId(userId);
        uploader.setDisplayName("Admin");
        var entity = new CustomJdbcDriverEntity();
        entity.setId(id);
        entity.setOrganization(org);
        entity.setVendorName("Acme");
        entity.setTargetDbType(DbType.POSTGRESQL);
        entity.setDriverClass("org.postgresql.Driver");
        entity.setJarFilename("driver.jar");
        entity.setJarSha256("a".repeat(64));
        entity.setJarSizeBytes(1024);
        entity.setStoragePath("custom/" + orgId + "/" + id + ".jar");
        entity.setUploadedBy(uploader);
        return entity;
    }

    private static String sha256(byte[] bytes) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(bytes));
    }

    /**
     * Reuses a real JDBC driver JAR present on the test classpath (the bundled PostgreSQL
     * driver) so the {@code Class.forName} probe inside {@link DefaultCustomJdbcDriverService}
     * succeeds. Reading the JAR bytes off the classpath lets us exercise the full upload path
     * without committing a binary fixture.
     */
    private static final class TestDriverJar {
        private static volatile byte[] cached;

        static byte[] bytes() throws Exception {
            var existing = cached;
            if (existing != null) {
                return existing;
            }
            var source = org.postgresql.Driver.class.getProtectionDomain()
                    .getCodeSource().getLocation();
            var bytes = Files.readAllBytes(Path.of(source.toURI()));
            cached = bytes;
            return bytes;
        }

        static Path writeTo(Path dir, String name) throws Exception {
            var target = dir.resolve(name);
            Files.write(target, bytes());
            return target;
        }
    }
}
