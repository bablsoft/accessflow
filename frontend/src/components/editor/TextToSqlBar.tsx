import { useState } from 'react';
import { App, Button, Input } from 'antd';
import { LoadingOutlined, RobotOutlined } from '@ant-design/icons';
import { useMutation } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { generateSql } from '@/api/queries';
import { textToSqlErrorMessage } from '@/utils/apiErrors';

interface TextToSqlBarProps {
  datasourceId: string;
  onGenerated: (sql: string, syntax?: string) => void;
}

export function TextToSqlBar({ datasourceId, onGenerated }: TextToSqlBarProps) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const [prompt, setPrompt] = useState('');

  const mutation = useMutation({
    mutationFn: () => generateSql({ datasource_id: datasourceId, prompt: prompt.trim() }),
    onSuccess: (res) => {
      onGenerated(res.sql, res.syntax);
      message.success(t('editor.text_to_sql.success'));
    },
    onError: (err) => {
      message.error(textToSqlErrorMessage(err));
    },
  });

  const canGenerate = prompt.trim().length > 0 && !mutation.isPending;

  return (
    <div>
      <label
        className="muted u-block u-text-xs u-fw-500 u-mb-1"
      >
        {t('editor.text_to_sql.label')}{' '}
        <span className="muted u-fw-400">
          {t('editor.text_to_sql.hint')}
        </span>
      </label>
      <div className="u-flex u-gap-2 u-items-start">
        <Input.TextArea
          value={prompt}
          onChange={(e) => setPrompt(e.target.value)}
          placeholder={t('editor.text_to_sql.placeholder')}
          autoSize={{ minRows: 1, maxRows: 3 }}
          aria-label={t('editor.text_to_sql.label')}
          onPressEnter={(e) => {
            if (!e.shiftKey && canGenerate) {
              e.preventDefault();
              mutation.mutate();
            }
          }}
        />
        <Button
          icon={mutation.isPending ? <LoadingOutlined /> : <RobotOutlined />}
          disabled={!canGenerate}
          onClick={() => mutation.mutate()}
        >
          {mutation.isPending
            ? t('editor.text_to_sql.generating')
            : t('editor.text_to_sql.generate_button')}
        </Button>
      </div>
    </div>
  );
}
