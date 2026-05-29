import { useState } from 'react';
import { Alert, App, Button, Popconfirm, Skeleton, Space, Tag, Typography } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import {
  createSlackLinkCode,
  getSlackLinkStatus,
  slackLinkKeys,
  unlinkSlack,
} from '@/api/slack';
import type { SlackLinkCode } from '@/types/api';
import { profileErrorMessage } from '@/utils/apiErrors';

export function SlackLinkSection() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [issued, setIssued] = useState<SlackLinkCode | null>(null);

  const statusQuery = useQuery({
    queryKey: slackLinkKeys.status(),
    queryFn: getSlackLinkStatus,
  });

  const generateMutation = useMutation({
    mutationFn: createSlackLinkCode,
    onSuccess: (code) => setIssued(code),
    onError: (err) => message.error(profileErrorMessage(err)),
  });

  const unlinkMutation = useMutation({
    mutationFn: unlinkSlack,
    onSuccess: () => {
      setIssued(null);
      void queryClient.invalidateQueries({ queryKey: slackLinkKeys.all });
      message.success(t('profile.slack.unlink_success'));
    },
    onError: (err) => message.error(profileErrorMessage(err)),
  });

  if (statusQuery.isLoading) {
    return <Skeleton active />;
  }

  if (statusQuery.data?.linked) {
    return (
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <Typography.Text>
          {t('profile.slack.linked_as')}{' '}
          <Tag color="green">{statusQuery.data.slack_user_id}</Tag>
        </Typography.Text>
        <Popconfirm
          title={t('profile.slack.unlink_confirm')}
          okText={t('profile.slack.unlink')}
          cancelText={t('common.cancel')}
          onConfirm={() => unlinkMutation.mutate()}
        >
          <Button danger loading={unlinkMutation.isPending}>
            {t('profile.slack.unlink')}
          </Button>
        </Popconfirm>
      </Space>
    );
  }

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Typography.Text type="secondary">{t('profile.slack.description')}</Typography.Text>
      {issued ? (
        <Alert
          type="success"
          showIcon
          message={t('profile.slack.code_ready')}
          description={
            <Space direction="vertical" style={{ width: '100%' }}>
              <Typography.Paragraph copyable={{ text: `/accessflow link ${issued.code}` }} code
                style={{ wordBreak: 'break-all', marginBottom: 0 }}>
                /accessflow link {issued.code}
              </Typography.Paragraph>
              <Typography.Text type="secondary">{t('profile.slack.code_instructions')}</Typography.Text>
            </Space>
          }
        />
      ) : (
        <Button type="primary" loading={generateMutation.isPending}
          onClick={() => generateMutation.mutate()}>
          {t('profile.slack.generate_code')}
        </Button>
      )}
    </Space>
  );
}
