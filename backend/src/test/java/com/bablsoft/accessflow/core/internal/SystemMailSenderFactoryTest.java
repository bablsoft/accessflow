package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.SystemSmtpSendingConfig;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SystemMailSenderFactoryTest {

    private final SystemMailSenderFactory factory = new SystemMailSenderFactory();

    @Test
    void createConfiguresHostPortAndCredentials() {
        var config = new SystemSmtpSendingConfig(UUID.randomUUID(),
                "smtp.example.com", 587, "user", "pass", true, "from@example.com", "From");

        var sender = (JavaMailSenderImpl) factory.create(config);

        assertThat(sender.getHost()).isEqualTo("smtp.example.com");
        assertThat(sender.getPort()).isEqualTo(587);
        assertThat(sender.getUsername()).isEqualTo("user");
        assertThat(sender.getPassword()).isEqualTo("pass");
        var props = sender.getJavaMailProperties();
        assertThat(props.get("mail.smtp.starttls.enable")).isEqualTo(true);
        assertThat(props.get("mail.smtp.auth")).isEqualTo(true);
    }

    @Test
    void createWithoutUsernameDisablesAuth() {
        var config = new SystemSmtpSendingConfig(UUID.randomUUID(),
                "smtp.example.com", 25, null, null, false, "from@example.com", null);

        var sender = (JavaMailSenderImpl) factory.create(config);

        var props = sender.getJavaMailProperties();
        assertThat(props.get("mail.smtp.auth")).isEqualTo(false);
        assertThat(props.get("mail.smtp.starttls.enable")).isEqualTo(false);
    }
}
