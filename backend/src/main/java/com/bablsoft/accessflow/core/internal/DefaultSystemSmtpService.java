package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.SaveSystemSmtpCommand;
import com.bablsoft.accessflow.core.api.SystemSmtpDeliveryException;
import com.bablsoft.accessflow.core.api.SystemSmtpNotConfiguredException;
import com.bablsoft.accessflow.core.api.SystemSmtpSendingConfig;
import com.bablsoft.accessflow.core.api.SystemSmtpService;
import com.bablsoft.accessflow.core.api.SystemSmtpView;
import com.bablsoft.accessflow.core.internal.persistence.entity.SystemSmtpConfigEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.SystemSmtpConfigRepository;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
class DefaultSystemSmtpService implements SystemSmtpService {

    private final SystemSmtpConfigRepository repository;
    private final CredentialEncryptionService encryptionService;
    private final SystemMailSenderFactory mailSenderFactory;

    @Override
    @Transactional(readOnly = true)
    public Optional<SystemSmtpView> findForOrganization(UUID organizationId) {
        return repository.findByOrganizationId(organizationId).map(DefaultSystemSmtpService::toView);
    }

    @Override
    @Transactional
    public SystemSmtpView saveOrUpdate(UUID organizationId, SaveSystemSmtpCommand command) {
        var entity = repository.findByOrganizationId(organizationId)
                .orElseGet(() -> {
                    var fresh = new SystemSmtpConfigEntity();
                    fresh.setId(UUID.randomUUID());
                    fresh.setOrganizationId(organizationId);
                    return fresh;
                });
        entity.setHost(command.host());
        entity.setPort(command.port());
        entity.setUsername(emptyToNull(command.username()));
        if (command.plaintextPassword() != null && !command.plaintextPassword().isBlank()) {
            entity.setPasswordEncrypted(encryptionService.encrypt(command.plaintextPassword()));
        }
        entity.setTls(command.tls());
        entity.setFromAddress(command.fromAddress());
        entity.setFromName(emptyToNull(command.fromName()));
        entity.setUpdatedAt(Instant.now());
        return toView(repository.save(entity));
    }

    @Override
    @Transactional
    public void delete(UUID organizationId) {
        repository.deleteByOrganizationId(organizationId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SystemSmtpSendingConfig> resolveSendingConfig(UUID organizationId) {
        return repository.findByOrganizationId(organizationId)
                .map(entity -> toSendingConfig(organizationId, entity));
    }

    @Override
    public void sendSystemEmail(UUID organizationId, String toEmail, String subject, String htmlBody) {
        var config = resolveSendingConfig(organizationId)
                .orElseThrow(SystemSmtpNotConfiguredException::new);
        sendOne(config, toEmail, subject, htmlBody);
    }

    @Override
    public void sendTest(UUID organizationId, SaveSystemSmtpCommand override, String toEmail) {
        var config = override != null
                ? toSendingConfig(organizationId, override, resolveExistingPasswordOrNull(organizationId, override))
                : resolveSendingConfig(organizationId)
                        .orElseThrow(SystemSmtpNotConfiguredException::new);
        var recipient = (toEmail != null && !toEmail.isBlank()) ? toEmail : config.fromAddress();
        sendOne(config, recipient, "AccessFlow system SMTP test",
                "<p>This is a test message from AccessFlow's system SMTP configuration.</p>");
    }

    private String resolveExistingPasswordOrNull(UUID organizationId, SaveSystemSmtpCommand override) {
        if (override.plaintextPassword() != null && !override.plaintextPassword().isBlank()) {
            return override.plaintextPassword();
        }
        return repository.findByOrganizationId(organizationId)
                .map(SystemSmtpConfigEntity::getPasswordEncrypted)
                .filter(s -> s != null && !s.isBlank())
                .map(encryptionService::decrypt)
                .orElse(null);
    }

    private void sendOne(SystemSmtpSendingConfig config, String to, String subject, String html) {
        if (to == null || to.isBlank()) {
            return;
        }
        JavaMailSender sender = mailSenderFactory.create(config);
        try {
            MimeMessage message = sender.createMimeMessage();
            var helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(buildFrom(config));
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            sender.send(message);
        } catch (MessagingException | UnsupportedEncodingException ex) {
            throw new SystemSmtpDeliveryException("System email composition failed", ex);
        } catch (RuntimeException ex) {
            throw new SystemSmtpDeliveryException("System email delivery failed", ex);
        }
    }

    private InternetAddress buildFrom(SystemSmtpSendingConfig config) throws UnsupportedEncodingException {
        if (config.fromName() != null && !config.fromName().isBlank()) {
            return new InternetAddress(config.fromAddress(), config.fromName(),
                    StandardCharsets.UTF_8.name());
        }
        try {
            return new InternetAddress(config.fromAddress());
        } catch (AddressException ex) {
            throw new SystemSmtpDeliveryException(
                    "Invalid system SMTP from address: " + config.fromAddress(), ex);
        }
    }

    private SystemSmtpSendingConfig toSendingConfig(UUID organizationId, SystemSmtpConfigEntity entity) {
        var passwordPlain = entity.getPasswordEncrypted() != null && !entity.getPasswordEncrypted().isBlank()
                ? encryptionService.decrypt(entity.getPasswordEncrypted())
                : null;
        return new SystemSmtpSendingConfig(
                organizationId,
                entity.getHost(),
                entity.getPort(),
                entity.getUsername(),
                passwordPlain,
                entity.isTls(),
                entity.getFromAddress(),
                entity.getFromName());
    }

    private SystemSmtpSendingConfig toSendingConfig(UUID organizationId, SaveSystemSmtpCommand command,
                                                    String resolvedPassword) {
        return new SystemSmtpSendingConfig(
                organizationId,
                command.host(),
                command.port(),
                emptyToNull(command.username()),
                resolvedPassword,
                command.tls(),
                command.fromAddress(),
                emptyToNull(command.fromName()));
    }

    private static SystemSmtpView toView(SystemSmtpConfigEntity entity) {
        return new SystemSmtpView(
                entity.getOrganizationId(),
                entity.getHost(),
                entity.getPort(),
                entity.getUsername(),
                entity.isTls(),
                entity.getFromAddress(),
                entity.getFromName(),
                entity.getUpdatedAt());
    }

    private static String emptyToNull(String s) {
        if (s == null) {
            return null;
        }
        var trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
