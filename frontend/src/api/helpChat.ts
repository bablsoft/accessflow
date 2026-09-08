import { apiClient } from './client';
import type {
  AskHelpChatInput,
  HelpAgentAvailability,
  HelpChatConversation,
  HelpChatSession,
  HelpChatTurn,
} from '@/types/api';

const BASE = '/api/v1/help-chat';

export const helpChatKeys = {
  all: ['helpChat'] as const,
  availability: () => ['helpChat', 'availability'] as const,
  session: (id: string) => ['helpChat', 'session', id] as const,
};

/**
 * Whether the assistant can answer for the caller's organization. Readable by every signed-in
 * user, and never fails because the agent is off — that is the answer.
 */
export async function fetchHelpChatAvailability(): Promise<HelpAgentAvailability> {
  const { data } = await apiClient.get<HelpAgentAvailability>(`${BASE}/availability`);
  return data;
}

/** Starts an empty conversation. 409 when the agent is off or unbound. */
export async function createHelpChatSession(): Promise<HelpChatSession> {
  const { data } = await apiClient.post<HelpChatSession>(`${BASE}/sessions`);
  return data;
}

export async function fetchHelpChatSession(id: string): Promise<HelpChatConversation> {
  const { data } = await apiClient.get<HelpChatConversation>(`${BASE}/sessions/${id}`);
  return data;
}

/**
 * Asks a question and returns the completed turn. `route_name` must be a mapped **label**, never
 * a pathname — see `src/components/help/routeLabel.ts`.
 */
export async function askHelpChat(id: string, input: AskHelpChatInput): Promise<HelpChatTurn> {
  const { data } = await apiClient.post<HelpChatTurn>(`${BASE}/sessions/${id}/messages`, input);
  return data;
}
