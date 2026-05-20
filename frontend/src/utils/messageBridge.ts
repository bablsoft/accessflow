import type { MessageInstance } from 'antd/es/message/interface';

// Module-level handle to the AntD App-scoped message API so non-React code
// (Axios interceptor, etc.) can surface toasts that respect the active theme.
// Set by <MessageBridgeBinder /> inside <AntdApp>; cleared on unmount.
let messageApi: MessageInstance | null = null;

export function setMessageApi(api: MessageInstance | null): void {
  messageApi = api;
}

export function getMessageApi(): MessageInstance | null {
  return messageApi;
}
