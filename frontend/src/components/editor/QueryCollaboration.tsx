import { useEffect, useState } from 'react';
import { Alert, App, Button } from 'antd';
import { SaveOutlined } from '@ant-design/icons';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { QueryCollabProvider } from '@/realtime/collabProvider';
import { queryKeys, submitQuery } from '@/api/queries';
import { showApiError } from '@/utils/showApiError';
import { collaborationErrorMessage } from '@/utils/apiErrors';
import { userDisplay } from '@/utils/userDisplay';
import { SqlBlock } from '@/components/common/SqlBlock';
import type { CollabMember } from '@/types/ws';
import type { QueryDetail } from '@/types/api';
import { CollaborativeSqlEditor } from './CollaborativeSqlEditor';
import { CommentsPanel } from './CommentsPanel';
import { PresenceBar } from './PresenceBar';

interface QueryCollaborationProps {
  query: QueryDetail;
  currentUser: { id: string; display_name: string; email: string };
}

/**
 * The live co-authoring experience for a query in review: presence avatars, a CRDT-bound editor
 * with remote cursors, inline comment threads, and a "save as draft" action that re-enters the
 * workflow through the normal submit path (never silently mutating the query under review).
 */
export function QueryCollaboration({ query, currentUser }: QueryCollaborationProps) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [members, setMembers] = useState<CollabMember[]>([]);
  const [denied, setDenied] = useState(false);
  const [selection, setSelection] = useState<{ start: number; end: number } | null>(null);

  const [provider] = useState(
    () =>
      new QueryCollabProvider({
        queryId: query.id,
        user: {
          id: currentUser.id,
          name: userDisplay(currentUser.display_name, currentUser.email),
        },
        initialText: query.sql_text,
        onPresence: setMembers,
        onDenied: () => setDenied(true),
      }),
  );

  useEffect(() => () => provider.destroy(), [provider]);

  const saveMutation = useMutation({
    mutationFn: () =>
      submitQuery({
        datasource_id: query.datasource.id,
        sql: provider.text.toString(),
        justification: query.justification ?? '',
        submission_reason: 'USER_SUBMITTED',
      }),
    onSuccess: (created) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.lists() });
      message.success(t('collab.save_success', { id: created.id }));
      navigate(`/queries/${created.id}`);
    },
    onError: (err) => showApiError(message, err, collaborationErrorMessage),
  });

  if (denied) {
    return (
      <div>
        <Alert
          type="warning"
          showIcon
          message={t('collab.denied_title')}
          description={t('collab.denied_body')}
          style={{ marginBottom: 12 }}
        />
        <div style={{ padding: 14 }}>
          <SqlBlock sql={query.sql_text} />
        </div>
      </div>
    );
  }

  return (
    <div className="qd-collab">
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          padding: '10px 14px',
          gap: 12,
        }}
      >
        <PresenceBar members={members} />
        <Button
          type="primary"
          icon={<SaveOutlined />}
          loading={saveMutation.isPending}
          onClick={() => saveMutation.mutate()}
          title={t('collab.save_draft_tooltip')}
        >
          {t('collab.save_draft')}
        </Button>
      </div>
      <div style={{ padding: '0 14px 14px' }}>
        <CollaborativeSqlEditor
          provider={provider}
          dbType={query.db_type}
          onSelectionLines={(start, end) => setSelection({ start, end })}
        />
      </div>
      <div style={{ padding: '0 14px 14px' }}>
        <CommentsPanel queryId={query.id} selection={selection} />
      </div>
    </div>
  );
}
