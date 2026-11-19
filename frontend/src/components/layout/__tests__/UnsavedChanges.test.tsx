import { describe, expect, it } from 'vitest';
import { act, fireEvent, render, renderHook, screen } from '@testing-library/react';
import { App } from 'antd';
import { Link, MemoryRouter, RouterProvider, createMemoryRouter, useLocation } from 'react-router-dom';
import '@/i18n';
import { FormActions, UnsavedChangesGuard, useDirtyState } from '..';

function Where() {
  return <div data-testid="where">{useLocation().pathname + useLocation().search}</div>;
}

function renderWithDataRouter(when: boolean) {
  const router = createMemoryRouter(
    [
      {
        path: '*',
        element: (
          <App>
            <UnsavedChangesGuard when={when} />
            <Where />
            <Link to="/other">leave</Link>
            <Link to="/form?tab=two">tab</Link>
          </App>
        ),
      },
    ],
    { initialEntries: ['/form'] },
  );
  render(<RouterProvider router={router} />);
}

describe('useDirtyState', () => {
  it('flips between dirty and clean', () => {
    const { result } = renderHook(() => useDirtyState());
    expect(result.current.dirty).toBe(false);
    act(() => result.current.markDirty());
    expect(result.current.dirty).toBe(true);
    act(() => result.current.markClean());
    expect(result.current.dirty).toBe(false);
  });
});

describe('UnsavedChangesGuard', () => {
  it('asks before leaving the page and stays on Keep editing', async () => {
    renderWithDataRouter(true);
    fireEvent.click(screen.getByText('leave'));
    expect((await screen.findAllByText('Discard unsaved changes?')).length).toBeGreaterThan(0);
    expect(screen.getByTestId('where')).toHaveTextContent('/form');
    fireEvent.click(screen.getByText('Keep editing'));
    expect(screen.getByTestId('where')).toHaveTextContent('/form');
  });

  it('leaves once the user discards', async () => {
    renderWithDataRouter(true);
    fireEvent.click(screen.getByText('leave'));
    await screen.findAllByText('Discard unsaved changes?');
    await act(async () => {
      fireEvent.click(screen.getByText('Discard changes'));
    });
    expect(screen.getByTestId('where')).toHaveTextContent('/other');
  });

  it('does not block a query-string change or a clean form', () => {
    renderWithDataRouter(true);
    fireEvent.click(screen.getByText('tab'));
    expect(screen.getByTestId('where')).toHaveTextContent('/form?tab=two');
  });

  it('lets a clean form navigate freely', () => {
    renderWithDataRouter(false);
    fireEvent.click(screen.getByText('leave'));
    expect(screen.getByTestId('where')).toHaveTextContent('/other');
  });

  it('prompts on reload only while dirty, and works without a data router', () => {
    const { rerender } = render(
      <MemoryRouter>
        <UnsavedChangesGuard when />
      </MemoryRouter>,
    );
    const dirtyEvent = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(dirtyEvent);
    expect(dirtyEvent.defaultPrevented).toBe(true);

    rerender(
      <MemoryRouter>
        <UnsavedChangesGuard when={false} />
      </MemoryRouter>,
    );
    const cleanEvent = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(cleanEvent);
    expect(cleanEvent.defaultPrevented).toBe(false);
  });
});

describe('FormActions', () => {
  it('shows the unsaved hint only while dirty', () => {
    const { rerender } = render(
      <FormActions>
        <button type="submit">Save</button>
      </FormActions>,
    );
    expect(screen.getByText('Save')).toBeInTheDocument();
    expect(screen.queryByText('Unsaved changes')).toBeNull();
    rerender(
      <FormActions dirty>
        <button type="submit">Save</button>
      </FormActions>,
    );
    expect(screen.getByRole('status')).toHaveTextContent('Unsaved changes');
  });
});
