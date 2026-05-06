import { AUDIT, CHANNELS, USERS } from '@/mocks/data';
import { jittered } from '@/mocks/delay';
import type { AuditEvent, NotificationChannel, User } from '@/types/api';

export async function listUsers(): Promise<User[]> {
  await jittered();
  return USERS;
}

export async function listAuditEvents(): Promise<AuditEvent[]> {
  await jittered();
  return AUDIT;
}

export async function listChannels(): Promise<NotificationChannel[]> {
  await jittered();
  return CHANNELS;
}
