import { apiClient } from './client';
import type {
  AiAnalysisStats,
  AiAnalysisStatsFilters,
  AiConfig,
  AuditChainFilters,
  AuditChainResult,
  AuditLogFilters,
  AuditLogPage,
  CreateAiConfigInput,
  CreateNotificationChannelInput,
  CreateUserInput,
  NotificationChannel,
  OAuth2Config,
  OAuth2Provider,
  SamlConfig,
  SetupProgress,
  TestAiConfigResult,
  TestNotificationChannelInput,
  TestNotificationResult,
  UpdateAiConfigInput,
  UpdateNotificationChannelInput,
  UpdateOAuth2ConfigInput,
  UpdateSamlConfigInput,
  UpdateUserInput,
  User,
  UserListFilters,
  UserPage,
  SystemSmtpConfig,
  UpdateSystemSmtpInput,
  TestSystemSmtpInput,
  UserInvitation,
  UserInvitationPage,
  InviteUserInput,
} from '@/types/api';

const USERS_BASE = '/api/v1/admin/users';
const AUDIT_BASE = '/api/v1/admin/audit-log';
const CHANNELS_BASE = '/api/v1/admin/notification-channels';
const AI_CONFIGS_BASE = '/api/v1/admin/ai-configs';
const AI_ANALYSES_BASE = '/api/v1/admin/ai-analyses';
const SAML_CONFIG_BASE = '/api/v1/admin/saml-config';
const OAUTH2_CONFIG_BASE = '/api/v1/admin/oauth2-config';
const SETUP_PROGRESS_BASE = '/api/v1/admin/setup-progress';
const SYSTEM_SMTP_BASE = '/api/v1/admin/system-smtp';
const INVITATIONS_BASE = '/api/v1/admin/users/invitations';

export const userKeys = {
  all: ['users'] as const,
  lists: () => ['users', 'list'] as const,
  list: (filters: UserListFilters) => ['users', 'list', filters] as const,
  details: () => ['users', 'detail'] as const,
  detail: (id: string) => ['users', 'detail', id] as const,
};

export const auditKeys = {
  all: ['audit'] as const,
  lists: () => ['audit', 'list'] as const,
  list: (filters: AuditLogFilters) => ['audit', 'list', filters] as const,
  verify: () => ['audit', 'verify'] as const,
};

export const aiAnalysesKeys = {
  all: ['ai-analyses'] as const,
  stats: (filters: AiAnalysisStatsFilters) => ['ai-analyses', 'stats', filters] as const,
};

export const notificationChannelKeys = {
  all: ['notificationChannels'] as const,
  lists: () => ['notificationChannels', 'list'] as const,
  details: () => ['notificationChannels', 'detail'] as const,
  detail: (id: string) => ['notificationChannels', 'detail', id] as const,
};

export const aiConfigKeys = {
  all: ['aiConfig'] as const,
  lists: () => ['aiConfig', 'list'] as const,
  details: () => ['aiConfig', 'detail'] as const,
  detail: (id: string) => ['aiConfig', 'detail', id] as const,
};

export const samlConfigKeys = {
  all: ['samlConfig'] as const,
  current: () => ['samlConfig', 'current'] as const,
};

export const oauth2ConfigKeys = {
  all: ['oauth2Config'] as const,
  list: () => ['oauth2Config', 'list'] as const,
  detail: (provider: OAuth2Provider) => ['oauth2Config', 'detail', provider] as const,
};

export const setupProgressKeys = {
  all: ['setupProgress'] as const,
  current: () => ['setupProgress', 'current'] as const,
};

export const systemSmtpKeys = {
  all: ['systemSmtp'] as const,
  current: () => ['systemSmtp', 'current'] as const,
};

export const invitationKeys = {
  all: ['invitations'] as const,
  lists: () => ['invitations', 'list'] as const,
  list: (filters: { page?: number; size?: number; sort?: string }) =>
    ['invitations', 'list', filters] as const,
};

// ── Users ─────────────────────────────────────────────────────────────────────

export async function listUsers(filters: UserListFilters = {}): Promise<UserPage> {
  const params: Record<string, string | number> = {};
  if (typeof filters.page === 'number') params.page = filters.page;
  if (typeof filters.size === 'number') params.size = filters.size;
  if (filters.sort) params.sort = filters.sort;
  const { data } = await apiClient.get<UserPage>(USERS_BASE, { params });
  return data;
}

export async function createUser(input: CreateUserInput): Promise<User> {
  const { data } = await apiClient.post<User>(USERS_BASE, input);
  return data;
}

export async function updateUser(id: string, input: UpdateUserInput): Promise<User> {
  const { data } = await apiClient.put<User>(`${USERS_BASE}/${id}`, input);
  return data;
}

export async function getUserAttributes(id: string): Promise<Record<string, string>> {
  const { data } = await apiClient.get<{ attributes: Record<string, string> }>(
    `${USERS_BASE}/${id}/attributes`,
  );
  return data.attributes;
}

