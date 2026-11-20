import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import '@/i18n';
import { WizardFooter, WizardLayout, WizardStep, WizardSteps, type WizardStepDef } from '../Wizard';

const steps: WizardStepDef<'a' | 'b' | 'c'>[] = [
  { key: 'a', title: 'First' },
  { key: 'b', title: 'Second', optional: true },
  { key: 'c', title: 'Third' },
];

describe('WizardSteps', () => {
  it('labels the list and marks an optional step', () => {
    render(<WizardSteps steps={steps} current="a" ariaLabel="Progress" />);
    expect(screen.getByRole('navigation', { name: 'Progress' })).toBeInTheDocument();
    expect(screen.getByText('Optional')).toBeInTheDocument();
  });

  it('opens only the steps it is allowed to', () => {
    const onOpen = vi.fn();
    render(
      <WizardSteps
        steps={steps}
        current="b"
        ariaLabel="Progress"
        canOpen={(key) => key === 'a'}
        onOpen={onOpen}
      />,
    );
    fireEvent.click(screen.getByText('Third'));
    fireEvent.click(screen.getByText('Second'));
    expect(onOpen).not.toHaveBeenCalled();
    fireEvent.click(screen.getByText('First'));
    expect(onOpen).toHaveBeenCalledWith('a');
  });
});

describe('WizardSteps on a narrow screen', () => {
  it('names the current step and counts the rest', () => {
    const original = window.matchMedia;
    window.matchMedia = ((query: string) => ({
      matches: true,
      media: query,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
      addListener: () => undefined,
      removeListener: () => undefined,
      onchange: null,
      dispatchEvent: () => false,
    })) as typeof window.matchMedia;
    try {
      render(<WizardSteps steps={steps} current="b" ariaLabel="Progress" />);
      expect(screen.getByText('Step 2 of 3')).toBeInTheDocument();
      expect(screen.getByText('Second')).toBeInTheDocument();
      expect(screen.queryByText('Third')).toBeNull();
    } finally {
      window.matchMedia = original;
    }
  });
});

describe('WizardLayout', () => {
  function layout(stepKey: string, body = <WizardStep title={`Step ${stepKey}`}>content</WizardStep>) {
    return (
      <WizardLayout
        stepKey={stepKey}
        header={<header>Header</header>}
        steps={<div>Steps</div>}
        footer={<WizardFooter start={<button>Back</button>} status="Saved" end={<button>Next</button>} />}
      >
        {body}
      </WizardLayout>
    );
  }

  it('renders header, steps, content and footer', () => {
    render(layout('a'));
    expect(screen.getByText('Header')).toBeInTheDocument();
    expect(screen.getByText('Steps')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Step a' })).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Saved');
    expect(screen.getByRole('button', { name: 'Next' })).toBeInTheDocument();
  });

  it('moves focus to the heading when the step changes, not on first render', () => {
    const { rerender } = render(layout('a'));
    expect(screen.getByRole('heading', { name: 'Step a' })).not.toHaveFocus();
    rerender(layout('b'));
    expect(screen.getByRole('heading', { name: 'Step b' })).toHaveFocus();
  });

  it('leaves focus alone when the new step focused a field of its own', () => {
    const { rerender } = render(layout('a'));
    rerender(
      layout(
        'b',
        <WizardStep title="Step b" description="Intro">
          <input aria-label="Name" autoFocus />
        </WizardStep>,
      ),
    );
    expect(screen.getByLabelText('Name')).toHaveFocus();
    expect(screen.getByText('Intro')).toBeInTheDocument();
  });
});
