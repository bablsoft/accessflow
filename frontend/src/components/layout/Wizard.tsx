import { useEffect, useRef, type ReactNode } from 'react';
import { Steps } from 'antd';
import { useTranslation } from 'react-i18next';
import { useMediaQuery } from '@/hooks/useMediaQuery';
import { useEnterAnimation } from '@/motion/useEnterAnimation';
import { Page } from './Page';
import './layout.css';

export interface WizardStepDef<K extends string = string> {
  key: K;
  title: string;
  /** Marks a step the user may skip. */
  optional?: boolean;
}

interface WizardStepsProps<K extends string> {
  steps: readonly WizardStepDef<K>[];
  current: K;
  ariaLabel: string;
  /** Whether a step other than the current one can be opened by clicking it. */
  canOpen?: (key: K) => boolean;
  onOpen?: (key: K) => void;
}

/** The step indicator of a wizard. Steps the user may return to are clickable. */
export function WizardSteps<K extends string>({
  steps,
  current,
  ariaLabel,
  canOpen,
  onOpen,
}: WizardStepsProps<K>) {
  const { t } = useTranslation();
  // Four step titles do not fit a phone; name the current one and count the rest.
  const compact = useMediaQuery('(max-width: 720px)');
  const index = steps.findIndex((step) => step.key === current);
  if (compact) {
    return (
      <nav aria-label={ariaLabel} className="af-wizard-steps-compact">
        <span className="af-wizard-steps-count">
          {t('common.step_of', { current: index + 1, total: steps.length })}
        </span>
        <span className="af-wizard-steps-current">{steps[index]?.title}</span>
      </nav>
    );
  }
  return (
    <nav aria-label={ariaLabel} className="af-wizard-steps">
      <Steps
        size="small"
        current={index}
        onChange={
          onOpen
            ? (index) => {
                const step = steps[index];
                if (step) onOpen(step.key);
              }
            : undefined
        }
        items={steps.map((step) => ({
          title: step.title,
          subTitle: step.optional ? t('common.optional') : undefined,
          disabled: step.key === current || !onOpen || !(canOpen?.(step.key) ?? false),
        }))}
      />
    </nav>
  );
}

interface WizardLayoutProps {
  /** The page header. */
  header: ReactNode;
  /** A `<WizardSteps>`. */
  steps: ReactNode;
  /** Key of the step on screen; a change replays the enter animation and moves focus. */
  stepKey: string;
  footer: ReactNode;
  width?: 'form' | 'reading' | 'full';
  children: ReactNode;
}

/**
 * The frame every multi-step flow shares: header, step indicator, a scrolling body of limited
 * width, and a footer that stays in view.
 */
export function WizardLayout({
  header,
  steps,
  stepKey,
  footer,
  width = 'form',
  children,
}: WizardLayoutProps) {
  const bodyRef = useRef<HTMLDivElement>(null);
  const contentRef = useRef<HTMLDivElement>(null);
  const firstStep = useRef(stepKey);
  useEnterAnimation(contentRef, stepKey);

  useEffect(() => {
    if (firstStep.current === stepKey) return;
    firstStep.current = '';
    const body = bodyRef.current;
    if (!body) return;
    body.scrollTop = 0;
    // A step that focuses its own first field keeps that focus.
    if (body.contains(document.activeElement)) return;
    body.querySelector<HTMLElement>('[data-wizard-heading]')?.focus();
  }, [stepKey]);

  return (
    <Page>
      {header}
      <div className="af-wizard-band">{steps}</div>
      <div className="af-wizard-body" ref={bodyRef}>
        <div className={`af-wizard-content af-wizard-content--${width}`} ref={contentRef}>
          {children}
        </div>
      </div>
      <div className="af-wizard-footer">{footer}</div>
    </Page>
  );
}

interface WizardStepProps {
  title: string;
  description?: ReactNode;
  children: ReactNode;
}

/** One step's heading, intro and content. */
export function WizardStep({ title, description, children }: WizardStepProps) {
  return (
    <div className="af-wizard-step">
      <h2 className="af-wizard-step-title" tabIndex={-1} data-wizard-heading>
        {title}
      </h2>
      {description && <p className="af-wizard-step-description">{description}</p>}
      {children}
    </div>
  );
}

interface WizardFooterProps {
  /** Back and Cancel. */
  start?: ReactNode;
  /** A short status such as "Saved" or "Unsaved changes". */
  status?: ReactNode;
  /** Skip and the primary action, primary last. */
  end?: ReactNode;
}

/** Footer row: leaving and going back on the left, moving forward on the right. */
export function WizardFooter({ start, status, end }: WizardFooterProps) {
  return (
    <>
      <div className="af-wizard-footer-group">{start}</div>
      <span className="af-wizard-footer-status" role="status">
        {status}
      </span>
      <div className="af-wizard-footer-group">{end}</div>
    </>
  );
}