export async function deactivateUser(id: string): Promise<void> {
  await apiClient.delete(`${USERS_BASE}/${id}`);
}

// ── Audit log ─────────────────────────────────────────────────────────────────

export async function listAuditEvents(
  filters: AuditLogFilters = {},
): Promise<AuditLogPage> {
  const params: Record<string, string | number> = {};
  if (filters.actor_id) params.actorId = filters.actor_id;
  if (filters.action) params.action = filters.action;
  if (filters.resource_type) params.resourceType = filters.resource_type;
  if (filters.resource_id) params.resourceId = filters.resource_id;
  if (filters.from) params.from = filters.from;
  if (filters.to) params.to = filters.to;
  if (typeof filters.page === 'number') params.page = filters.page;
  if (typeof filters.size === 'number') params.size = filters.size;
  if (filters.sort) params.sort = filters.sort;
  const { data } = await apiClient.get<AuditLogPage>(AUDIT_BASE, { params });
  return data;
}

export async function verifyAuditChain(
  filters: AuditChainFilters = {},
): Promise<AuditChainResult> {
  const params: Record<string, string> = {};
  if (filters.from) params.from = filters.from;
  if (filters.to) params.to = filters.to;
  const { data } = await apiClient.get<AuditChainResult>(`${AUDIT_BASE}/verify`, {
    params,
  });
  return data;
}

export interface AuditLogExportResult {
  blob: Blob;
  filename: string;
  truncated: boolean;
}

export async function exportAuditLogCsv(
  filters: AuditLogFilters = {},
): Promise<AuditLogExportResult> {
  const params: Record<string, string> = {};
  if (filters.actor_id) params.actorId = filters.actor_id;
  if (filters.action) params.action = filters.action;
  if (filters.resource_type) params.resourceType = filters.resource_type;
  if (filters.resource_id) params.resourceId = filters.resource_id;
  if (filters.from) params.from = filters.from;
  if (filters.to) params.to = filters.to;
  const response = await apiClient.get<Blob>(`${AUDIT_BASE}/export.csv`, {
    params,
    responseType: 'blob',
  });
  const disposition = response.headers['content-disposition'];
  const filename =
    parseAuditFilename(typeof disposition === 'string' ? disposition : undefined) ??
    defaultAuditFilename();
  const truncatedHeader = response.headers['x-accessflow-export-truncated'];
  return {
    blob: response.data,
    filename,
    truncated: typeof truncatedHeader === 'string' && truncatedHeader.toLowerCase() === 'true',
  };
}

function parseAuditFilename(header: string | undefined): string | null {
  if (!header) return null;
  const match = /filename="([^"]+)"/i.exec(header);
  return match?.[1] ?? null;
}

function defaultAuditFilename(): string {
  const now = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  const stamp =
    `${now.getUTCFullYear()}${pad(now.getUTCMonth() + 1)}${pad(now.getUTCDate())}` +
    `-${pad(now.getUTCHours())}${pad(now.getUTCMinutes())}${pad(now.getUTCSeconds())}`;
  return `audit-log-${stamp}.csv`;
}

// ── Notification channels ─────────────────────────────────────────────────────

export async function listChannels(): Promise<NotificationChannel[]> {
  const { data } = await apiClient.get<NotificationChannel[]>(CHANNELS_BASE);
  return data;
}

export async function createChannel(
  input: CreateNotificationChannelInput,
): Promise<NotificationChannel> {
  const { data } = await apiClient.post<NotificationChannel>(CHANNELS_BASE, input);
  return data;
}

export async function updateChannel(
  id: string,
  input: UpdateNotificationChannelInput,
): Promise<NotificationChannel> {
  const { data } = await apiClient.put<NotificationChannel>(
    `${CHANNELS_BASE}/${id}`,
    input,
  );
  return data;
}

export async function testChannel(
  id: string,
  input: TestNotificationChannelInput = {},
): Promise<TestNotificationResult> {
  const { data } = await apiClient.post<TestNotificationResult>(
    `${CHANNELS_BASE}/${id}/test`,
    input,
  );
  return data;
}

export async function deleteChannel(id: string): Promise<void> {
  await apiClient.delete(`${CHANNELS_BASE}/${id}`);
}

// ── AI configs ────────────────────────────────────────────────────────────────

export async function listAiConfigs(): Promise<AiConfig[]> {
  const { data } = await apiClient.get<AiConfig[]>(AI_CONFIGS_BASE);
  return data;
}

export async function getAiConfig(id: string): Promise<AiConfig> {
  const { data } = await apiClient.get<AiConfig>(`${AI_CONFIGS_BASE}/${id}`);
  return data;
}

export async function createAiConfig(input: CreateAiConfigInput): Promise<AiConfig> {
  const { data } = await apiClient.post<AiConfig>(AI_CONFIGS_BASE, input);
  return data;
}

