import { useCallback } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  askHelpChat,
  createHelpChatSession,
  fetchHelpChatAvailability,
  fetchHelpChatSession,
  helpChatKeys,
} from '@/api/helpChat';
import { usePreferencesStore } from '@/store/preferencesStore';
import type { HelpChatConversation, HelpChatMessage, HelpChatTurn } from '@/types/api';

/** Availability changes only when an admin edits the settings page — five minutes is plenty. */
const AVAILABILITY_STALE_TIME = 5 * 60_000;

/** Marks the placeholder message the optimistic append adds; never a server id. */
const OPTIMISTIC_ID_PREFIX = 'optimistic-';

interface AskVariables {
  sessionId: string;
  question: string;
  routeName?: string;
}

interface AskContext {
  previous: HelpChatConversation | undefined;
  sessionId: string;
}

/**
 * The help chat panel's server state (AF-906).
 *
 * The conversation id is UI state — which transcript the drawer currently shows — and lives in
 * `preferencesStore`; every message in it is server state and lives in TanStack Query. A session is
 * created lazily on the first question rather than when the drawer opens, so browsing the panel
 * leaves no empty transcript behind.
 */
export function useHelpChat() {
  const queryClient = useQueryClient();
  const sessionId = usePreferencesStore((s) => s.activeHelpSessionId);
  const setSessionId = usePreferencesStore((s) => s.setActiveHelpSessionId);

  const availabilityQuery = useQuery({
    queryKey: helpChatKeys.availability(),
    queryFn: fetchHelpChatAvailability,
    staleTime: AVAILABILITY_STALE_TIME,
  });

  const conversationQuery = useQuery({
    queryKey: helpChatKeys.session(sessionId ?? ''),
    queryFn: () => fetchHelpChatSession(sessionId as string),
    enabled: sessionId !== null,
  });

  const askMutation = useMutation<HelpChatTurn, unknown, AskVariables, AskContext>({
    mutationFn: (variables) =>
      askHelpChat(variables.sessionId, {
        question: variables.question,
        ...(variables.routeName === undefined ? {} : { route_name: variables.routeName }),
      }),
    onMutate: async (variables) => {
      const key = helpChatKeys.session(variables.sessionId);
      await queryClient.cancelQueries({ queryKey: key });
      const previous = queryClient.getQueryData<HelpChatConversation>(key);
      queryClient.setQueryData<HelpChatConversation>(key, (current) =>
        current === undefined
          ? current
          : { ...current, messages: [...current.messages, optimisticMessage(variables.question)] },
      );
      return { previous, sessionId: variables.sessionId };
    },
    onSuccess: (turn, variables) => {
      // The turn carries both stored messages precisely so a client can append the exchange
      // without re-reading the conversation (`docs/04-api-spec.md` → POST /help-chat/sessions/
      // {id}/messages), so this is the authoritative write and no conversation refetch follows
      // it — one fewer round-trip before the user sees an answer they already waited on.
      queryClient.setQueryData<HelpChatConversation>(
        helpChatKeys.session(variables.sessionId),
        (current) => ({
          session: turn.session,
          messages: [
            ...(current?.messages ?? []).filter((m) => !m.id.startsWith(OPTIMISTIC_ID_PREFIX)),
            turn.user_message,
            turn.assistant_message,
          ],
        }),
      );
    },
    onError: (_error, _variables, context) => {
      // Put the transcript back: the question the user typed stays in the composer, so leaving a
      // greyed-out message behind would show it twice.
      if (context !== undefined) {
        queryClient.setQueryData(helpChatKeys.session(context.sessionId), context.previous);
      }
    },
  });

  const { mutateAsync: ask } = askMutation;

  /**
   * Asks a question, opening a conversation first when there is not one yet. Rejects with the
   * Axios error so the caller can surface the server's own `detail`.
   */
  const askQuestion = useCallback(
    async (question: string, routeName?: string): Promise<HelpChatTurn> => {
      let id = sessionId;
      if (id === null) {
        const created = await createHelpChatSession();
        id = created.id;
        setSessionId(id);
        queryClient.setQueryData<HelpChatConversation>(helpChatKeys.session(id), {
          session: created,
          messages: [],
        });
      }
      return ask({ sessionId: id, question, ...(routeName === undefined ? {} : { routeName }) });
    },
    [ask, queryClient, sessionId, setSessionId],
  );

  /** Forgets the current transcript without deleting it — the next question opens a new one. */
  const startNewConversation = useCallback(() => setSessionId(null), [setSessionId]);

  return {
    availability: availabilityQuery.data,
    availabilityLoading: availabilityQuery.isLoading,
    conversation: conversationQuery.data,
    conversationLoading: sessionId !== null && conversationQuery.isLoading,
    messages: conversationQuery.data?.messages ?? [],
    askQuestion,
    isAsking: askMutation.isPending,
    startNewConversation,
    hasConversation: sessionId !== null,
  };
}

/**
 * The user's own question, echoed straight back into the transcript while the answer is in flight.
 * Its id is a client-side placeholder that never reaches the server; the invalidation on settle
 * replaces it with the stored row.
 */
function optimisticMessage(question: string): HelpChatMessage {
  return {
    id: `${OPTIMISTIC_ID_PREFIX}${Date.now()}`,
    role: 'USER',
    content: question,
    citations: [],
    created_at: new Date().toISOString(),
  };
}
