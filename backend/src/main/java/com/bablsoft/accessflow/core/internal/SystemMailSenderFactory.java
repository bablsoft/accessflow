package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.SystemSmtpSendingConfig;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import java.util.Properties;

/**
 * Builds a {@link JavaMailSender} on demand from the resolved system-SMTP sending config.
 * Mirrors the options used by the per-channel email strategy so connection behaviour is identical.
 */
@Component
class SystemMailSenderFactory {

    JavaMailSender create(SystemSmtpSendingConfig config) {
        var sender = new JavaMailSenderImpl();
        sender.setHost(config.host());
        sender.setPort(config.port());
        sender.setUsername(config.username());
        sender.setPassword(config.plaintextPassword());
        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", config.username() != null);
        props.put("mail.smtp.starttls.enable", config.tls());
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");
        return sender;
    }
}
