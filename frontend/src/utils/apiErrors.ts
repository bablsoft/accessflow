import axios, { AxiosError } from 'axios';

interface ProblemDetail {
  title?: string;
  detail?: string;
  error?: string;
}

export function authErrorMessage(err: unknown): string {
  if (axios.isAxiosError(err)) {
    const ax = err as AxiosError<ProblemDetail>;
    if (ax.response?.status === 401) return 'Invalid email or password.';
    const body = ax.response?.data;
    if (body?.title) return body.title;
    if (body?.detail) return body.detail;
    if (ax.message) return ax.message;
  }
  if (err instanceof Error && err.message) return err.message;
  return 'Sign in failed. Please try again.';
}

export function setupErrorMessage(err: unknown): string {
  if (axios.isAxiosError(err)) {
    const ax = err as AxiosError<ProblemDetail>;
    const body = ax.response?.data;
    if (body?.error === 'SETUP_ALREADY_COMPLETED') {
      return 'Setup is already complete — please sign in.';
    }
    if (body?.error === 'EMAIL_ALREADY_EXISTS') {
      return 'A user with that email already exists.';
    }
    if (body?.title) return body.title;
    if (body?.detail) return body.detail;
    if (ax.message) return ax.message;
  }
  if (err instanceof Error && err.message) return err.message;
  return 'Could not complete setup. Please try again.';
}
