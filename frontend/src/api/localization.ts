import { apiClient } from './client';
import type {
  LocalizationConfig,
  MeLocalization,
  UpdateLocalizationConfigInput,
} from '@/types/api';

const ADMIN_BASE = '/api/v1/admin/localization-config';
const ME_BASE = '/api/v1/me/localization';

export const localizationKeys = {
  all: ['localization'] as const,
  admin: () => ['localization', 'admin'] as const,
  me: () => ['localization', 'me'] as const,
};

export async function getAdminLocalizationConfig(): Promise<LocalizationConfig> {
  const { data } = await apiClient.get<LocalizationConfig>(ADMIN_BASE);
  return data;
}

export async function updateAdminLocalizationConfig(
  input: UpdateLocalizationConfigInput,
): Promise<LocalizationConfig> {
  const { data } = await apiClient.put<LocalizationConfig>(ADMIN_BASE, input);
  return data;
}

export async function getMeLocalization(): Promise<MeLocalization> {
  const { data } = await apiClient.get<MeLocalization>(ME_BASE);
  return data;
}

export async function updateMeLocalization(language: string): Promise<MeLocalization> {
  const { data } = await apiClient.put<MeLocalization>(ME_BASE, { language });
  return data;
}
