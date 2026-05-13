package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.SaveSystemSmtpCommand;
import com.bablsoft.accessflow.core.api.SystemSmtpDeliveryException;
import com.bablsoft.accessflow.core.api.SystemSmtpNotConfiguredException;
import com.bablsoft.accessflow.core.internal.persistence.entity.SystemSmtpConfigEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.SystemSmtpConfigRepository;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultSystemSmtpServiceTest {

    @Mock SystemSmtpConfigRepository repository;
    @Mock CredentialEncryptionService encryptionService;
    @Mock SystemMailSenderFactory mailSenderFactory;
    @Mock JavaMailSender mailSender;

    private DefaultSystemSmtpService service;

    private final UUID orgId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultSystemSmtpService(repository, encryptionService, mailSenderFactory);
        lenient().when(mailSenderFactory.create(any())).thenReturn(mailSender);
        lenient().when(mailSender.createMimeMessage()).thenAnswer(inv ->
                new MimeMessage(Session.getInstance(new Properties())));
    }

    @Test
    void saveOrUpdateEncryptsPasswordWhenProvided() {
        when(repository.findByOrganizationId(orgId)).thenReturn(Optional.empty());
        when(encryptionService.encrypt("secret")).thenReturn("ENC");
        var captor = ArgumentCaptor.forClass(SystemSmtpConfigEntity.class);
        when(repository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        var view = service.saveOrUpdate(orgId, new SaveSystemSmtpCommand(
                "smtp.example.com", 587, "user", "secret", true,
                "no-reply@example.com", "AccessFlow"));

        assertThat(view.host()).isEqualTo("smtp.example.com");
        assertThat(captor.getValue().getPasswordEncrypted()).isEqualTo("ENC");
        verify(encryptionService).encrypt("secret");
    }

    @Test
    void saveOrUpdatePreservesExistingPasswordWhenPlaintextIsNull() {
        var existing = entity("smtp.old.com", "EXISTING_CIPHER");
        when(repository.findByOrganizationId(orgId)).thenReturn(Optional.of(existing));
        when(repository.save(any(SystemSmtpConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.saveOrUpdate(orgId, new SaveSystemSmtpCommand(
                "smtp.new.com", 25, null, null, false, "from@example.com", null));

        assertThat(existing.getHost()).isEqualTo("smtp.new.com");
        assertThat(existing.getPasswordEncrypted()).isEqualTo("EXISTING_CIPHER");
        verify(encryptionService, never()).encrypt(any());
    }

    @Test
    void deleteDelegatesToRepository() {
        service.delete(orgId);
        verify(repository).deleteByOrganizationId(orgId);
    }

    @Test
    void findForOrganizationReturnsMaskedView() {
        when(repository.findByOrganizationId(orgId)).thenReturn(Optional.of(entity("h", "ENC")));

        var view = service.findForOrganization(orgId);

        assertThat(view).isPresent();
        assertThat(view.get().host()).isEqualTo("h");
    }

    @Test
    void resolveSendingConfigDecryptsPassword() {
        when(repository.findByOrganizationId(orgId)).thenReturn(Optional.of(entity("h", "ENC")));
        when(encryptionService.decrypt("ENC")).thenReturn("secret");

        var sending = service.resolveSendingConfig(orgId);

        assertThat(sending).isPresent();
        assertThat(sending.get().plaintextPassword()).isEqualTo("secret");
    }

    @Test
    void resolveSendingConfigReturnsEmptyWhenMissing() {
        when(repository.findByOrganizationId(orgId)).thenReturn(Optional.empty());

        assertThat(service.resolveSendingConfig(orgId)).isEmpty();
    }

    @Test
    void sendSystemEmailThrowsWhenNotConfigured() {
        when(repository.findByOrganizationId(orgId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.sendSystemEmail(orgId, "to@example.com", "s", "<p>b</p>"))
                .isInstanceOf(SystemSmtpNotConfiguredException.class);
    }

    @Test
    void sendSystemEmailDelegatesToMailSender() {
        when(repository.findByOrganizationId(orgId)).thenReturn(Optional.of(entity("h", "ENC")));
        when(encryptionService.decrypt("ENC")).thenReturn("secret");

        service.sendSystemEmail(orgId, "to@example.com", "Subject", "<p>body</p>");

        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    void sendSystemEmailWrapsMailSenderExceptions() {
        when(repository.findByOrganizationId(orgId)).thenReturn(Optional.of(entity("h", "ENC")));
        when(encryptionService.decrypt("ENC")).thenReturn("secret");
        doThrow(new RuntimeException("boom")).when(mailSender).send(any(MimeMessage.class));

        assertThatThrownBy(() -> service.sendSystemEmail(orgId, "to@example.com", "s", "<p>b</p>"))
                .isInstanceOf(SystemSmtpDeliveryException.class);
    }

    @Test
    void sendTestWithOverrideDoesNotRequirePersistedRow() {
        service.sendTest(orgId, new SaveSystemSmtpCommand(
                "smtp.example.com", 587, "user", "secret", true,
                "from@example.com", "From"), "to@example.com");

        verify(mailSender).send(any(MimeMessage.class));
        verify(repository, never()).findByOrganizationId(any());
    }

    @Test
    void sendTestWithOverrideAndBlankPasswordResolvesFromPersisted() {
        when(repository.findByOrganizationId(orgId)).thenReturn(Optional.of(entity("h", "ENC")));
        when(encryptionService.decrypt("ENC")).thenReturn("secret");

        service.sendTest(orgId, new SaveSystemSmtpCommand(
                "smtp.example.com", 587, "user", null, true,
                "from@example.com", "From"), null);

        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    void sendTestUsesFromAddressWhenToIsBlank() {
        when(repository.findByOrganizationId(orgId)).thenReturn(Optional.of(entity("h", null)));

        service.sendTest(orgId, null, null);

        verify(mailSender).send(any(MimeMessage.class));
    }

    private SystemSmtpConfigEntity entity(String host, String passwordCipher) {
        var e = new SystemSmtpConfigEntity();
        e.setId(UUID.randomUUID());
        e.setOrganizationId(orgId);
        e.setHost(host);
        e.setPort(587);
        e.setUsername("user");
        e.setPasswordEncrypted(passwordCipher);
        e.setTls(true);
        e.setFromAddress("no-reply@example.com");
        e.setFromName("AccessFlow");
        return e;
    }
}
