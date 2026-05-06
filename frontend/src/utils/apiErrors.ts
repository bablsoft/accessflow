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
