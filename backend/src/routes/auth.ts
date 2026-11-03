import { z } from 'zod';
import type { FastifyInstance } from 'fastify';
import type { ZodTypeProvider } from 'fastify-type-provider-zod';
import { withTx } from '../db/pool.js';
import { AppError, Errors } from '../lib/errors.js';
import { dummyVerify, hashPassword, randomToken, sha256, verifyPassword } from '../lib/crypto.js';
import { signAccessToken, me, type Role } from '../lib/auth.js';
import { authLimit } from '../lib/limits.js';
import { USER_SELECT, mapProfile } from '../lib/views.js';

export const RESERVED_USERNAMES = new Set([
  'admin', 'administrator', 'api', 'u', 'me', 'root', 'support', 'help', 'vibespace', 'vibe', 'docs',
  'settings', 'login', 'signup', 'explore', 'trending', 'feed', 'moderator', 'system', 'null', 'undefined',
]);

export const UsernameSchema = z
  .string()
  .trim()
  .toLowerCase()
  .regex(/^[a-z0-9_.]{3,30}$/, 'Username must be 3–30 characters: a–z, 0–9, _ or .')
  .refine((u) => !/^\.|\.$|\.\./.test(u), 'Username cannot start/end with a dot or contain ".."');
const PasswordSchema = z.string().min(8, 'Password must be at least 8 characters').max(128);
const EmailSchema = z.string().trim().toLowerCase().email().max(254);

