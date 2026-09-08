import { useState } from 'react';
import { Button, Input } from 'antd';
import { SendOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';

/**
 * The question ceiling enforced by `AskHelpChatRequest.question` (`@Size(max = 10000)`).
 *
 * It is deliberately the *highest* value `help_agent_config.max_question_chars` can be set to
 * rather than its 2,000 default: the server stores the question as typed and truncates only what
 * reaches the model, so a client bound to the default would refuse text an organization that
 * raised the setting has configured for.
 */
export const MAX_QUESTION_CHARS = 10000;

interface Props {
  onSend: (question: string) => void;
  disabled: boolean;
  sending: boolean;
}

export function HelpChatComposer({ onSend, disabled, sending }: Props) {
  const { t } = useTranslation();
  const [value, setValue] = useState('');

  const trimmed = value.trim();
  const canSend = trimmed.length > 0 && trimmed.length <= MAX_QUESTION_CHARS && !disabled && !sending;

  const send = () => {
    if (!canSend) return;
    onSend(trimmed);
    setValue('');
  };

  return (
    <div className="af-help-composer">
      <Input.TextArea
        value={value}
        onChange={(e) => setValue(e.target.value)}
        onPressEnter={(e) => {
          if (e.shiftKey) return;
          e.preventDefault();
          send();
        }}
        autoSize={{ minRows: 2, maxRows: 6 }}
        maxLength={MAX_QUESTION_CHARS}
        disabled={disabled}
        aria-label={t('help_chat.question_label')}
        placeholder={t('help_chat.question_placeholder')}
      />
      <div className="af-help-composer-actions">
        <span className="af-help-composer-hint">{t('help_chat.enter_hint')}</span>
        <Button
          type="primary"
          icon={<SendOutlined />}
          onClick={send}
          disabled={!canSend}
          loading={sending}
        >
          {t('help_chat.send')}
        </Button>
      </div>
    </div>
  );
}

export default HelpChatComposer;
