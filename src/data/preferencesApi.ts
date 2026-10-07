import { request, jsonBody } from './api.ts';
import type { ThemePreference } from '../theme/theme.ts';

function preferenceFrom(value: {themePreference: unknown}): ThemePreference {
  if (!['light', 'dark', 'system'].includes(value.themePreference as string)) throw new Error('Invalid account appearance response.');
  return value.themePreference as ThemePreference;
}
export const preferencesApi = {
  async read(isCurrent:()=>boolean=()=>true) { return preferenceFrom(await request<{themePreference: unknown}>('/me/preferences', {}, isCurrent)); },
  async write(themePreference: ThemePreference, isCurrent:()=>boolean=()=>true) {
    return preferenceFrom(await request<{themePreference: unknown}>('/me/preferences', {method: 'PUT', ...jsonBody({themePreference})}, isCurrent));
  },
};
