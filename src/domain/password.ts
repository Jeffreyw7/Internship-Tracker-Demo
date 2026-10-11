// Mirrors the server signup policy for immediate feedback; the backend remains authoritative.
export const PASSWORD_REQUIREMENTS = 'Use at least 15 characters. A long, unique passphrase is welcome; avoid common or compromised passwords.';

// Return the first policy error, counting Unicode code points rather than UTF-16 units.
export function passwordError(password: string): string | null {
  if (Array.from(password).length < 15) return PASSWORD_REQUIREMENTS;
  // Measure encoded bytes separately because BCrypt has a 72-byte input limit.
  if (new TextEncoder().encode(password).length > 72)
    return 'Password must fit within 72 UTF-8 bytes. Use fewer characters.';
  return null;
}