export async function updateAiConfig(id: string, input: UpdateAiConfigInput): Promise<AiConfig> {
  const { data } = await apiClient.put<AiConfig>(`${AI_CONFIGS_BASE}/${id}`, input);
  return data;
}

export async function deleteAiConfig(id: string): Promise<void> {
  await apiClient.delete(`${AI_CONFIGS_BASE}/${id}`);
}

export async function testAiConfig(id: string): Promise<TestAiConfigResult> {
  const { data } = await apiClient.post<TestAiConfigResult>(`${AI_CONFIGS_BASE}/${id}/test`);
  return data;
}

// ── SAML config (Enterprise only) ────────────────────────────────────────────

export async function getSamlConfig(): Promise<SamlConfig> {
  const { data } = await apiClient.get<SamlConfig>(SAML_CONFIG_BASE);
  return data;
}

export async function updateSamlConfig(input: UpdateSamlConfigInput): Promise<SamlConfig> {
  const { data } = await apiClient.put<SamlConfig>(SAML_CONFIG_BASE, input);
  return data;
}

// ── OAuth2 config ────────────────────────────────────────────────────────────

export async function listOAuth2Configs(): Promise<OAuth2Config[]> {
  const { data } = await apiClient.get<OAuth2Config[]>(OAUTH2_CONFIG_BASE);
  return data;
}

export async function getOAuth2Config(provider: OAuth2Provider): Promise<OAuth2Config> {
  const { data } = await apiClient.get<OAuth2Config>(`${OAUTH2_CONFIG_BASE}/${provider}`);
  return data;
}

export async function updateOAuth2Config(
  provider: OAuth2Provider,
  input: UpdateOAuth2ConfigInput,
): Promise<OAuth2Config> {
  const { data } = await apiClient.put<OAuth2Config>(`${OAUTH2_CONFIG_BASE}/${provider}`, input);
  return data;
}

export async function deleteOAuth2Config(provider: OAuth2Provider): Promise<void> {
  await apiClient.delete(`${OAUTH2_CONFIG_BASE}/${provider}`);
}

// ── Setup progress ───────────────────────────────────────────────────────────

export async function getSetupProgress(): Promise<SetupProgress> {
  const { data } = await apiClient.get<SetupProgress>(SETUP_PROGRESS_BASE);
  return data;
}

// ── System SMTP ───────────────────────────────────────────────────────────────

export async function getSystemSmtp(): Promise<SystemSmtpConfig | null> {
  try {
    const { data } = await apiClient.get<SystemSmtpConfig>(SYSTEM_SMTP_BASE);
    return data;
  } catch (err) {
    if (
      typeof err === 'object' &&
      err !== null &&
      'response' in err &&
      (err as { response?: { status?: number } }).response?.status === 404
    ) {
      return null;
    }
    throw err;
  }
}

export async function updateSystemSmtp(
  input: UpdateSystemSmtpInput,
): Promise<SystemSmtpConfig> {
  const { data } = await apiClient.put<SystemSmtpConfig>(SYSTEM_SMTP_BASE, input);
  return data;
}

export async function deleteSystemSmtp(): Promise<void> {
  await apiClient.delete(SYSTEM_SMTP_BASE);
}

export async function testSystemSmtp(input: TestSystemSmtpInput = {}): Promise<void> {
  await apiClient.post(`${SYSTEM_SMTP_BASE}/test`, input);
}

// ── User invitations ─────────────────────────────────────────────────────────

export async function listInvitations(
  filters: { page?: number; size?: number; sort?: string } = {},
): Promise<UserInvitationPage> {
  const params: Record<string, string | number> = {};
  if (typeof filters.page === 'number') params.page = filters.page;
  if (typeof filters.size === 'number') params.size = filters.size;
  if (filters.sort) params.sort = filters.sort;
  const { data } = await apiClient.get<UserInvitationPage>(INVITATIONS_BASE, { params });
  return data;
}

export async function createInvitation(input: InviteUserInput): Promise<UserInvitation> {
  const { data } = await apiClient.post<UserInvitation>(INVITATIONS_BASE, input);
  return data;
}

export async function resendInvitation(id: string): Promise<UserInvitation> {
  const { data } = await apiClient.post<UserInvitation>(`${INVITATIONS_BASE}/${id}/resend`);
  return data;
}

export async function revokeInvitation(id: string): Promise<void> {
  await apiClient.delete(`${INVITATIONS_BASE}/${id}`);
}

// ── AI analysis history dashboard ─────────────────────────────────────────────

export async function fetchAiAnalysisStats(
  filters: AiAnalysisStatsFilters = {},
): Promise<AiAnalysisStats> {
  const params: Record<string, string> = {};
  if (filters.from) params.from = filters.from;
  if (filters.to) params.to = filters.to;
  if (filters.datasource_id) params.datasourceId = filters.datasource_id;
  const { data } = await apiClient.get<AiAnalysisStats>(`${AI_ANALYSES_BASE}/stats`, {
    params,
  });
  return data;
}
