export const PASSWORD_REQUIREMENTS = 'Use at least 12 characters, including an uppercase letter, a lowercase letter, a number, and a symbol.';

export function passwordError(password: string): string | null {
  if (Array.from(password).length < 12 || !/\p{Lu}/u.test(password)
      || !/\p{Ll}/u.test(password) || !/\p{Nd}/u.test(password)
      || !/[\p{P}\p{S}]/u.test(password)) return PASSWORD_REQUIREMENTS;
  if (new TextEncoder().encode(password).length > 72)
    return 'Password must fit within 72 UTF-8 bytes. Use fewer characters.';
  return null;
}
