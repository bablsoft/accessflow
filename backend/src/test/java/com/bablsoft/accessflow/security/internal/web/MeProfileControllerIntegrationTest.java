package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.security.internal.jwt.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.context.WebApplicationContext;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest(properties = "accessflow.encryption-key=00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff")
@ImportTestcontainers(TestcontainersConfig.class)
class MeProfileControllerIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtService jwtService;
    @Autowired com.bablsoft.accessflow.core.api.CredentialEncryptionService encryptionService;
    @Autowired com.bablsoft.accessflow.core.internal.totp.TotpCodec totpCodec;

    private MockMvcTester mvc;
    private UserEntity user;
    private String token;

    @DynamicPropertySource
    static void rsaProperties(DynamicPropertyRegistry registry) throws Exception {
        var kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        var kp = kpg.generateKeyPair();
        var privateKey = (RSAPrivateCrtKey) kp.getPrivate();
        var pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(privateKey.getEncoded())
                + "\n-----END PRIVATE KEY-----";
        registry.add("accessflow.jwt.private-key", () -> pem);
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcTester.from(context, builder -> builder.apply(springSecurity()).build());
        userRepository.deleteAll();
        organizationRepository.deleteAll();

        var org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Acme");
        org.setSlug("acme");
        organizationRepository.save(org);

        user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail("alice@example.com");
        user.setDisplayName("Alice");
        user.setPasswordHash(passwordEncoder.encode("Password123!"));
        user.setRole(UserRoleType.ANALYST);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setActive(true);
        user.setOrganization(org);
        user = userRepository.save(user);

        var view = new com.bablsoft.accessflow.core.api.UserView(
                user.getId(), user.getEmail(), user.getDisplayName(), user.getRole(),
                user.getOrganization().getId(), user.isActive(), user.getAuthProvider(),
                user.getPasswordHash(), user.getLastLoginAt(), user.getPreferredLanguage(),
                user.isTotpEnabled(), user.getCreatedAt());
        token = jwtService.generateAccessToken(view);
    }

    @Test
    void getMeReturnsProfile() {
        var result = mvc.get().uri("/api/v1/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange();
        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.email").asString()
                .isEqualTo("alice@example.com");
        assertThat(result).bodyJson().extractingPath("$.totp_enabled").isEqualTo(false);
        assertThat(result).bodyJson().extractingPath("$.auth_provider").asString().isEqualTo("LOCAL");
    }

    @Test
    void updateProfileChangesDisplayName() {
        var result = mvc.put().uri("/api/v1/me/profile")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"display_name\":\"Alice Updated\"}")
                .exchange();
        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.display_name").asString()
                .isEqualTo("Alice Updated");
        assertThat(userRepository.findById(user.getId()).orElseThrow().getDisplayName())
                .isEqualTo("Alice Updated");
    }

    @Test
    void updateProfileRejectsBlankDisplayName() {
        var result = mvc.put().uri("/api/v1/me/profile")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"display_name\":\"\"}")
                .exchange();
        assertThat(result).hasStatus(400);
    }

    @Test
    void changePasswordWithCorrectCurrentPasswordSucceeds() {
        var result = mvc.post().uri("/api/v1/me/password")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"current_password\":\"Password123!\",\"new_password\":\"NewPassword456!\"}")
                .exchange();
        assertThat(result).hasStatus(204);
        var reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(passwordEncoder.matches("NewPassword456!", reloaded.getPasswordHash())).isTrue();
    }

    @Test
    void changePasswordRejectsWrongCurrentPassword() {
        var result = mvc.post().uri("/api/v1/me/password")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"current_password\":\"WrongPassword!\",\"new_password\":\"NewPassword456!\"}")
                .exchange();
        assertThat(result).hasStatus(422);
        assertThat(result).bodyJson().extractingPath("$.error").asString().isEqualTo("PASSWORD_INCORRECT");
    }

    @Test
    void enrollTotpReturnsSecretAndQrData() {
        var result = mvc.post().uri("/api/v1/me/totp/enroll")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange();
        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.secret").asString().isNotBlank();
        assertThat(result).bodyJson().extractingPath("$.otpauth_url").asString().startsWith("otpauth://totp/");
        assertThat(result).bodyJson().extractingPath("$.qr_data_uri").asString().startsWith("data:image/png;base64,");
    }

    @Test
    void confirmTotpWithBadCodeReturns422() {
        // First begin enrollment so the secret is set
        mvc.post().uri("/api/v1/me/totp/enroll")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();

        var result = mvc.post().uri("/api/v1/me/totp/confirm")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"000000\"}")
                .exchange();
        assertThat(result).hasStatus(422);
        assertThat(result).bodyJson().extractingPath("$.error").asString().isEqualTo("TOTP_INVALID_CODE");
    }

    @Test
    void confirmTotpWithValidCodeEnables2faAndReturnsBackupCodes() throws Exception {
        // Begin enrollment and capture the secret.
        var enrollResult = mvc.post().uri("/api/v1/me/totp/enroll")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
        var enrollBody = enrollResult.getResponse().getContentAsString();
        var secret = enrollBody.replaceAll(".*\"secret\":\"([^\"]+)\".*", "$1");

        // Compute a valid 6-digit TOTP for the current 30-second window.
        var validCode = generateCode(secret, java.time.Instant.now().getEpochSecond() / 30);

        var result = mvc.post().uri("/api/v1/me/totp/confirm")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + validCode + "\"}")
                .exchange();
        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.backup_codes").asArray().hasSize(10);
        assertThat(userRepository.findById(user.getId()).orElseThrow().isTotpEnabled()).isTrue();
    }

    @Test
    void enrollTotpRejectedWhenAlreadyEnabled() {
        user.setTotpEnabled(true);
        user.setTotpSecretEncrypted(encryptionService.encrypt("JBSWY3DPEHPK3PXP"));
        userRepository.save(user);

        var result = mvc.post().uri("/api/v1/me/totp/enroll")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange();
        assertThat(result).hasStatus(422);
        assertThat(result).bodyJson().extractingPath("$.error").asString().isEqualTo("TOTP_ALREADY_ENABLED");
    }

    @Test
    void disableTotpWithCorrectPasswordSucceedsAndClearsState() {
        user.setTotpEnabled(true);
        user.setTotpSecretEncrypted(encryptionService.encrypt("JBSWY3DPEHPK3PXP"));
        user.setTotpBackupCodesEncrypted(encryptionService.encrypt("[\"h:c1\",\"h:c2\"]"));
        userRepository.save(user);

        var result = mvc.post().uri("/api/v1/me/totp/disable")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"current_password\":\"Password123!\"}")
                .exchange();
        assertThat(result).hasStatus(204);
        var reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.isTotpEnabled()).isFalse();
        assertThat(reloaded.getTotpSecretEncrypted()).isNull();
        assertThat(reloaded.getTotpBackupCodesEncrypted()).isNull();
    }

    @Test
    void changePasswordOnSamlAccountReturns422() {
        user.setAuthProvider(AuthProviderType.SAML);
        user.setPasswordHash(null);
        userRepository.save(user);

        var result = mvc.post().uri("/api/v1/me/password")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"current_password\":\"x12345678\",\"new_password\":\"NewPassword456!\"}")
                .exchange();
        assertThat(result).hasStatus(422);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("PASSWORD_CHANGE_NOT_ALLOWED");
    }

    private String generateCode(String secret, long timeBucket) throws Exception {
        var generator = new dev.samstevens.totp.code.DefaultCodeGenerator();
        return generator.generate(secret, timeBucket);
    }

    @Test
    void disableTotpRejectsWhenNotEnabled() {
        var result = mvc.post().uri("/api/v1/me/totp/disable")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"current_password\":\"Password123!\"}")
                .exchange();
        assertThat(result).hasStatus(422);
        assertThat(result).bodyJson().extractingPath("$.error").asString().isEqualTo("TOTP_NOT_ENABLED");
    }
}
