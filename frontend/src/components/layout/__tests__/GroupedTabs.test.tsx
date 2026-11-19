import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { GroupedTabs, groupedTabId, type TabGroup } from '../GroupedTabs';

const groups: TabGroup[] = [
  { key: 'a', label: 'General', items: [{ key: 'one', label: 'One' }, { key: 'two', label: 'Two' }] },
  { key: 'empty', label: 'Nothing here', items: [] },
  { key: 'b', label: 'Access', items: [{ key: 'three', label: 'Three' }] },
];

function setup(activeKey = 'one') {
  const onChange = vi.fn();
  render(
    <GroupedTabs groups={groups} activeKey={activeKey} onChange={onChange} ariaLabel="Settings" panelId="panel" />,
  );
  return onChange;
}

describe('GroupedTabs', () => {
  it('lists every tab under its group and skips an empty group', () => {
    setup();
    expect(screen.getByRole('tablist', { name: 'Settings' })).toBeInTheDocument();
    expect(screen.getByRole('group', { name: 'General' })).toBeInTheDocument();
    expect(screen.getByRole('group', { name: 'Access' })).toBeInTheDocument();
    expect(screen.queryByText('Nothing here')).toBeNull();
    expect(screen.getAllByRole('tab')).toHaveLength(3);
  });

  it('marks the active tab and ties it to the panel', () => {
    setup('two');
    const active = screen.getByRole('tab', { name: 'Two' });
    expect(active).toHaveAttribute('aria-selected', 'true');
    expect(active).toHaveAttribute('tabindex', '0');
    expect(active).toHaveAttribute('aria-controls', 'panel');
    expect(active).toHaveAttribute('id', groupedTabId('panel', 'two'));
    expect(screen.getByRole('tab', { name: 'One' })).toHaveAttribute('tabindex', '-1');
  });

  it('selects a tab on click', () => {
    const onChange = setup();
    fireEvent.click(screen.getByRole('tab', { name: 'Three' }));
    expect(onChange).toHaveBeenCalledWith('three');
  });

  it('moves with the arrow keys across groups and wraps around', () => {
    const onChange = setup('two');
    const two = screen.getByRole('tab', { name: 'Two' });
    fireEvent.keyDown(two, { key: 'ArrowDown' });
    expect(onChange).toHaveBeenLastCalledWith('three');
    expect(screen.getByRole('tab', { name: 'Three' })).toHaveFocus();
    fireEvent.keyDown(two, { key: 'ArrowUp' });
    expect(onChange).toHaveBeenLastCalledWith('one');
    fireEvent.keyDown(screen.getByRole('tab', { name: 'Three' }), { key: 'ArrowRight' });
    expect(onChange).toHaveBeenLastCalledWith('one');
    fireEvent.keyDown(screen.getByRole('tab', { name: 'One' }), { key: 'ArrowLeft' });
    expect(onChange).toHaveBeenLastCalledWith('three');
  });

  it('jumps to the first and last tab with Home and End and ignores other keys', () => {
    const onChange = setup('two');
    const two = screen.getByRole('tab', { name: 'Two' });
    fireEvent.keyDown(two, { key: 'End' });
    expect(onChange).toHaveBeenLastCalledWith('three');
    fireEvent.keyDown(two, { key: 'Home' });
    expect(onChange).toHaveBeenLastCalledWith('one');
    onChange.mockClear();
    fireEvent.keyDown(two, { key: 'x' });
    expect(onChange).not.toHaveBeenCalled();
  });
});
