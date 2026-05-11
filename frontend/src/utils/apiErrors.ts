import axios, { AxiosError } from 'axios';
import i18n from '../i18n';

interface ProblemDetail {
  title?: string;
  detail?: string;
  error?: string;
  reason?: string;
  dbType?: string;
}

export function authErrorMessage(err: unknown): string {
  if (axios.isAxiosError(err)) {
    const ax = err as AxiosError<ProblemDetail>;
    const code = ax.response?.data?.error;
    if (code === 'TOTP_INVALID') return i18n.t('errors.totp_invalid');
    if (code === 'TOTP_REQUIRED') return i18n.t('errors.totp_required');
    if (ax.response?.status === 401) return i18n.t('errors.auth_invalid');
    const body = ax.response?.data;
    if (body?.title) return body.title;
    if (body?.detail) return body.detail;
    if (ax.message) return ax.message;
  }
  if (err instanceof Error && err.message) return err.message;
  return i18n.t('errors.auth_generic');
}

export function isTotpRequiredError(err: unknown): boolean {
  if (!axios.isAxiosError(err)) return false;
  const ax = err as AxiosError<ProblemDetail>;
  return ax.response?.data?.error === 'TOTP_REQUIRED';
}

export function isTotpInvalidError(err: unknown): boolean {
  if (!axios.isAxiosError(err)) return false;
  const ax = err as AxiosError<ProblemDetail>;
  return ax.response?.data?.error === 'TOTP_INVALID';
}

export function profileErrorMessage(err: unknown): string {
  if (axios.isAxiosError(err)) {
    const ax = err as AxiosError<ProblemDetail>;
    const code = ax.response?.data?.error;
    if (code === 'PASSWORD_INCORRECT') return i18n.t('errors.password_incorrect');
    if (code === 'PASSWORD_CHANGE_NOT_ALLOWED') return i18n.t('errors.password_change_not_allowed');
    if (code === 'TOTP_NOT_ENABLED') return i18n.t('errors.totp_not_enabled');
    if (code === 'TOTP_ALREADY_ENABLED') return i18n.t('errors.totp_already_enabled');
    if (code === 'TOTP_INVALID_CODE') return i18n.t('errors.totp_invalid_code');
    const body = ax.response?.data;
    if (body?.title) return body.title;
    if (body?.detail) return body.detail;
    if (ax.message) return ax.message;
  }
  if (err instanceof Error && err.message) return err.message;
  return i18n.t('errors.profile_generic');
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

export function adminErrorMessage(err: unknown): string {
  if (axios.isAxiosError(err)) {
    const ax = err as AxiosError<ProblemDetail>;
    const body = ax.response?.data;
    const code = body?.error;
    if (code === 'EMAIL_ALREADY_EXISTS') return i18n.t('errors.email_already_exists');
    if (code === 'USER_NOT_FOUND') return i18n.t('errors.user_not_found');
    if (code === 'ILLEGAL_USER_OPERATION') return i18n.t('errors.illegal_user_operation_admin');
    if (code === 'NOTIFICATION_CHANNEL_CONFIG_INVALID') {
      return i18n.t('errors.notification_channel_config_invalid');
    }
    if (code === 'NOTIFICATION_CHANNEL_NOT_FOUND') {
      return i18n.t('errors.notification_channel_not_found');
    }
    if (code === 'NOTIFICATION_DELIVERY_FAILED') {
      return i18n.t('errors.notification_delivery_failed');
    }
    if (code === 'BAD_AUDIT_QUERY') return i18n.t('errors.bad_audit_query');
    if (body?.title) return body.title;
    if (body?.detail) return body.detail;
    if (ax.message) return ax.message;
  }
  if (err instanceof Error && err.message) return err.message;
  return i18n.t('errors.admin_generic');
}

export function datasourceGrantErrorMessage(err: unknown): string {
  if (axios.isAxiosError(err)) {
    const ax = err as AxiosError<ProblemDetail>;
    const body = ax.response?.data;
    const code = body?.error;
    if (code === 'DATASOURCE_PERMISSION_ALREADY_EXISTS') {
      return i18n.t('errors.datasource_permission_already_exists');
    }
    if (code === 'ILLEGAL_DATASOURCE_PERMISSION') {
      return i18n.t('errors.illegal_datasource_permission');
    }
    if (body?.title) return body.title;
    if (body?.detail) return body.detail;
    if (ax.message) return ax.message;
  }
  if (err instanceof Error && err.message) return err.message;
  return i18n.t('errors.datasource_grant_generic');
}

export function datasourceCreateErrorMessage(err: unknown): string | null {
  if (axios.isAxiosError(err)) {
    const ax = err as AxiosError<ProblemDetail>;
    const body = ax.response?.data;
    if (body?.error === 'DATASOURCE_DRIVER_UNAVAILABLE') {
      const dbType = body.dbType ?? '';
      switch (body.reason) {
        case 'OFFLINE_CACHE_MISS':
          return i18n.t('errors.driver_unavailable.offline_cache_miss', {
            dbType,
            detail: body.detail ?? '',
          });
        case 'CACHE_NOT_WRITABLE':
          return i18n.t('errors.driver_unavailable.cache_not_writable', {
            dbType,
            detail: body.detail ?? '',
          });
        case 'DOWNLOAD_FAILED':
          return i18n.t('errors.driver_unavailable.download_failed', {
            dbType,
            detail: body.detail ?? '',
          });
        case 'CHECKSUM_MISMATCH':
          return i18n.t('errors.driver_unavailable.checksum_mismatch', {
            dbType,
            detail: body.detail ?? '',
          });
        default:
          return i18n.t('errors.driver_unavailable.unavailable', {
            dbType,
            detail: body.detail ?? '',
          });
      }
    }
    if (body?.title) return body.title;
    if (body?.detail) return body.detail;
    if (ax.message) return ax.message;
  }
  if (err instanceof Error && err.message) return err.message;
  return null;
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
