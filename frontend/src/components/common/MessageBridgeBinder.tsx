import { useEffect } from 'react';
import { App as AntdApp } from 'antd';
import { setMessageApi } from '@/utils/messageBridge';

// Binds the AntD App-scoped `message` instance to the module-level
// messageBridge so non-React callers (Axios interceptor) can render toasts
// inside the same portal / theme context as the rest of the app.
export function MessageBridgeBinder(): null {
  const { message } = AntdApp.useApp();
  useEffect(() => {
    setMessageApi(message);
    return () => setMessageApi(null);
  }, [message]);
  return null;
}