export default async function authRoutes(app0: FastifyInstance) {
  const app = app0.withTypeProvider<ZodTypeProvider>();
  const { db, config, mailer, storage } = app.deps;
  const { requireAuth } = app.deps.auth;

  async function issueSession(client: { query: typeof db.query }, userId: string, role: Role, ua?: string, ip?: string) {
    const refreshToken = randomToken(48);
    const { rows } = await client.query(
      `INSERT INTO sessions (user_id, refresh_token_hash, user_agent, ip, expires_at)
       VALUES ($1, $2, $3, $4, now() + make_interval(days => $5)) RETURNING id`,
      [userId, sha256(refreshToken), ua?.slice(0, 300) ?? null, ip ?? null, config.REFRESH_TOKEN_TTL_DAYS],
    );
    const sessionId = rows[0].id as string;
    return {
      sessionId,
      accessToken: signAccessToken(config, userId, sessionId, role),
      refreshToken,
      tokenType: 'Bearer' as const,
      expiresIn: config.ACCESS_TOKEN_TTL_SECONDS,
    };
  }

  async function loadMe(userId: string) {
    const { rows } = await db.query(`${USER_SELECT} WHERE u.id = $1`, [userId]);
    const r = rows[0];
    return { ...mapProfile(r, storage), language: r.language };
  }

  app.get('/username-available', {
    schema: { tags: ['auth'], summary: 'Check whether a username is free', querystring: z.object({ username: UsernameSchema }) },
    config: authLimit(config),
  }, async (req) => {
    const u = req.query.username;
    if (RESERVED_USERNAMES.has(u)) return { username: u, available: false };
    const { rowCount } = await db.query('SELECT 1 FROM users WHERE username = $1', [u]);
    return { username: u, available: rowCount === 0 };
  });

  app.post('/signup', {
    schema: {
      tags: ['auth'],
      summary: 'Create an account',
      body: z.object({
        email: EmailSchema,
        username: UsernameSchema,
        password: PasswordSchema,
        displayName: z.string().trim().min(1).max(60).optional(),
        language: z.enum(['en', 'hi']).default('en'),
      }),
    },
    config: authLimit(config),
  }, async (req, reply) => {
    const b = req.body;
    if (RESERVED_USERNAMES.has(b.username)) throw new AppError(409, 'USERNAME_TAKEN');
    const hash = await hashPassword(b.password);
    const out = await withTx(db, async (c) => {
      let userId: string;
      try {
        const { rows } = await c.query(
          `INSERT INTO users (email, username, password_hash, display_name, language)
           VALUES ($1, $2, $3, $4, $5) RETURNING id`,
          [b.email, b.username, hash, b.displayName ?? b.username, b.language],
        );
        userId = rows[0].id;
      } catch (e: any) {
        if (e.code === '23505' && e.constraint === 'users_username_uq') throw new AppError(409, 'USERNAME_TAKEN');
        if (e.code === '23505' && e.constraint === 'users_email_lower_uq') throw new AppError(409, 'EMAIL_TAKEN');
        throw e;
      }
      return { userId, tokens: await issueSession(c, userId, 'user', req.headers['user-agent'], req.ip) };
    });
    reply.status(201);
    const { sessionId: _s, ...tokens } = out.tokens;
    return { user: await loadMe(out.userId), ...tokens };
  });

  app.post('/login', {
    schema: {
      tags: ['auth'],
      summary: 'Log in with email or username',
      body: z.object({ identifier: z.string().trim().toLowerCase().min(3).max(254), password: z.string().min(1).max(128) }),
    },
    config: authLimit(config),
  }, async (req) => {
    const { identifier, password } = req.body;
    const { rows } = await db.query(
      `SELECT id, password_hash, role, status FROM users WHERE ${identifier.includes('@') ? 'lower(email)' : 'username'} = $1`,
      [identifier],
    );
    const u = rows[0];
    if (!u) {
      await dummyVerify(password);
      throw Errors.invalidCredentials();
    }
    if (!(await verifyPassword(u.password_hash, password))) throw Errors.invalidCredentials();
    if (u.status !== 'active') throw Errors.suspended();
    const { sessionId: _s, ...tokens } = await issueSession(db, u.id, u.role, req.headers['user-agent'], req.ip);
    return { user: await loadMe(u.id), ...tokens };
  });

  app.post('/refresh', {
    schema: {
      tags: ['auth'],
      summary: 'Rotate a refresh token for a new access + refresh token pair',
      description: 'Refresh tokens are single-use. Re-using an already rotated token revokes every session of that account.',
      body: z.object({ refreshToken: z.string().min(20).max(200) }),
    },
    config: authLimit(config),
  }, async (req) => {
    const hash = sha256(req.body.refreshToken);
    const result = await withTx(db, async (c) => {
      const { rows } = await c.query(
        `SELECT s.id, s.user_id, s.revoked_at, s.expires_at < now() AS expired, u.role, u.status
           FROM sessions s JOIN users u ON u.id = s.user_id WHERE s.refresh_token_hash = $1 FOR UPDATE OF s`,
        [hash],
      );
      const s = rows[0];
      if (!s) return { error: Errors.invalidToken() };
      if (s.revoked_at) {
        // Token reuse: likely stolen. Kill every session for this user (committed, then 401).
        await c.query('UPDATE sessions SET revoked_at = now() WHERE user_id = $1 AND revoked_at IS NULL', [s.user_id]);
        return { error: Errors.invalidToken() };
      }
      if (s.expired) return { error: Errors.invalidToken() };
      if (s.status !== 'active') return { error: Errors.suspended() };
      const next = await issueSession(c, s.user_id, s.role, req.headers['user-agent'], req.ip);
      await c.query('UPDATE sessions SET revoked_at = now(), replaced_by = $2, last_used_at = now() WHERE id = $1', [s.id, next.sessionId]);
      const { sessionId: _s, ...tokens } = next;
      return { tokens };
    });
    if ('error' in result) throw result.error;
    return result.tokens;
  });

  app.post('/logout', {
    schema: { tags: ['auth'], summary: 'Revoke one session by its refresh token', body: z.object({ refreshToken: z.string().min(20).max(200) }) },
    config: authLimit(config),
  }, async (_req, reply) => {
    await db.query('UPDATE sessions SET revoked_at = now() WHERE refresh_token_hash = $1 AND revoked_at IS NULL', [sha256(_req.body.refreshToken)]);
    reply.status(204);
  });

  app.post('/logout-all', {
    schema: { tags: ['auth'], summary: 'Revoke every session of the current user', security: [{ bearerAuth: [] }] },
    onRequest: requireAuth,
  }, async (req, reply) => {
    await db.query('UPDATE sessions SET revoked_at = now() WHERE user_id = $1 AND revoked_at IS NULL', [me(req).id]);
    reply.status(204);
  });

  app.post('/password/forgot', {
    schema: {
      tags: ['auth'],
      summary: 'Email a password-reset link',
      description: 'Always returns 202 so the endpoint cannot be used to discover which emails have accounts.',
      body: z.object({ email: EmailSchema }),
    },
    config: authLimit(config),
  }, async (req, reply) => {
    const { rows } = await db.query(`SELECT id, display_name, language FROM users WHERE lower(email) = $1 AND status = 'active'`, [req.body.email]);
    const u = rows[0];
    if (u) {
      const token = randomToken(32);
      await withTx(db, async (c) => {
        await c.query('UPDATE password_reset_tokens SET used_at = now() WHERE user_id = $1 AND used_at IS NULL', [u.id]);
        await c.query(
          `INSERT INTO password_reset_tokens (user_id, token_hash, expires_at) VALUES ($1, $2, now() + make_interval(mins => $3))`,
          [u.id, sha256(token), config.PASSWORD_RESET_TTL_MINUTES],
        );
      });
      const link = `${config.PASSWORD_RESET_URL}${token}`;
      const hi = u.language === 'hi';
      try {
        await mailer.send({
          to: req.body.email,
          subject: hi ? 'VibeSpace पासवर्ड रीसेट करें' : 'Reset your VibeSpace password',
          text: hi
            ? `नमस्ते ${u.display_name},\n\nअपना पासवर्ड रीसेट करने के लिए यह लिंक खोलें (${config.PASSWORD_RESET_TTL_MINUTES} मिनट तक मान्य):\n${link}\n\nअगर आपने यह अनुरोध नहीं किया, तो इस ईमेल को अनदेखा करें।`
            : `Hi ${u.display_name},\n\nOpen this link to reset your password (valid for ${config.PASSWORD_RESET_TTL_MINUTES} minutes):\n${link}\n\nIf you didn't ask for this, ignore this email.`,
        });
      } catch (e) {
        req.log.error({ err: e }, 'password reset email failed');
      }
    }
    reply.status(202);
    return { ok: true };
  });

  app.post('/password/reset', {
    schema: {
      tags: ['auth'],
      summary: 'Set a new password with a reset token (signs out all sessions)',
      body: z.object({ token: z.string().min(20).max(200), newPassword: PasswordSchema }),
    },
    config: authLimit(config),
  }, async (req, reply) => {
    const hash = await hashPassword(req.body.newPassword);
    await withTx(db, async (c) => {
      const { rows } = await c.query(
        `UPDATE password_reset_tokens SET used_at = now()
          WHERE token_hash = $1 AND used_at IS NULL AND expires_at > now() RETURNING user_id`,
        [sha256(req.body.token)],
      );
      if (!rows[0]) throw Errors.invalidToken();
      await c.query('UPDATE users SET password_hash = $2, updated_at = now() WHERE id = $1', [rows[0].user_id, hash]);
      await c.query('UPDATE sessions SET revoked_at = now() WHERE user_id = $1 AND revoked_at IS NULL', [rows[0].user_id]);
    });
    reply.status(204);
  });
}
