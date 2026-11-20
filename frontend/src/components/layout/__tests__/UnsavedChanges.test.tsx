import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, renderHook, screen } from '@testing-library/react';
import { App } from 'antd';
import { Link, MemoryRouter, RouterProvider, createMemoryRouter, useLocation } from 'react-router-dom';
import '@/i18n';
import {
  FormActions,
  UnsavedChangesGuard,
  useConfirmDiscard,
  useDirtyClose,
  useDirtyState,
} from '..';

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

describe('UnsavedChangesGuard with tabs and several forms', () => {
  function renderGuards(guards: React.ReactNode) {
    const router = createMemoryRouter(
      [
        {
          path: '*',
          element: (
            <App>
              {guards}
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

  it('asks before a tab change when the form lives in the tab', async () => {
    renderGuards(<UnsavedChangesGuard when tabs />);
    fireEvent.click(screen.getByText('tab'));
    expect((await screen.findAllByText('Discard unsaved changes?')).length).toBeGreaterThan(0);
    expect(screen.getByTestId('where')).toHaveTextContent('/form');
    await act(async () => {
      fireEvent.click(screen.getByText('Discard changes'));
    });
    expect(screen.getByTestId('where')).toHaveTextContent('/form?tab=two');
  });

  it('asks once when two forms are dirty, and lets a clean pair through', async () => {
    renderGuards(
      <>
        <UnsavedChangesGuard when />
        <UnsavedChangesGuard when />
        <UnsavedChangesGuard when={false} />
      </>,
    );
    fireEvent.click(screen.getByText('leave'));
    await screen.findAllByText('Discard unsaved changes?');
    expect(document.querySelectorAll('.ant-modal-confirm')).toHaveLength(1);
  });

  it('still guards when only the second form is dirty', async () => {
    renderGuards(
      <>
        <UnsavedChangesGuard when={false} />
        <UnsavedChangesGuard when />
      </>,
    );
    fireEvent.click(screen.getByText('leave'));
    expect((await screen.findAllByText('Discard unsaved changes?')).length).toBeGreaterThan(0);
    expect(screen.getByTestId('where')).toHaveTextContent('/form');
  });
});

describe('useConfirmDiscard and useDirtyClose', () => {
  function Dialog({ onClose }: { onClose: () => void }) {
    const [open, setOpen] = useState(true);
    const guard = useDirtyClose(open);
    return (
      <>
        <span data-testid="dirty">{String(guard.dirty)}</span>
        <button onClick={guard.markDirty}>type</button>
        <button onClick={() => guard.close(onClose)}>close</button>
        <button onClick={() => setOpen((value) => !value)}>toggle</button>
      </>
    );
  }

  it('closes at once while clean', () => {
    const onClose = vi.fn();
    render(
      <App>
        <Dialog onClose={onClose} />
      </App>,
    );
    fireEvent.click(screen.getByText('close'));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('asks before closing with typed input, and closes on Discard', async () => {
    const onClose = vi.fn();
    render(
      <App>
        <Dialog onClose={onClose} />
      </App>,
    );
    fireEvent.click(screen.getByText('type'));
    fireEvent.click(screen.getByText('close'));
    expect((await screen.findAllByText('Discard unsaved changes?')).length).toBeGreaterThan(0);
    expect(onClose).not.toHaveBeenCalled();
    await act(async () => {
      fireEvent.click(screen.getByText('Discard changes'));
    });
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('starts clean again when reopened', () => {
    render(
      <App>
        <Dialog onClose={() => undefined} />
      </App>,
    );
    fireEvent.click(screen.getByText('type'));
    expect(screen.getByTestId('dirty')).toHaveTextContent('true');
    fireEvent.click(screen.getByText('toggle'));
    fireEvent.click(screen.getByText('toggle'));
    expect(screen.getByTestId('dirty')).toHaveTextContent('false');
  });

  it('runs the action directly when nothing is dirty', () => {
    const proceed = vi.fn();
    const { result } = renderHook(() => useConfirmDiscard(), {
      wrapper: ({ children }) => <App>{children}</App>,
    });
    act(() => result.current(false, proceed));
    expect(proceed).toHaveBeenCalledTimes(1);
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
