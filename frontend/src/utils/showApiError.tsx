import type { MessageInstance } from 'antd/es/message/interface';
import { TraceIdFooter } from '../components/common/TraceIdFooter';
import { apiErrorTraceId } from './apiErrors';

export function showApiError(
  messageApi: MessageInstance,
  err: unknown,
  builder: (e: unknown) => string,
): void {
  const text = builder(err);
  const traceId = apiErrorTraceId(err);
  if (traceId) {
    messageApi.error({
      content: (
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}>
          <span>{text}</span>
          <TraceIdFooter traceId={traceId} />
        </span>
      ),
      duration: 8,
    });
    return;
  }
  messageApi.error(text);
}
