import { Button, Checkbox, Drawer, Select, Space } from 'antd';
import {
  ExperimentOutlined,
  LoadingOutlined,
  BookOutlined,
  SaveOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { QueryAuthoringPanel } from '@/components/editor/QueryAuthoringPanel';
import { useQueryAuthoring } from '@/components/editor/useQueryAuthoring';
import { ApiAuthoringPanel } from '@/components/apigov/ApiAuthoringPanel';
import { useApiAuthoring } from '@/components/apigov/useApiAuthoring';
import type { ApiConnector, Datasource } from '@/types/api';
import { targetKindLabel } from '@/utils/enumLabels';
import type { DraftMember } from './groupBuilder';

interface GroupMemberEditDrawerProps {
  /** The member being edited; null keeps the drawer closed. */
  member: DraftMember | null;
  index: number;
  /** Active-only datasources (the builder filters). */
  datasources: Datasource[];
  connectors: ApiConnector[];
  /** Live updates — the summary card stays in sync while the drawer is open. */
  onChange: (next: DraftMember) => void;
  onClose: () => void;
}

/**
 * Full-parity authoring drawer for one group step (#559): QUERY members get the standalone Query
 * Editor surface (schema tree + autocomplete, syntax toggle, format, AI analyze, dry-run,
 * text-to-SQL, templates); API_CALL members get the API Editor surface (operation picker, request
 * composer, AI analyze, text-to-API). The drawer's AI analysis is a transient preview — persisted
 * member risk still comes from the server-side analyses at submission.
 */
export function GroupMemberEditDrawer({
  member,
  index,
  datasources,
  connectors,
  onChange,
  onClose,
}: GroupMemberEditDrawerProps) {
  return (
    <Drawer
      open={member != null}
      onClose={onClose}
      width="min(1080px, 100vw)"
      destroyOnHidden
      title={member ? <DrawerTitle member={member} index={index} /> : null}
    >
      <div data-testid="group-member-edit-drawer">
        {member?.targetKind === 'QUERY' && (
          <QueryMemberEditor
            member={member}
            datasources={datasources}
            onChange={onChange}
            onClose={onClose}
          />
        )}
        {member?.targetKind === 'API_CALL' && (
          <ApiMemberEditor
            member={member}
            connectors={connectors}
            onChange={onChange}
            onClose={onClose}
          />
        )}
      </div>
    </Drawer>
  );
}

function DrawerTitle({ member, index }: { member: DraftMember; index: number }) {
  const { t } = useTranslation();
  return (
    <span>
      {t(
        member.targetKind === 'QUERY'
          ? 'requestGroups.builder.drawerTitleQuery'
          : 'requestGroups.builder.drawerTitleApi',
        { index: index + 1 },
      )}{' '}
      <span className="muted" style={{ fontWeight: 400 }}>
        · {targetKindLabel(t, member.targetKind)}
      </span>
    </span>
  );
}

function QueryMemberEditor({
  member,
  datasources,
  onChange,
  onClose,
}: {
  member: DraftMember;
  datasources: Datasource[];
  onChange: (next: DraftMember) => void;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const ds = datasources.find((d) => d.id === member.datasourceId) ?? null;
  const authoring = useQueryAuthoring({
    ds,
    sql: member.sqlText,
    onSqlChange: (next) => onChange({ ...member, sqlText: next }),
  });

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
      <div style={{ display: 'flex', gap: 8, alignItems: 'flex-end', flexWrap: 'wrap' }}>
        <label style={{ flex: 1, minWidth: 260 }}>
          <div className="muted" style={{ marginBottom: 4 }}>
            {t('requestGroups.builder.datasource')}
          </div>
          <Select
            style={{ width: '100%' }}
            showSearch
            optionFilterProp="label"
            placeholder={t('requestGroups.builder.selectDatasource')}
            value={member.datasourceId ?? undefined}
            onChange={(v) => onChange({ ...member, datasourceId: v })}
            options={datasources.map((d) => ({ value: d.id, label: d.name }))}
          />
        </label>
        <Space wrap>
          <Button icon={<BookOutlined />} disabled={!ds} onClick={authoring.openTemplates}>
            {t('editor.templates_button')}
          </Button>
          <Button
            icon={<SaveOutlined />}
            disabled={!ds || !member.sqlText.trim()}
            onClick={authoring.openSaveTemplate}
          >
            {t('editor.save_template_button')}
          </Button>
          <Button
            icon={authoring.dryRunning ? <LoadingOutlined /> : <ExperimentOutlined />}
            disabled={!ds || !authoring.canDryRun}
            onClick={authoring.dryRun}
          >
            {authoring.dryRunning ? t('editor.dry_running_button') : t('editor.dry_run_button')}
          </Button>
          {authoring.aiSupported && (
            <Button
              icon={authoring.analyzing ? <LoadingOutlined /> : <ThunderboltOutlined />}
              disabled={!authoring.canAnalyze}
              onClick={authoring.analyze}
            >
              {authoring.analyzing ? t('editor.analyzing_button') : t('editor.analyze_button')}
            </Button>
          )}
        </Space>
      </div>
      {ds ? (
        <QueryAuthoringPanel
          authoring={authoring}
          ds={ds}
          datasources={datasources}
          onChangeDs={(id) => onChange({ ...member, datasourceId: id })}
          sql={member.sqlText}
          variant="drawer"
          editorHeight={240}
          footer={
            <Checkbox
              checked={member.transactional}
              onChange={(e) => onChange({ ...member, transactional: e.target.checked })}
            >
              {t('requestGroups.builder.transactional')}
            </Checkbox>
          }
        />
      ) : (
        <div className="muted" style={{ padding: 24, textAlign: 'center' }}>
          {t('requestGroups.builder.pickDatasourceFirst')}
        </div>
      )}
      <DrawerFooter onClose={onClose} />
    </div>
  );
}

function ApiMemberEditor({
  member,
  connectors,
  onChange,
  onClose,
}: {
  member: DraftMember;
  connectors: ApiConnector[];
  onChange: (next: DraftMember) => void;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const connector = connectors.find((c) => c.id === member.connectorId) ?? null;
  const value = {
    operationId: member.operationId,
    verb: member.verb,
    requestPath: member.requestPath,
    composition: member.composition,
  };
  const handleChange = (next: typeof value) =>
    onChange({
      ...member,
      operationId: next.operationId,
      verb: next.verb,
      requestPath: next.requestPath,
      composition: next.composition,
    });
  const authoring = useApiAuthoring({ connector, value, onChange: handleChange });

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
      <ApiAuthoringPanel
        authoring={authoring}
        connector={connector}
        value={value}
        onChange={handleChange}
        layout="drawer"
        header={
          <div style={{ display: 'flex', gap: 8, alignItems: 'flex-end', flexWrap: 'wrap' }}>
            <label style={{ flex: 1, minWidth: 260 }}>
              <div className="muted" style={{ marginBottom: 4 }}>
                {t('requestGroups.builder.connector')}
              </div>
              <Select
                style={{ width: '100%' }}
                showSearch
                optionFilterProp="label"
                placeholder={t('requestGroups.builder.selectConnector')}
                value={member.connectorId ?? undefined}
                onChange={(v) => onChange({ ...member, connectorId: v, operationId: null })}
                options={connectors.map((c) => ({ value: c.id, label: c.name }))}
              />
            </label>
            <Button
              icon={authoring.analyzing ? <LoadingOutlined /> : <ThunderboltOutlined />}
              disabled={!authoring.canAnalyze}
              onClick={authoring.analyze}
            >
              {t('apiGov.editor.analyze')}
            </Button>
          </div>
        }
      />
      <DrawerFooter onClose={onClose} />
    </div>
  );
}

function DrawerFooter({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation();
  return (
    <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
      <Button type="primary" onClick={onClose} data-testid="group-member-edit-done">
        {t('requestGroups.builder.done')}
      </Button>
    </div>
  );
}
