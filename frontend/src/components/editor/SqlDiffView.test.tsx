import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render } from '@testing-library/react';

const { mergeViewMock } = vi.hoisted(() => ({ mergeViewMock: vi.fn() }));

vi.mock('@codemirror/merge', () => ({
  MergeView: class {
    constructor(config: unknown) {
      mergeViewMock(config);
    }
    destroy() {}
  },
}));

import { SqlDiffView } from './SqlDiffView';

describe('SqlDiffView', () => {
  beforeEach(() => mergeViewMock.mockReset());

  it('constructs a MergeView with the old doc on the left and new on the right', () => {
    render(<SqlDiffView oldValue="SELECT 1" newValue="SELECT 2" />);

    expect(mergeViewMock).toHaveBeenCalledTimes(1);
    const config = mergeViewMock.mock.calls[0]?.[0] as { a: { doc: string }; b: { doc: string } };
    expect(config.a.doc).toBe('SELECT 1');
    expect(config.b.doc).toBe('SELECT 2');
  });

  it('renders a container for the diff', () => {
    const { getByTestId } = render(<SqlDiffView oldValue="a" newValue="b" />);
    expect(getByTestId('sql-diff-view')).toBeInTheDocument();
  });
});
