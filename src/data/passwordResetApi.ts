import { authTransition, jsonBody, refreshAuthCsrf, request } from './api.ts';

export interface PasswordResetAccepted { status: 'PASSWORD_RESET_REQUEST_ACCEPTED'; message: string }
export interface PasswordResetComplete { status: 'PASSWORD_RESET_COMPLETE'; message: string }

export const passwordResetApi = {
  requestReset(email: string) {
    return authTransition(async isCurrent => {
      await refreshAuthCsrf(isCurrent);
      return request<PasswordResetAccepted>('/auth/password-reset/request',{
        method:'POST',...jsonBody({email:email.trim()}),
      },isCurrent);
    });
  },
  confirmReset(token: string, newPassword: string) {
    return authTransition(async isCurrent => {
      await refreshAuthCsrf(isCurrent);
      return request<PasswordResetComplete>('/auth/password-reset/confirm',{
        method:'POST',...jsonBody({token,newPassword}),
      },isCurrent);
    },true);
  },
};
