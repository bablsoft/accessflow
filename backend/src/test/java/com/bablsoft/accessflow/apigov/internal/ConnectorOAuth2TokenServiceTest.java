package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiExecutionException;
import com.bablsoft.accessflow.apigov.api.Oauth2ClientAuth;
import com.bablsoft.accessflow.apigov.api.Oauth2GrantType;
import com.bablsoft.accessflow.apigov.events.ApiConnectorTokenFailureEvent;
import com.bablsoft.accessflow.apigov.internal.config.ApigovOAuth2Properties;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorEntity;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
class ConnectorOAuth2TokenServiceTest {

    private static final String TOKEN_URI = "https://idp.test/oauth/token";

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private CredentialEncryptionService encryptionService;
    @Mock private com.bablsoft.accessflow.audit.api.AuditLogService auditLogService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private MessageSource messageSource;

    private MockRestServiceServer server;
    private ConnectorOAuth2TokenService service;

    private final UUID connectorId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        var restClient = builder.build();
        var props = new ApigovOAuth2Properties(Duration.ofSeconds(30), Duration.ofSeconds(10),
                Duration.ofSeconds(60), 3);
        service = new ConnectorOAuth2TokenService(redisTemplate, encryptionService, restClient,
                auditLogService, props, eventPublisher, messageSource);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(messageSource.getMessage(anyString(), any(), any())).thenAnswer(i -> i.getArgument(0));
    }

    private ApiConnectorEntity connector(Oauth2GrantType grant, Oauth2ClientAuth clientAuth) {
        var c = new ApiConnectorEntity();
        c.setId(connectorId);
        c.setOrganizationId(orgId);
        c.setOauth2TokenUri(TOKEN_URI);
        c.setOauth2ClientId("client-1");
        c.setOauth2ClientSecretEncrypted("ENC-SECRET");
        c.setOauth2GrantType(grant);
        c.setOauth2ClientAuth(clientAuth);
        return c;
    }

    @Test
    void clientCredentialsBasicAuthHappyPath() {
        var c = connector(Oauth2GrantType.CLIENT_CREDENTIALS, Oauth2ClientAuth.CLIENT_SECRET_BASIC);
        c.setOauth2Scopes("read write");
        c.setOauth2Audience("aud-1");
        when(valueOps.get(anyString())).thenReturn(null);
        when(encryptionService.decrypt("ENC-SECRET")).thenReturn("secret-1");
        when(encryptionService.encrypt("tok-123")).thenReturn("ENC-TOK");
        server.expect(requestTo(TOKEN_URI))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("Authorization", org.hamcrest.Matchers.startsWith("Basic ")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("grant_type=client_credentials")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("scope=read+write")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("audience=aud-1")))
                .andRespond(withSuccess("{\"access_token\":\"tok-123\",\"expires_in\":3600}",
                        MediaType.APPLICATION_JSON));

        var token = service.accessToken(c);

        assertThat(token).isEqualTo("tok-123");
        server.verify();
        var ttl = ArgumentCaptor.forClass(Duration.class);
        verify(valueOps).set(eq("apigov:oauth2:token:" + connectorId), eq("ENC-TOK"), ttl.capture());
        assertThat(ttl.getValue()).isEqualTo(Duration.ofSeconds(3600).minusSeconds(30));
        verify(auditLogService).record(any(AuditEntry.class));
        verify(redisTemplate).delete("apigov:oauth2:failcount:" + connectorId);
    }

    @Test
    void clientSecretPostPutsCredentialsInBody() {
        var c = connector(Oauth2GrantType.CLIENT_CREDENTIALS, Oauth2ClientAuth.CLIENT_SECRET_POST);
        when(valueOps.get(anyString())).thenReturn(null);
        when(encryptionService.decrypt("ENC-SECRET")).thenReturn("secret-1");
        when(encryptionService.encrypt(anyString())).thenReturn("ENC-TOK");
        server.expect(requestTo(TOKEN_URI))
                .andExpect(headerDoesNotExist("Authorization"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("client_id=client-1")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("client_secret=secret-1")))
                .andRespond(withSuccess("{\"access_token\":\"tok\",\"expires_in\":120}",
                        MediaType.APPLICATION_JSON));

        assertThat(service.accessToken(c)).isEqualTo("tok");
        server.verify();
    }

    @Test
    void refreshTokenGrant() {
        var c = connector(Oauth2GrantType.REFRESH_TOKEN, Oauth2ClientAuth.CLIENT_SECRET_BASIC);
        c.setOauth2RefreshTokenEncrypted("ENC-REFRESH");
        when(valueOps.get(anyString())).thenReturn(null);
        when(encryptionService.decrypt("ENC-SECRET")).thenReturn("secret-1");
        when(encryptionService.decrypt("ENC-REFRESH")).thenReturn("refresh-1");
        when(encryptionService.encrypt(anyString())).thenReturn("ENC-TOK");
        server.expect(requestTo(TOKEN_URI))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("grant_type=refresh_token")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("refresh_token=refresh-1")))
                .andRespond(withSuccess("{\"access_token\":\"tok\",\"expires_in\":120}",
                        MediaType.APPLICATION_JSON));

        assertThat(service.accessToken(c)).isEqualTo("tok");
        server.verify();
    }

    @Test
    void passwordGrant() {
        var c = connector(Oauth2GrantType.PASSWORD, Oauth2ClientAuth.CLIENT_SECRET_BASIC);
        c.setOauth2Username("alice");
        c.setOauth2PasswordEncrypted("ENC-PW");
        when(valueOps.get(anyString())).thenReturn(null);
        when(encryptionService.decrypt("ENC-SECRET")).thenReturn("secret-1");
        when(encryptionService.decrypt("ENC-PW")).thenReturn("pw-1");
        when(encryptionService.encrypt(anyString())).thenReturn("ENC-TOK");
        server.expect(requestTo(TOKEN_URI))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("grant_type=password")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("username=alice")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("password=pw-1")))
                .andRespond(withSuccess("{\"access_token\":\"tok\"}", MediaType.APPLICATION_JSON));

        assertThat(service.accessToken(c)).isEqualTo("tok");
        server.verify();
    }

    @Test
    void cacheHitReturnsDecryptedTokenWithoutNetworkOrAudit() {
        var c = connector(Oauth2GrantType.CLIENT_CREDENTIALS, Oauth2ClientAuth.CLIENT_SECRET_BASIC);
        when(valueOps.get("apigov:oauth2:token:" + connectorId)).thenReturn("ENC-CACHED");
        when(encryptionService.decrypt("ENC-CACHED")).thenReturn("cached-tok");

        assertThat(service.accessToken(c)).isEqualTo("cached-tok");
        server.verify(); // no HTTP call expected
        verify(auditLogService, never()).record(any());
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void missingExpiresInUsesFallbackTtl() {
        var c = connector(Oauth2GrantType.CLIENT_CREDENTIALS, Oauth2ClientAuth.CLIENT_SECRET_BASIC);
        when(valueOps.get(anyString())).thenReturn(null);
        when(encryptionService.decrypt("ENC-SECRET")).thenReturn("secret-1");
        when(encryptionService.encrypt(anyString())).thenReturn("ENC-TOK");
        server.expect(requestTo(TOKEN_URI))
                .andRespond(withSuccess("{\"access_token\":\"tok\"}", MediaType.APPLICATION_JSON));

        service.accessToken(c);

        var ttl = ArgumentCaptor.forClass(Duration.class);
        verify(valueOps).set(anyString(), eq("ENC-TOK"), ttl.capture());
        assertThat(ttl.getValue()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void tinyExpiresInFlooredToMinimumTtl() {
        var c = connector(Oauth2GrantType.CLIENT_CREDENTIALS, Oauth2ClientAuth.CLIENT_SECRET_BASIC);
        when(valueOps.get(anyString())).thenReturn(null);
        when(encryptionService.decrypt("ENC-SECRET")).thenReturn("secret-1");
        when(encryptionService.encrypt(anyString())).thenReturn("ENC-TOK");
        server.expect(requestTo(TOKEN_URI))
                .andRespond(withSuccess("{\"access_token\":\"tok\",\"expires_in\":5}", MediaType.APPLICATION_JSON));

        service.accessToken(c);

        var ttl = ArgumentCaptor.forClass(Duration.class);
        verify(valueOps).set(anyString(), eq("ENC-TOK"), ttl.capture());
        assertThat(ttl.getValue()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void fetchFreshSkipsCacheRead() {
        var c = connector(Oauth2GrantType.CLIENT_CREDENTIALS, Oauth2ClientAuth.CLIENT_SECRET_BASIC);
        when(encryptionService.decrypt("ENC-SECRET")).thenReturn("secret-1");
        when(encryptionService.encrypt(anyString())).thenReturn("ENC-TOK");
        server.expect(requestTo(TOKEN_URI))
                .andRespond(withSuccess("{\"access_token\":\"fresh\",\"expires_in\":120}", MediaType.APPLICATION_JSON));

        assertThat(service.fetchFresh(c)).isEqualTo("fresh");
        verify(valueOps, never()).get(anyString());
    }

    @Test
    void missingTokenUriFailsClosedWithoutNetwork() {
        var c = connector(Oauth2GrantType.CLIENT_CREDENTIALS, Oauth2ClientAuth.CLIENT_SECRET_BASIC);
        c.setOauth2TokenUri("  ");
        when(valueOps.get(anyString())).thenReturn(null);

        assertThatThrownBy(() -> service.accessToken(c))
                .isInstanceOf(ApiExecutionException.class)
                .hasMessage("error.apigov.oauth2_token_uri_missing");
        server.verify();
    }

    @Test
    void refreshGrantWithoutRefreshTokenFails() {
        var c = connector(Oauth2GrantType.REFRESH_TOKEN, Oauth2ClientAuth.CLIENT_SECRET_BASIC);
        when(valueOps.get(anyString())).thenReturn(null);
        lenient().when(encryptionService.decrypt("ENC-SECRET")).thenReturn("secret-1");

        assertThatThrownBy(() -> service.accessToken(c))
                .isInstanceOf(ApiExecutionException.class)
                .hasMessage("error.apigov.oauth2_refresh_token_missing");
    }

    @Test
    void passwordGrantWithoutPasswordFails() {
        var c = connector(Oauth2GrantType.PASSWORD, Oauth2ClientAuth.CLIENT_SECRET_BASIC);
        c.setOauth2Username("alice");
        when(valueOps.get(anyString())).thenReturn(null);
        lenient().when(encryptionService.decrypt("ENC-SECRET")).thenReturn("secret-1");

        assertThatThrownBy(() -> service.accessToken(c))
                .isInstanceOf(ApiExecutionException.class)
                .hasMessage("error.apigov.oauth2_password_missing");
    }

    @Test
    void nonSuccessResponseFails() {
        var c = connector(Oauth2GrantType.CLIENT_CREDENTIALS, Oauth2ClientAuth.CLIENT_SECRET_BASIC);
        when(valueOps.get(anyString())).thenReturn(null);
        when(encryptionService.decrypt("ENC-SECRET")).thenReturn("secret-1");
        server.expect(requestTo(TOKEN_URI)).andRespond(withServerError());

        assertThatThrownBy(() -> service.accessToken(c))
                .isInstanceOf(ApiExecutionException.class)
                .hasMessage("error.apigov.oauth2_token_fetch_failed");
    }

    @Test
    void successWithoutAccessTokenFails() {
        var c = connector(Oauth2GrantType.CLIENT_CREDENTIALS, Oauth2ClientAuth.CLIENT_SECRET_BASIC);
        when(valueOps.get(anyString())).thenReturn(null);
        when(encryptionService.decrypt("ENC-SECRET")).thenReturn("secret-1");
        server.expect(requestTo(TOKEN_URI))
                .andRespond(withSuccess("{\"token_type\":\"Bearer\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service.accessToken(c))
                .isInstanceOf(ApiExecutionException.class)
                .hasMessage("error.apigov.oauth2_token_no_access_token");
    }

    @Test
    void repeatedFailurePublishesAlertAtThreshold() {
        var c = connector(Oauth2GrantType.CLIENT_CREDENTIALS, Oauth2ClientAuth.CLIENT_SECRET_BASIC);
        c.setOauth2TokenUri(" ");
        when(valueOps.increment("apigov:oauth2:failcount:" + connectorId)).thenReturn(3L);

        assertThatThrownBy(() -> service.fetchFresh(c)).isInstanceOf(ApiExecutionException.class);

        var captor = ArgumentCaptor.forClass(ApiConnectorTokenFailureEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().connectorId()).isEqualTo(connectorId);
        assertThat(captor.getValue().organizationId()).isEqualTo(orgId);
    }

    @Test
    void failureBelowThresholdDoesNotPublish() {
        var c = connector(Oauth2GrantType.CLIENT_CREDENTIALS, Oauth2ClientAuth.CLIENT_SECRET_BASIC);
        c.setOauth2TokenUri(" ");
        when(valueOps.increment(anyString())).thenReturn(1L);

        assertThatThrownBy(() -> service.fetchFresh(c)).isInstanceOf(ApiExecutionException.class);

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void evictDeletesCacheKey() {
        service.evict(connectorId);
        verify(redisTemplate).delete("apigov:oauth2:token:" + connectorId);
    }

    @Test
    void auditMetadataNeverContainsToken() {
        var c = connector(Oauth2GrantType.CLIENT_CREDENTIALS, Oauth2ClientAuth.CLIENT_SECRET_BASIC);
        when(valueOps.get(anyString())).thenReturn(null);
        when(encryptionService.decrypt("ENC-SECRET")).thenReturn("secret-1");
        when(encryptionService.encrypt(anyString())).thenReturn("ENC-TOK");
        server.expect(requestTo(TOKEN_URI))
                .andRespond(withSuccess("{\"access_token\":\"super-secret-token\",\"expires_in\":120}",
                        MediaType.APPLICATION_JSON));

        service.accessToken(c);

        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(captor.capture());
        var entry = captor.getValue();
        assertThat(entry.action()).isEqualTo(AuditAction.API_CONNECTOR_OAUTH2_TOKEN_REFRESHED);
        assertThat(entry.metadata().toString()).doesNotContain("super-secret-token");
    }
}
