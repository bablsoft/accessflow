import { Button } from 'antd';
import {
  EditOutlined,
  MailOutlined,
  MessageOutlined,
  MoreOutlined,
  PlayCircleOutlined,
  PlusOutlined,
  SendOutlined,
} from '@ant-design/icons';
import { PageHeader } from '@/components/common/PageHeader';
import { Pill } from '@/components/common/Pill';
import { CHANNELS } from '@/mocks/data';
import { timeAgo } from '@/utils/dateFormat';
import type { NotificationChannel } from '@/types/api';

export function NotificationsPage() {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        title="Notification channels"
        subtitle="Where AccessFlow sends review events, approvals, and security alerts."
        actions={<Button type="primary" icon={<PlusOutlined />}>Add channel</Button>}
      />
      <div
        style={{
          flex: 1,
          overflow: 'auto',
          padding: 24,
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fill, minmax(380px, 1fr))',
          gap: 14,
          alignContent: 'start',
        }}
      >
        {CHANNELS.map((c) => (
          <ChannelCard key={c.id} ch={c} />
        ))}
      </div>
    </div>
  );
}

function ChannelCard({ ch }: { ch: NotificationChannel }) {
  const icon = ch.type === 'EMAIL'
    ? <MailOutlined style={{ fontSize: 18 }} />
    : ch.type === 'SLACK'
      ? <MessageOutlined style={{ fontSize: 18 }} />
      : <SendOutlined style={{ fontSize: 18 }} />;
  const iconColor = ch.type === 'EMAIL' ? '#2563eb' : ch.type === 'SLACK' ? '#7c3aed' : '#ea580c';
  return (
    <div
      style={{
        background: 'var(--bg-elev)',
        border: '1px solid var(--border)',
        borderRadius: 'var(--radius-md)',
        padding: 16,
      }}
    >
      <div style={{ display: 'flex', alignItems: 'flex-start', gap: 12 }}>
        <div
          style={{
            width: 36,
            height: 36,
            borderRadius: 8,
            background: iconColor + '20',
            color: iconColor,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            flexShrink: 0,
          }}
        >
          {icon}
        </div>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <div style={{ fontWeight: 600, fontSize: 14 }}>{ch.name}</div>
            {!ch.active && (
              <Pill
                fg="var(--fg-muted)"
                bg="var(--status-neutral-bg)"
                border="var(--status-neutral-border)"
                size="sm"
              >
                disabled
              </Pill>
            )}
          </div>
          <div className="mono muted" style={{ fontSize: 11, marginTop: 2 }}>
            {ch.type}
          </div>
        </div>
        <Button size="small" type="text" icon={<MoreOutlined />} />
      </div>
      <div
        style={{
          marginTop: 12,
          padding: 10,
          background: 'var(--bg-sunken)',
          borderRadius: 6,
          fontSize: 11.5,
          fontFamily: 'var(--font-mono)',
          lineHeight: 1.5,
        }}
      >
        {ch.type === 'EMAIL' && 'to' in ch.config && (
          <>to: {ch.config.to.join(', ')}<br />from: {ch.config.from}</>
        )}
        {ch.type === 'SLACK' && 'channel' in ch.config && (
          <>channel: {ch.config.channel}<br />webhook: {ch.config.webhook.slice(0, 38)}…</>
        )}
        {ch.type === 'WEBHOOK' && 'url' in ch.config && (
          <>url: {ch.config.url}<br />signing: {ch.config.signing}</>
        )}
      </div>
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          marginTop: 12,
          paddingTop: 12,
          borderTop: '1px solid var(--border)',
        }}
      >
        <span className="mono muted" style={{ fontSize: 10 }}>
          last sent · {timeAgo(ch.last_used)}
        </span>
        <div style={{ flex: 1 }} />
        <Button size="small" icon={<PlayCircleOutlined />}>Test</Button>
        <Button size="small" icon={<EditOutlined />}>Edit</Button>
      </div>
    </div>
  );
}
