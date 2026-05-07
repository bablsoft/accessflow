import { apiClient } from './client';
import type {
  AiConfig,
  AuditLogFilters,
  AuditLogPage,
  CreateNotificationChannelInput,
  CreateUserInput,
  NotificationChannel,
  SamlConfig,
  TestAiConfigResult,
  TestNotificationChannelInput,
  TestNotificationResult,
  UpdateAiConfigInput,
  UpdateNotificationChannelInput,
  UpdateSamlConfigInput,
  UpdateUserInput,
  User,
  UserListFilters,
  UserPage,
} from '@/types/api';

const USERS_BASE = '/api/v1/admin/users';
const AUDIT_BASE = '/api/v1/admin/audit-log';
const CHANNELS_BASE = '/api/v1/admin/notification-channels';
const AI_CONFIG_BASE = '/api/v1/admin/ai-config';
const SAML_CONFIG_BASE = '/api/v1/admin/saml-config';

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
};

export const notificationChannelKeys = {
  all: ['notificationChannels'] as const,
  lists: () => ['notificationChannels', 'list'] as const,
  details: () => ['notificationChannels', 'detail'] as const,
  detail: (id: string) => ['notificationChannels', 'detail', id] as const,
};

export const aiConfigKeys = {
  all: ['aiConfig'] as const,
  current: () => ['aiConfig', 'current'] as const,
};

export const samlConfigKeys = {
  all: ['samlConfig'] as const,
  current: () => ['samlConfig', 'current'] as const,
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

// ── AI config ─────────────────────────────────────────────────────────────────

export async function getAiConfig(): Promise<AiConfig> {
  const { data } = await apiClient.get<AiConfig>(AI_CONFIG_BASE);
  return data;
}

export async function updateAiConfig(input: UpdateAiConfigInput): Promise<AiConfig> {
  const { data } = await apiClient.put<AiConfig>(AI_CONFIG_BASE, input);
  return data;
}

export async function testAiConfig(): Promise<TestAiConfigResult> {
  const { data } = await apiClient.post<TestAiConfigResult>(`${AI_CONFIG_BASE}/test`);
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
