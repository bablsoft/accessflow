package com.bablsoft.accessflow.audit.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditSinkConfigException;
import com.bablsoft.accessflow.audit.api.AuditSinkNameConflictException;
import com.bablsoft.accessflow.audit.api.AuditSinkNotFoundException;
import com.bablsoft.accessflow.audit.api.AuditSinkService;
import com.bablsoft.accessflow.audit.api.AuditSinkTestFailedException;
import com.bablsoft.accessflow.audit.api.AuditSinkType;
import com.bablsoft.accessflow.audit.api.AuditSinkView;
import com.bablsoft.accessflow.audit.api.CreateAuditSinkCommand;
import com.bablsoft.accessflow.audit.api.UpdateAuditSinkCommand;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.JwtClaims;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminAuditSinkControllerTest {

    private static final String BASE = "/api/v1/admin/audit-sinks";
    private static final Map<String, Object> MASKED_CONFIG =
            Map.of("url", "https://receiver.example.com/audit", "secret", "********");

    @Mock AuditSinkService service;
    @Mock AuditLogService auditLogService;
    @Mock MessageSource messageSource;

    private MockMvc mockMvc;

    private final UUID organizationId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID sinkId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        lenient().when(messageSource.getMessage(anyString(), any(), any(Locale.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        var claims = JwtClaims.forSystemRole(
                userId, "admin@example.com", UserRoleType.ADMIN, organizationId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(claims, "n/a", List.of()));
        var snakeCaseMapper = JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                // Same mixin Boot registers: flattens ProblemDetail properties ($.error).
                .addMixIn(org.springframework.http.ProblemDetail.class,
                        org.springframework.http.converter.json.ProblemDetailJacksonMixin.class)
                .build();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AdminAuditSinkController(service, auditLogService))
                .setControllerAdvice(new AuditSinkExceptionHandler(messageSource))
                .setCustomArgumentResolvers(
                        new AuthenticationPrincipalArgumentResolver(),
                        new RequestAuditContextArgumentResolver())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(snakeCaseMapper))
                .build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private AuditSinkView view(String name) {
        return new AuditSinkView(sinkId, organizationId, AuditSinkType.HTTPS_BATCH, name,
                MASKED_CONFIG, true, Instant.EPOCH, null, null, 0, null, 42L, false,
                Instant.parse("2026-08-19T10:00:00Z"), Instant.parse("2026-08-19T11:00:00Z"));
    }

    @Test
    void listReturnsMaskedSinksAndDoesNotAudit() throws Exception {
        when(service.list(organizationId)).thenReturn(List.of(view("splunk")));

        mockMvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(sinkId.toString()))
                .andExpect(jsonPath("$[0].organization_id").value(organizationId.toString()))
                .andExpect(jsonPath("$[0].name").value("splunk"))
                .andExpect(jsonPath("$[0].type").value("HTTPS_BATCH"))
                .andExpect(jsonPath("$[0].config.secret").value("********"))
                .andExpect(jsonPath("$[0].behind_count").value(42))
                .andExpect(jsonPath("$[0].behind_count_capped").value(false));

        verify(auditLogService, never()).record(any());
    }

    @Test
    void createReturns201WithLocationAndAudits() throws Exception {
        when(service.create(any(CreateAuditSinkCommand.class))).thenReturn(view("https-sink"));

        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("User-Agent", "ua/1")
                        .content("""
                                {"name":"https-sink","type":"HTTPS_BATCH",
                                 "config":{"url":"https://receiver.example.com/audit","secret":"s"}}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location",
                        org.hamcrest.Matchers.endsWith(BASE + "/" + sinkId)))
                .andExpect(jsonPath("$.name").value("https-sink"))
                .andExpect(jsonPath("$.config.secret").value("********"));

        var commandCaptor = ArgumentCaptor.forClass(CreateAuditSinkCommand.class);
        verify(service).create(commandCaptor.capture());
        assertThat(commandCaptor.getValue().organizationId()).isEqualTo(organizationId);
        assertThat(commandCaptor.getValue().type()).isEqualTo(AuditSinkType.HTTPS_BATCH);

        var auditCaptor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(auditCaptor.capture());
        var entry = auditCaptor.getValue();
        assertThat(entry.action()).isEqualTo(AuditAction.AUDIT_SINK_CREATED);
        assertThat(entry.resourceId()).isEqualTo(sinkId);
        assertThat(entry.actorId()).isEqualTo(userId);
        assertThat(entry.metadata()).containsEntry("name", "https-sink");
    }

    @Test
    void createWithBlankNameIs400AndNeverHitsTheService() throws Exception {
        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":" ","type":"HTTPS_BATCH","config":{}}
                                """))
                .andExpect(status().isBadRequest());

        verify(service, never()).create(any());
        verify(auditLogService, never()).record(any());
    }

    @Test
    void createNameConflictIs409() throws Exception {
        when(service.create(any(CreateAuditSinkCommand.class)))
                .thenThrow(new AuditSinkNameConflictException("dup"));

        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"dup","type":"HTTPS_BATCH","config":{"url":"https://x","secret":"s"}}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("AUDIT_SINK_NAME_EXISTS"));

        verify(auditLogService, never()).record(any());
    }

    @Test
    void createInvalidConfigIs422() throws Exception {
        when(service.create(any(CreateAuditSinkCommand.class)))
                .thenThrow(new AuditSinkConfigException("Missing required config key: url"));

        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"x","type":"HTTPS_BATCH","config":{}}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("AUDIT_SINK_CONFIG_INVALID"))
                .andExpect(jsonPath("$.detail").value("error.audit_sink_config_invalid"));
    }

    @Test
    void updateReturnsViewAndAudits() throws Exception {
        when(service.update(eq(sinkId), eq(organizationId), any(UpdateAuditSinkCommand.class)))
                .thenReturn(view("renamed"));

        mockMvc.perform(put(BASE + "/" + sinkId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"renamed","enabled":false}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("renamed"))
                .andExpect(jsonPath("$.config.secret").value("********"));

        var commandCaptor = ArgumentCaptor.forClass(UpdateAuditSinkCommand.class);
        verify(service).update(eq(sinkId), eq(organizationId), commandCaptor.capture());
        assertThat(commandCaptor.getValue().name()).isEqualTo("renamed");
        assertThat(commandCaptor.getValue().enabled()).isFalse();

        var auditCaptor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo(AuditAction.AUDIT_SINK_UPDATED);
    }

    @Test
    void updateUnknownSinkIs404() throws Exception {
        when(service.update(eq(sinkId), eq(organizationId), any(UpdateAuditSinkCommand.class)))
                .thenThrow(new AuditSinkNotFoundException(sinkId));

        mockMvc.perform(put(BASE + "/" + sinkId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("AUDIT_SINK_NOT_FOUND"));
    }

    @Test
    void deleteReturns204AndAudits() throws Exception {
        mockMvc.perform(delete(BASE + "/" + sinkId))
                .andExpect(status().isNoContent());

        verify(service).delete(sinkId, organizationId);
        var auditCaptor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo(AuditAction.AUDIT_SINK_DELETED);
        assertThat(auditCaptor.getValue().metadata()).isEmpty();
    }

    @Test
    void deleteUnknownSinkIs404WithoutAudit() throws Exception {
        doThrow(new AuditSinkNotFoundException(sinkId))
                .when(service).delete(sinkId, organizationId);

        mockMvc.perform(delete(BASE + "/" + sinkId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("AUDIT_SINK_NOT_FOUND"));

        verify(auditLogService, never()).record(any());
    }

    @Test
    void sendTestReturnsOkAndDoesNotAudit() throws Exception {
        mockMvc.perform(post(BASE + "/" + sinkId + "/test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"));

        verify(service).sendTest(sinkId, organizationId);
        verify(auditLogService, never()).record(any());
    }

    @Test
    void sendTestFailureIs502() throws Exception {
        doThrow(new AuditSinkTestFailedException("unreachable", null))
                .when(service).sendTest(sinkId, organizationId);

        mockMvc.perform(post(BASE + "/" + sinkId + "/test"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("AUDIT_SINK_TEST_FAILED"));
    }

    @Test
    void auditWriteFailureDoesNotBreakTheResponse() throws Exception {
        when(service.create(any(CreateAuditSinkCommand.class))).thenReturn(view("https-sink"));
        doThrow(new RuntimeException("audit datasource down"))
                .when(auditLogService).record(any(AuditEntry.class));

        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"https-sink","type":"HTTPS_BATCH",
                                 "config":{"url":"https://x","secret":"s"}}
                                """))
                .andExpect(status().isCreated());
    }
}
