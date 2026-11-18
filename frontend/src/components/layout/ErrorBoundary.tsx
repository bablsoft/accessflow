import { Component, type ErrorInfo, type ReactNode } from 'react';
import { Button } from 'antd';
import { WarningOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { EmptyState } from '@/components/common/EmptyState';

interface ErrorBoundaryProps {
  children: ReactNode;
  /** The boundary clears its error when this changes (pass the route path). */
  resetKey?: string;
}

interface ErrorBoundaryState {
  failed: boolean;
  resetKey?: string;
}

function ErrorFallback({ onRetry }: { onRetry: () => void }) {
  const { t } = useTranslation();
  return (
    <div role="alert">
      <EmptyState
        icon={<WarningOutlined />}
        title={t('common.error_boundary_title')}
        description={t('common.error_boundary_description')}
        action={<Button onClick={onRetry}>{t('common.retry')}</Button>}
      />
    </div>
  );
}

/** Keeps a render error in one page from blanking the whole app. */
export class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  state: ErrorBoundaryState = { failed: false, resetKey: this.props.resetKey };

  static getDerivedStateFromError(): Partial<ErrorBoundaryState> {
    return { failed: true };
  }

  static getDerivedStateFromProps(
    props: ErrorBoundaryProps,
    state: ErrorBoundaryState,
  ): ErrorBoundaryState | null {
    return props.resetKey === state.resetKey ? null : { failed: false, resetKey: props.resetKey };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('Page render failed', error, info.componentStack);
  }

  render() {
    if (this.state.failed) {
      return <ErrorFallback onRetry={() => this.setState({ failed: false })} />;
    }
    return this.props.children;
  }
}
