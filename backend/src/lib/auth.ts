import jwt from 'jsonwebtoken';
import type { FastifyReply, FastifyRequest } from 'fastify';
import type { Config } from '../config.js';
import type { Db } from '../db/pool.js';
import { Errors } from './errors.js';

export type Role = 'user' | 'moderator' | 'admin';
export interface AuthUser {
  id: string;
  username: string;
  role: Role;
  sessionId: string;
}

declare module 'fastify' {
  interface FastifyRequest {
    user: AuthUser | null;
  }
}

export function signAccessToken(config: Config, userId: string, sessionId: string, role: Role) {
  return jwt.sign({ sid: sessionId, role }, config.JWT_ACCESS_SECRET, {
    subject: userId,
    issuer: config.JWT_ISSUER,
    expiresIn: config.ACCESS_TOKEN_TTL_SECONDS,
    algorithm: 'HS256',
  });
}

/**
 * Resolve the bearer token (if any) to a user. Every request re-checks the DB so that
 * logout, suspension and role changes take effect immediately (not after token expiry).
 * Returns null when no Authorization header is present; throws 401 when one is present but bad.
 */
async function resolveUser(req: FastifyRequest, db: Db, config: Config): Promise<AuthUser | null> {
  const header = req.headers.authorization;
  if (!header) return null;
  const m = /^Bearer\s+(.+)$/i.exec(header);
  if (!m) throw Errors.invalidToken();
  let payload: jwt.JwtPayload;
  try {
    payload = jwt.verify(m[1], config.JWT_ACCESS_SECRET, {
      algorithms: ['HS256'],
      issuer: config.JWT_ISSUER,
    }) as jwt.JwtPayload;
  } catch {
    throw Errors.invalidToken();
  }
  if (typeof payload.sub !== 'string' || typeof payload.sid !== 'string') throw Errors.invalidToken();
  const { rows } = await db.query(
    `SELECT u.id, u.username, u.role, u.status
       FROM sessions s JOIN users u ON u.id = s.user_id
      WHERE s.id = $1 AND u.id = $2 AND s.revoked_at IS NULL AND s.expires_at > now()`,
    [payload.sid, payload.sub],
  );
  const row = rows[0];
  if (!row) throw Errors.invalidToken();
  if (row.status !== 'active') throw Errors.suspended();
  return { id: row.id, username: row.username, role: row.role, sessionId: payload.sid };
}

export function makeAuthHooks(db: Db, config: Config) {
  /** Public route: guests allowed; logged-in users get personalised fields. */
  const optionalAuth = async (req: FastifyRequest, _reply: FastifyReply) => {
    req.user = await resolveUser(req, db, config);
  };
  /** Login required. */
  const requireAuth = async (req: FastifyRequest, _reply: FastifyReply) => {
    req.user = await resolveUser(req, db, config);
    if (!req.user) throw Errors.unauthenticated();
  };
  const requireRole =
    (...roles: Role[]) =>
    async (req: FastifyRequest, reply: FastifyReply) => {
      await requireAuth(req, reply);
      if (!roles.includes(req.user!.role)) throw Errors.forbidden();
    };
  return { optionalAuth, requireAuth, requireRole };
}

/** Narrow req.user after requireAuth. */
export function me(req: FastifyRequest): AuthUser {
  if (!req.user) throw Errors.unauthenticated();
  return req.user;
}
