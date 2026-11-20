import { Tag } from 'antd';
import { useTranslation } from 'react-i18next';
import { deploymentTicketDecisionLabel } from '@/utils/enumLabels';
import { deploymentTicketDecisionColor } from '@/utils/statusColors';
import type { DeploymentTicketRef } from '@/types/api';

/** A deployment's change-ticket key, linked out when the system gave a URL, plus its decision (#994). */
export function ChangeTicketLink({ ticket }: { ticket: DeploymentTicketRef }) {
  const { t } = useTranslation();
  const color = ticket.decision ? deploymentTicketDecisionColor(ticket.decision) : null;
  return (
    <span className="u-inline-flex u-items-center u-gap-2">
      {ticket.url ? (
        <a
          href={ticket.url}
          target="_blank"
          rel="noopener noreferrer"
          className="u-mono"
        >
          {ticket.external_key}
        </a>
      ) : (
        <span className="u-mono">{ticket.external_key}</span>
      )}
      {ticket.decision && color && (
        <Tag style={{ color: color.fg, background: color.bg, borderColor: color.border }}>
          {deploymentTicketDecisionLabel(t, ticket.decision)}
        </Tag>
      )}
    </span>
  );
}
