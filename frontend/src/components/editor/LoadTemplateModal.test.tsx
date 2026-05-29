import { describe, expect, it, vi } from 'vitest';
import { App } from 'antd';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { LoadTemplateModal } from './LoadTemplateModal';
import type { QueryTemplate } from '@/types/api';

function withApp(ui: ReactNode) {
  return <App>{ui}</App>;
}

const baseTemplate: QueryTemplate = {
  id: 't-1',
  organization_id: 'org-1',
  owner_id: 'u-1',
  owner_display_name: 'Alice',
  datasource_id: null,
  name: 'Top users',
  body: '',
  description: null,
  tags: [],
  visibility: 'PRIVATE',
  editable: true,
  created_at: '2026-05-01T00:00:00Z',
  updated_at: '2026-05-01T00:00:00Z',
};

describe('LoadTemplateModal', () => {
  it('auto-confirms the body when the template has no placeholders', async () => {
    const onConfirm = vi.fn();
    render(
      withApp(
        <LoadTemplateModal
          template={{ ...baseTemplate, body: 'SELECT * FROM users' }}
          onCancel={() => {}}
          onConfirm={onConfirm}
        />,
      ),
    );

    await waitFor(() => {
      expect(onConfirm).toHaveBeenCalledWith('SELECT * FROM users');
    });
  });

  it('renders an input per placeholder and substitutes on confirm', async () => {
    const onConfirm = vi.fn();
    render(
      withApp(
        <LoadTemplateModal
          template={{
            ...baseTemplate,
            body: 'SELECT * FROM users WHERE country = :country LIMIT :limit',
          }}
          onCancel={() => {}}
          onConfirm={onConfirm}
        />,
      ),
    );

    expect(await screen.findByText(':country')).toBeInTheDocument();
    expect(screen.getByText(':limit')).toBeInTheDocument();

    const inputs = screen.getAllByRole('textbox');
    fireEvent.change(inputs[0]!, { target: { value: "'US'" } });
    fireEvent.change(inputs[1]!, { target: { value: '10' } });
    fireEvent.click(screen.getByRole('button', { name: 'Load' }));

    await waitFor(() => {
      expect(onConfirm).toHaveBeenCalledWith(
        "SELECT * FROM users WHERE country = 'US' LIMIT 10",
      );
    });
  });

  it('returns null and renders nothing when template is null', () => {
    const { container } = render(
      withApp(
        <LoadTemplateModal template={null} onCancel={() => {}} onConfirm={() => {}} />,
      ),
    );
    // No dialog is rendered.
    expect(container.querySelector('.ant-modal')).toBeNull();
  });
});
