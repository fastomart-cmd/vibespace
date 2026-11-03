import type { Config } from '../config.js';

/** Strict per-IP limit for credential endpoints (signup/login/refresh/reset). */
export const authLimit = (c: Config) => ({ rateLimit: { max: c.RATE_LIMIT_AUTH_PER_MIN, timeWindow: '1 minute' } });

/** Per-user limit for write actions; runs after auth so the key is the user id. */
export const writeLimit = (c: Config) => ({
  rateLimit: { max: c.RATE_LIMIT_WRITE_PER_MIN, timeWindow: '1 minute', hook: 'preHandler' as const },
});
