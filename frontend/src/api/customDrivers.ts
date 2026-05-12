import { apiClient } from './client';
import type {
  CustomDriver,
  CustomDriverListResponse,
  CustomDriverUploadInput,
} from '@/types/api';

const BASE = '/api/v1/datasources/drivers';

export const customDriverKeys = {
  all: ['custom-drivers'] as const,
  lists: () => ['custom-drivers', 'list'] as const,
  detail: (id: string) => ['custom-drivers', 'detail', id] as const,
};

export async function listCustomDrivers(): Promise<CustomDriver[]> {
  const { data } = await apiClient.get<CustomDriverListResponse>(BASE);
  return data.drivers;
}

export async function uploadCustomDriver(
  input: CustomDriverUploadInput,
): Promise<CustomDriver> {
  const form = new FormData();
  form.append('jar', input.jar);
  form.append('vendor_name', input.vendor_name);
  form.append('target_db_type', input.target_db_type);
  form.append('driver_class', input.driver_class);
  form.append('expected_sha256', input.expected_sha256.toLowerCase());
  const { data } = await apiClient.post<CustomDriver>(BASE, form);
  return data;
}

export async function deleteCustomDriver(id: string): Promise<void> {
  await apiClient.delete(`${BASE}/${id}`);
}
