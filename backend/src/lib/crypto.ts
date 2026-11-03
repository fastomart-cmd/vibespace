import { createHash, randomBytes } from 'node:crypto';
import argon2 from 'argon2';

export const sha256 = (s: string) => createHash('sha256').update(s).digest('hex');
export const randomToken = (bytes = 32) => randomBytes(bytes).toString('base64url');

// argon2id with OWASP-recommended minimums (19 MiB, t=2, p=1).
const ARGON_OPTS = { type: argon2.argon2id, memoryCost: 19456, timeCost: 2, parallelism: 1 } as const;
export const hashPassword = (pw: string) => argon2.hash(pw, ARGON_OPTS);
export const verifyPassword = async (hash: string, pw: string) => {
  try {
    return await argon2.verify(hash, pw);
  } catch {
    return false;
  }
};
// Precomputed hash used to keep login timing similar when the user doesn't exist.
let dummy: string | undefined;
export async function dummyVerify(pw: string) {
  dummy ??= await hashPassword('timing-equaliser-not-a-real-password');
  await verifyPassword(dummy, pw);
}
