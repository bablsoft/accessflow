import { useEffect, useState } from 'react';
import { Alert, App, Button, Checkbox, Input, Modal, Space, Steps, Typography } from 'antd';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { confirmTotp, enrollTotp, meKeys } from '@/api/me';
import type { TotpEnrollment } from '@/types/api';
import { profileErrorMessage } from '@/utils/apiErrors';

interface TotpEnrollmentDialogProps {
  open: boolean;
  onClose: () => void;
}

export function TotpEnrollmentDialog({ open, onClose }: TotpEnrollmentDialogProps) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [step, setStep] = useState(0);
  const [enrollment, setEnrollment] = useState<TotpEnrollment | null>(null);
  const [code, setCode] = useState('');
  const [backupCodes, setBackupCodes] = useState<string[]>([]);
  const [savedAck, setSavedAck] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const enrollMutation = useMutation({
    mutationFn: () => enrollTotp(),
    onSuccess: (data) => {
      setEnrollment(data);
      setError(null);
    },
    onError: (err) => setError(profileErrorMessage(err)),
  });

  const confirmMutation = useMutation({
    mutationFn: () => confirmTotp({ code }),
    onSuccess: (data) => {
      setBackupCodes(data.backup_codes);
      setError(null);
      setStep(2);
    },
    onError: (err) => setError(profileErrorMessage(err)),
  });

  useEffect(() => {
    if (open && !enrollment && !enrollMutation.isPending) {
      enrollMutation.mutate();
    }
    if (!open) {
      // Reset on close
      setStep(0);
      setEnrollment(null);
      setCode('');
      setBackupCodes([]);
      setSavedAck(false);
      setError(null);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  const onDone = () => {
    queryClient.invalidateQueries({ queryKey: meKeys.current });
    onClose();
  };

  const onCopyBackupCodes = async () => {
    try {
      await navigator.clipboard.writeText(backupCodes.join('\n'));
      message.success(t('profile.totp.codes_copied'));
    } catch {
      // Clipboard may be unavailable in non-secure contexts; the codes are still on screen.
    }
  };

  return (
    <Modal
      open={open}
      onCancel={onClose}
      title={t('profile.totp.enroll_title')}
      footer={null}
      width={520}
      destroyOnHidden
    >
      <Steps
        current={step}
        size="small"
        items={[
          { title: t('profile.totp.step_scan') },
          { title: t('profile.totp.step_verify') },
          { title: t('profile.totp.step_codes') },
        ]}
        style={{ marginBottom: 24 }}
      />

      {error && (
        <Alert
          type="error"
          message={error}
          style={{ marginBottom: 16 }}
          showIcon
          closable
          onClose={() => setError(null)}
        />
      )}

      {step === 0 && (
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Typography.Paragraph>{t('profile.totp.step_scan_help')}</Typography.Paragraph>
          <div style={{ display: 'flex', justifyContent: 'center' }}>
            {enrollment ? (
              <img
                src={enrollment.qr_data_uri}
                alt={t('profile.totp.step_scan')}
                width={200}
                height={200}
                style={{ background: '#fff', padding: 8, borderRadius: 8 }}
              />
            ) : (
              <div style={{ width: 200, height: 200 }} aria-hidden />
            )}
          </div>
          {enrollment && (
            <div>
              <Typography.Text type="secondary">
                {t('profile.totp.manual_secret_label')}
              </Typography.Text>
              <Input.TextArea
                readOnly
                value={enrollment.secret}
                autoSize
                style={{ marginTop: 4, fontFamily: 'monospace' }}
              />
            </div>
          )}
          <Button
            type="primary"
            block
            disabled={!enrollment}
            onClick={() => {
              setStep(1);
              setError(null);
            }}
          >
            {t('profile.totp.next')}
          </Button>
        </Space>
      )}

      {step === 1 && (
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Typography.Paragraph>{t('profile.totp.step_verify_help')}</Typography.Paragraph>
          <Input
            placeholder={t('profile.totp.verify_placeholder')}
            value={code}
            onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
            inputMode="numeric"
            autoComplete="one-time-code"
            maxLength={6}
            style={{ textAlign: 'center', fontFamily: 'monospace', fontSize: 18, letterSpacing: 4 }}
            aria-label={t('profile.totp.verify_placeholder')}
          />
          <Space>
            <Button onClick={() => setStep(0)}>{t('profile.totp.back')}</Button>
            <Button
              type="primary"
              loading={confirmMutation.isPending}
              disabled={code.length !== 6}
              onClick={() => confirmMutation.mutate()}
            >
              {t('profile.totp.verify_button')}
            </Button>
          </Space>
        </Space>
      )}

      {step === 2 && (
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Alert type="warning" message={t('profile.totp.backup_codes_help')} showIcon />
          <pre
            style={{
              background: 'var(--bg)',
              border: '1px solid var(--border)',
              borderRadius: 8,
              padding: 12,
              fontFamily: 'monospace',
              fontSize: 13,
              lineHeight: 1.7,
            }}
            aria-label="backup-codes"
          >
            {backupCodes.join('\n')}
          </pre>
          <Space>
            <Button onClick={onCopyBackupCodes}>{t('profile.totp.copy_codes')}</Button>
          </Space>
          <Checkbox checked={savedAck} onChange={(e) => setSavedAck(e.target.checked)}>
            {t('profile.totp.backup_codes_saved')}
          </Checkbox>
          <Button type="primary" block disabled={!savedAck} onClick={onDone}>
            {t('profile.totp.done')}
          </Button>
        </Space>
      )}
    </Modal>
  );
}
