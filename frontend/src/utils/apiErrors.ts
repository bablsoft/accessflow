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

export function reviewErrorMessage(err: unknown): string {
  if (axios.isAxiosError(err)) {
    const ax = err as AxiosError<ProblemDetail>;
    const body = ax.response?.data;
    const code = body?.error;
    if (code === 'FORBIDDEN' && ax.response?.status === 403) {
      return i18n.t('errors.review_self_forbidden');
    }
    if (code === 'REVIEWER_NOT_ELIGIBLE') {
      return i18n.t('errors.reviewer_not_eligible');
    }
    if (code === 'QUERY_NOT_PENDING_REVIEW' || code === 'ILLEGAL_STATUS_TRANSITION') {
      return i18n.t('errors.review_query_not_pending');
    }
    if (code === 'QUERY_REQUEST_NOT_FOUND') {
      return i18n.t('errors.review_query_not_found');
    }
    if (body?.title) return body.title;
    if (body?.detail) return body.detail;
    if (ax.message) return ax.message;
  }
  if (err instanceof Error && err.message) return err.message;
  return i18n.t('errors.review_generic');
}

export function reviewPlanErrorMessage(err: unknown): string {
  if (axios.isAxiosError(err)) {
    const ax = err as AxiosError<ProblemDetail>;
    const body = ax.response?.data;
    const code = body?.error;
    if (code === 'REVIEW_PLAN_IN_USE') {
      return i18n.t('errors.review_plan_in_use');
    }
    if (code === 'REVIEW_PLAN_NOT_FOUND') {
      return i18n.t('errors.review_plan_not_found');
    }
    if (code === 'ILLEGAL_REVIEW_PLAN') {
      return i18n.t('errors.illegal_review_plan');
    }
    if (body?.title) return body.title;
    if (body?.detail) return body.detail;
    if (ax.message) return ax.message;
  }
  if (err instanceof Error && err.message) return err.message;
  return i18n.t('errors.review_plan_generic');
}
