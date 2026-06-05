import { describe, expect, it } from 'vitest';
import type { TFunction } from 'i18next';
import i18n from '@/i18n';
import {
  accessGrantStatusLabel,
  aiProviderLabel,
  authProviderLabel,
  channelTypeLabel,
  dbTypeLabel,
  enumOptions,
  invitationStatusLabel,
  maskingStrategyLabel,
  MASKING_STRATEGIES,
  EMBEDDING_PROVIDERS,
  oauth2ProviderLabel,
  queryStatusLabel,
  queryTypeLabel,
  RAG_STORE_TYPES,
  ragStoreTypeLabel,
  riskLevelLabel,
  roleLabel,
  sslModeLabel,
} from '../enumLabels';
import type {
  AiProvider,
  AuthProvider,
  ChannelType,
  DbType,
  InvitationStatus,
  MaskingStrategy,
  OAuth2Provider,
  QueryStatus,
  QueryType,
  RiskLevel,
  Role,
  SslMode,
} from '@/types/api';

// Stub TFunction that just echoes the key. Lets us assert each helper builds
// the right key shape without depending on the loaded English JSON.
const stubT = ((key: string) => key) as unknown as TFunction;
const t = i18n.t.bind(i18n) as unknown as TFunction;

describe('enumLabels (key shape)', () => {
  it('queryStatusLabel builds enums.query_status.<value>', () => {
    expect(queryStatusLabel(stubT, 'PENDING_REVIEW')).toBe('enums.query_status.PENDING_REVIEW');
  });
  it('accessGrantStatusLabel builds enums.access_grant_status.<value>', () => {
    expect(accessGrantStatusLabel(stubT, 'APPROVED')).toBe('enums.access_grant_status.APPROVED');
  });
  it('queryTypeLabel builds enums.query_type.<value>', () => {
    expect(queryTypeLabel(stubT, 'SELECT')).toBe('enums.query_type.SELECT');
  });
  it('riskLevelLabel builds enums.risk_level.<value>', () => {
    expect(riskLevelLabel(stubT, 'HIGH')).toBe('enums.risk_level.HIGH');
  });
  it('roleLabel builds enums.role.<value>', () => {
    expect(roleLabel(stubT, 'ADMIN')).toBe('enums.role.ADMIN');
  });
  it('dbTypeLabel builds enums.db_type.<value>', () => {
    expect(dbTypeLabel(stubT, 'POSTGRESQL')).toBe('enums.db_type.POSTGRESQL');
  });
  it('sslModeLabel builds enums.ssl_mode.<value>', () => {
    expect(sslModeLabel(stubT, 'VERIFY_CA')).toBe('enums.ssl_mode.VERIFY_CA');
  });
  it('channelTypeLabel builds enums.channel_type.<value>', () => {
    expect(channelTypeLabel(stubT, 'MS_TEAMS')).toBe('enums.channel_type.MS_TEAMS');
  });
  it('aiProviderLabel builds enums.ai_provider.<value>', () => {
    expect(aiProviderLabel(stubT, 'OPENAI')).toBe('enums.ai_provider.OPENAI');
  });
  it('authProviderLabel builds enums.auth_provider.<value>', () => {
    expect(authProviderLabel(stubT, 'OAUTH2')).toBe('enums.auth_provider.OAUTH2');
  });
  it('oauth2ProviderLabel builds enums.oauth2_provider.<value>', () => {
    expect(oauth2ProviderLabel(stubT, 'GOOGLE')).toBe('enums.oauth2_provider.GOOGLE');
  });
  it('invitationStatusLabel builds enums.invitation_status.<value>', () => {
    expect(invitationStatusLabel(stubT, 'PENDING')).toBe('enums.invitation_status.PENDING');
  });
  it('maskingStrategyLabel builds enums.masking_strategy.<value>', () => {
    expect(maskingStrategyLabel(stubT, 'PARTIAL')).toBe('enums.masking_strategy.PARTIAL');
  });
});

