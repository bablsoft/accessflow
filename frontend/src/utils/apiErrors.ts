import axios, { AxiosError } from 'axios';
import i18n from '../i18n';

interface ProblemDetail {
  title?: string;
  detail?: string;
  error?: string;
}

export function authErrorMessage(err: unknown): string {
  if (axios.isAxiosError(err)) {
    const ax = err as AxiosError<ProblemDetail>;
    if (ax.response?.status === 401) return i18n.t('errors.auth_invalid');
    const body = ax.response?.data;
    if (body?.title) return body.title;
    if (body?.detail) return body.detail;
    if (ax.message) return ax.message;
  }
  if (err instanceof Error && err.message) return err.message;
  return i18n.t('errors.auth_generic');
}

export function setupErrorMessage(err: unknown): string {
  if (axios.isAxiosError(err)) {
    const ax = err as AxiosError<ProblemDetail>;
    const body = ax.response?.data;
    if (body?.error === 'SETUP_ALREADY_COMPLETED') {
      return i18n.t('errors.setup_already_complete');
    }
    if (body?.error === 'EMAIL_ALREADY_EXISTS') {
      return i18n.t('errors.email_already_exists');
    }
    if (body?.title) return body.title;
    if (body?.detail) return body.detail;
    if (ax.message) return ax.message;
  }
  if (err instanceof Error && err.message) return err.message;
  return i18n.t('errors.setup_generic');
}
