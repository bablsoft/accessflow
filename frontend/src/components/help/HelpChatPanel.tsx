import { App, Alert, Button, Drawer, Space, Tooltip } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { useLocation } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { HelpChatComposer } from './HelpChatComposer';
import { HelpChatMessageList } from './HelpChatMessageList';
import { routeLabel } from './routeLabel';
import { useHelpChat } from '@/hooks/useHelpChat';
import { apiErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import './help-chat.css';

interface Props {
  open: boolean;
  onClose: () => void;
}

/**
 * The help chat drawer (AF-906).
 *
 * `mask={false}` on purpose: the drawer sits over the app shell alongside the `SetupProgressWidget`
 * banner, and a mask would make the rest of the page unusable while a question is being asked —
 * which is exactly when a user wants to look at the screen they are asking about.
 */
export function HelpChatPanel({ open, onClose }: Props) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const location = useLocation();
  const {
    availability,
    conversation,
    conversationLoading,
    messages,
    askQuestion,
    isAsking,
    startNewConversation,
    hasConversation,
  } = useHelpChat();

  const send = (question: string) => {
    // A mapped label for the current screen — never the pathname, which carries request and
    // datasource ids (epic #899 decision 3).
    void askQuestion(question, routeLabel(location.pathname, t)).catch((err: unknown) => {
      showApiError(message, err, (e) => apiErrorMessage(e, () => t('help_chat.ask_error')));
    });
  };

  return (
    <Drawer
      open={open}
      onClose={onClose}
      placement="right"
      mask={false}
      size={420}
      title={t('help_chat.title')}
      classNames={{ body: 'af-help-panel' }}
      styles={{ body: { padding: 0 } }}
      extra={
        <Space>
          <Tooltip title={t('help_chat.new_conversation')}>
            <Button
              size="small"
              icon={<PlusOutlined />}
              onClick={startNewConversation}
              disabled={!hasConversation || isAsking}
              aria-label={t('help_chat.new_conversation')}
            />
          </Tooltip>
        </Space>
      }
    >
      {availability?.retrieval_active === false ? (
        <Alert
          type="info"
          showIcon
          banner
          title={t('help_chat.quick_reference_mode')}
          description={t('help_chat.quick_reference_mode_help')}
        />
      ) : null}
      <HelpChatMessageList
        messages={messages}
        loading={conversationLoading && conversation === undefined}
        answering={isAsking}
        emptyHint={t('help_chat.empty_hint')}
      />
      <HelpChatComposer onSend={send} disabled={!availability?.enabled} sending={isAsking} />
    </Drawer>
  );
}

export default HelpChatPanel;