describe('enumLabels (English text round-trip)', () => {
  const statuses: QueryStatus[] = [
    'PENDING_AI', 'PENDING_REVIEW', 'APPROVED', 'EXECUTED',
    'REJECTED', 'TIMED_OUT', 'FAILED', 'CANCELLED',
  ];
  const types: QueryType[] = ['SELECT', 'INSERT', 'UPDATE', 'DELETE', 'DDL'];
  const risks: RiskLevel[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];
  const roles: Role[] = ['ADMIN', 'REVIEWER', 'ANALYST', 'READONLY'];
  const dbs: DbType[] = ['POSTGRESQL', 'MYSQL', 'MARIADB', 'ORACLE', 'MSSQL', 'CUSTOM'];
  const ssl: SslMode[] = ['DISABLE', 'REQUIRE', 'VERIFY_CA', 'VERIFY_FULL'];
  const channels: ChannelType[] = ['EMAIL', 'SLACK', 'WEBHOOK', 'DISCORD', 'TELEGRAM', 'MS_TEAMS'];
  const ai: AiProvider[] = ['OPENAI', 'ANTHROPIC', 'OLLAMA', 'OPENAI_COMPATIBLE', 'HUGGING_FACE'];
  const auth: AuthProvider[] = ['LOCAL', 'SAML', 'OAUTH2'];
  const oauth2: OAuth2Provider[] = ['GOOGLE', 'GITHUB', 'MICROSOFT', 'GITLAB', 'OIDC'];
  const invites: InvitationStatus[] = ['PENDING', 'ACCEPTED', 'REVOKED', 'EXPIRED'];
  const maskingStrategies: MaskingStrategy[] = [...MASKING_STRATEGIES];

  it('returns a non-empty resolved string for every QueryStatus', () => {
    for (const v of statuses) expect(queryStatusLabel(t, v)).not.toBe(`enums.query_status.${v}`);
  });
  it('returns a non-empty resolved string for every QueryType', () => {
    for (const v of types) expect(queryTypeLabel(t, v)).not.toBe(`enums.query_type.${v}`);
  });
  it('returns a non-empty resolved string for every RiskLevel', () => {
    for (const v of risks) expect(riskLevelLabel(t, v)).not.toBe(`enums.risk_level.${v}`);
  });
  it('returns a non-empty resolved string for every Role', () => {
    for (const v of roles) expect(roleLabel(t, v)).not.toBe(`enums.role.${v}`);
  });
  it('returns a non-empty resolved string for every DbType', () => {
    for (const v of dbs) expect(dbTypeLabel(t, v)).not.toBe(`enums.db_type.${v}`);
  });
  it('returns a non-empty resolved string for every SslMode', () => {
    for (const v of ssl) expect(sslModeLabel(t, v)).not.toBe(`enums.ssl_mode.${v}`);
  });
  it('returns a non-empty resolved string for every ChannelType', () => {
    for (const v of channels) expect(channelTypeLabel(t, v)).not.toBe(`enums.channel_type.${v}`);
  });
  it('returns a non-empty resolved string for every AiProvider', () => {
    for (const v of ai) expect(aiProviderLabel(t, v)).not.toBe(`enums.ai_provider.${v}`);
  });
  it('returns a non-empty resolved string for every AuthProvider', () => {
    for (const v of auth) expect(authProviderLabel(t, v)).not.toBe(`enums.auth_provider.${v}`);
  });
  it('returns a non-empty resolved string for every OAuth2Provider', () => {
    for (const v of oauth2) expect(oauth2ProviderLabel(t, v)).not.toBe(`enums.oauth2_provider.${v}`);
  });
  it('returns a non-empty resolved string for every InvitationStatus', () => {
    for (const v of invites) expect(invitationStatusLabel(t, v)).not.toBe(`enums.invitation_status.${v}`);
  });
  it('returns a non-empty resolved string for every MaskingStrategy', () => {
    for (const v of maskingStrategies) {
      expect(maskingStrategyLabel(t, v)).not.toBe(`enums.masking_strategy.${v}`);
    }
  });

  it('renders well-known English labels', () => {
    expect(queryStatusLabel(t, 'PENDING_REVIEW')).toBe('Pending review');
    expect(queryStatusLabel(t, 'TIMED_OUT')).toBe('Timed out');
    expect(roleLabel(t, 'READONLY')).toBe('Read-only');
    expect(dbTypeLabel(t, 'MSSQL')).toBe('Microsoft SQL Server');
    expect(channelTypeLabel(t, 'MS_TEAMS')).toBe('Microsoft Teams');
    expect(sslModeLabel(t, 'VERIFY_CA')).toBe('Verify CA');
    expect(aiProviderLabel(t, 'HUGGING_FACE')).toBe('Hugging Face');
  });
});

describe('ragStoreTypeLabel', () => {
  it('translates each RAG store type', () => {
    expect(ragStoreTypeLabel(t, 'PGVECTOR')).toBe('In-app (pgvector)');
    expect(ragStoreTypeLabel(t, 'QDRANT')).toBe('Qdrant');
  });

  it('exposes the store types and embedding providers (Anthropic excluded)', () => {
    expect(RAG_STORE_TYPES).toEqual(['PGVECTOR', 'QDRANT']);
    expect(EMBEDDING_PROVIDERS).not.toContain('ANTHROPIC');
    expect(EMBEDDING_PROVIDERS).toContain('OPENAI');
  });
});

describe('enumOptions', () => {
  it('preserves order and pairs each value with its translated label', () => {
    const values = ['ADMIN', 'REVIEWER'] as const;
    const options = enumOptions(values, roleLabel, t);
    expect(options).toEqual([
      { value: 'ADMIN', label: 'Admin' },
      { value: 'REVIEWER', label: 'Reviewer' },
    ]);
  });

  it('returns an empty array for an empty input', () => {
    expect(enumOptions([] as readonly Role[], roleLabel, t)).toEqual([]);
  });
});
