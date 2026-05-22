import type { TFunction } from 'i18next';
import type {
  AiProvider,
  AuthProvider,
  ChannelType,
  DbType,
  InvitationStatus,
  OAuth2Provider,
  QueryStatus,
  QueryType,
  RiskLevel,
  Role,
  SslMode,
} from '@/types/api';

export const queryStatusLabel = (t: TFunction, v: QueryStatus): string =>
  t(`enums.query_status.${v}` as const);

export const queryTypeLabel = (t: TFunction, v: QueryType): string =>
  t(`enums.query_type.${v}` as const);

export const riskLevelLabel = (t: TFunction, v: RiskLevel): string =>
  t(`enums.risk_level.${v}` as const);

export const roleLabel = (t: TFunction, v: Role): string =>
  t(`enums.role.${v}` as const);

export const dbTypeLabel = (t: TFunction, v: DbType): string =>
  t(`enums.db_type.${v}` as const);

export const sslModeLabel = (t: TFunction, v: SslMode): string =>
  t(`enums.ssl_mode.${v}` as const);

export const channelTypeLabel = (t: TFunction, v: ChannelType): string =>
  t(`enums.channel_type.${v}` as const);

export const aiProviderLabel = (t: TFunction, v: AiProvider): string =>
  t(`enums.ai_provider.${v}` as const);

export const authProviderLabel = (t: TFunction, v: AuthProvider): string =>
  t(`enums.auth_provider.${v}` as const);

export const oauth2ProviderLabel = (t: TFunction, v: OAuth2Provider): string =>
  t(`enums.oauth2_provider.${v}` as const);

export const invitationStatusLabel = (t: TFunction, v: InvitationStatus): string =>
  t(`enums.invitation_status.${v}` as const);

export interface EnumOption<V extends string> {
  value: V;
  label: string;
}

export function enumOptions<V extends string>(
  values: readonly V[],
  label: (t: TFunction, v: V) => string,
  t: TFunction,
): EnumOption<V>[] {
  return values.map((value) => ({ value, label: label(t, value) }));
}
