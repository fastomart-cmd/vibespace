import { z } from 'zod';
import type { FastifyInstance } from 'fastify';
import type { ZodTypeProvider } from 'fastify-type-provider-zod';
import { withTx } from '../db/pool.js';
import { AppError, Errors } from '../lib/errors.js';
import { me } from '../lib/auth.js';
import { writeLimit } from '../lib/limits.js';
import { decodeTimeCursor, pageByTime } from '../lib/cursor.js';
import { mapUserSummary } from '../lib/views.js';
import { loadUserWithRelation } from '../lib/relations.js';
import { PageQuery } from './users.js';

const UsernameParam = z.object({ username: z.string().min(1).max(30) });
const bearer = [{ bearerAuth: [] }];

export default async function socialRoutes(app0: FastifyInstance) {
  const app = app0.withTypeProvider<ZodTypeProvider>();
  const { db, storage, config } = app.deps;
  const { requireAuth } = app.deps.auth;
  const write = { onRequest: requireAuth, config: writeLimit(config) };

  /** Target must exist, be active, not be the viewer, and not be blocked either way. */
  async function target(username: string, viewerId: string, allowBlockedByMe = false) {
    const t = await loadUserWithRelation(db, username, viewerId);
    if (!t || t.row.status !== 'active' || t.row.blocked_me) throw Errors.notFound('User');
    if (t.isSelf) throw Errors.self();
    if (t.row.i_blocked && !allowBlockedByMe) throw Errors.blocked();
    return t.row;
  }

  async function counts(userId: string) {
    const { rows } = await db.query('SELECT vibemates_count, vibestars_count FROM users WHERE id = $1', [userId]);
    return { vibeMates: rows[0].vibemates_count, vibeStars: rows[0].vibestars_count };
  }

  // ------------------------------------------------------------ VibeMates
  app.post('/users/:username/vibemate', {
    schema: {
      tags: ['vibemates-vibestars'],
      summary: 'Become a VibeMate of this user (request if their account is private)',
      security: bearer,
      params: UsernameParam,
    },
    ...write,
  }, async (req) => {
    const viewer = me(req).id;
    const t = await target(req.params.username, viewer);
    const status = t.is_private ? 'pending' : 'accepted';
    await db.query(
      `INSERT INTO vibemates (from_user_id, to_user_id, status) VALUES ($1, $2, $3) ON CONFLICT (from_user_id, to_user_id) DO NOTHING`,
      [viewer, t.id, status],
    );
    const { rows } = await db.query('SELECT status FROM vibemates WHERE from_user_id = $1 AND to_user_id = $2', [viewer, t.id]);
    return { username: t.username, vibeMateStatus: rows[0].status, counts: await counts(t.id) };
  });

  app.delete('/users/:username/vibemate', {
    schema: { tags: ['vibemates-vibestars'], summary: 'Stop being a VibeMate (or cancel a pending request)', security: bearer, params: UsernameParam },
    ...write,
  }, async (req) => {
    const viewer = me(req).id;
    const t = await target(req.params.username, viewer, true);
    await db.query('DELETE FROM vibemates WHERE from_user_id = $1 AND to_user_id = $2', [viewer, t.id]);
    return { username: t.username, vibeMateStatus: 'none', counts: await counts(t.id) };
  });

  app.get('/me/vibemate-requests', {
    schema: { tags: ['vibemates-vibestars'], summary: 'Pending VibeMate requests to my private account', security: bearer, querystring: PageQuery },
    onRequest: requireAuth,
  }, async (req) => {
    const cur = decodeTimeCursor(req.query.cursor);
    const { rows } = await db.query(
      `SELECT x.id, x.created_at::text AS cursor_t, u.id AS user_id, u.username, u.display_name, am.object_key AS avatar_key
         FROM vibemates x JOIN users u ON u.id = x.from_user_id LEFT JOIN media am ON am.id = u.avatar_media_id
        WHERE x.to_user_id = $1 AND x.status = 'pending'
          AND ($2::timestamptz IS NULL OR (x.created_at, x.id) < ($2::timestamptz, $3::uuid))
        ORDER BY x.created_at DESC, x.id DESC LIMIT $4`,
      [me(req).id, cur?.t ?? null, cur?.id ?? null, req.query.limit + 1],
    );
    const page = pageByTime(rows, req.query.limit);
    return { items: page.items.map((r) => mapUserSummary({ ...r, id: r.user_id }, storage)), nextCursor: page.nextCursor };
  });

  app.post('/me/vibemate-requests/:username/accept', {
    schema: { tags: ['vibemates-vibestars'], summary: 'Accept a VibeMate request', security: bearer, params: UsernameParam },
    ...write,
  }, async (req) => {
    const viewer = me(req).id;
    const { rowCount } = await db.query(
      `UPDATE vibemates SET status = 'accepted'
        WHERE to_user_id = $1 AND status = 'pending' AND from_user_id = (SELECT id FROM users WHERE username = $2)`,
      [viewer, req.params.username.toLowerCase()],
    );
    if (!rowCount) throw Errors.notFound('VibeMate request');
    return { accepted: true, counts: await counts(viewer) };
  });

  app.delete('/me/vibemates/:username', {
    schema: {
      tags: ['vibemates-vibestars'],
      summary: 'Remove someone from my VibeMates, or decline their pending request',
      security: bearer,
      params: UsernameParam,
    },
    ...write,
  }, async (req) => {
    const viewer = me(req).id;
    const { rowCount } = await db.query(
      `DELETE FROM vibemates WHERE to_user_id = $1 AND from_user_id = (SELECT id FROM users WHERE username = $2)`,
      [viewer, req.params.username.toLowerCase()],
    );
    if (!rowCount) throw Errors.notFound('VibeMate');
    return { removed: true, counts: await counts(viewer) };
  });

  // ------------------------------------------------------------ VibeStars (channel subscriptions, separate table)
  app.post('/users/:username/vibestar', {
    schema: { tags: ['vibemates-vibestars'], summary: "Become a VibeStar of this creator's channel", security: bearer, params: UsernameParam },
    ...write,
  }, async (req) => {
    const viewer = me(req).id;
    const t = await target(req.params.username, viewer);
    if (t.is_private) throw new AppError(403, 'PRIVATE_CHANNEL');
    await db.query(`INSERT INTO vibestars (user_id, channel_id) VALUES ($1, $2) ON CONFLICT (user_id, channel_id) DO NOTHING`, [viewer, t.id]);
    return { username: t.username, isVibeStar: true, counts: await counts(t.id) };
  });

  app.delete('/users/:username/vibestar', {
    schema: { tags: ['vibemates-vibestars'], summary: 'Stop being a VibeStar of this channel', security: bearer, params: UsernameParam },
    ...write,
  }, async (req) => {
    const viewer = me(req).id;
    const t = await target(req.params.username, viewer, true);
    await db.query('DELETE FROM vibestars WHERE user_id = $1 AND channel_id = $2', [viewer, t.id]);
    return { username: t.username, isVibeStar: false, counts: await counts(t.id) };
  });

  // ------------------------------------------------------------ blocks & mutes
  app.post('/users/:username/block', {
    schema: {
      tags: ['safety'],
      summary: 'Block a user (removes VibeMate and VibeStar links both ways)',
      security: bearer,
      params: UsernameParam,
    },
    ...write,
  }, async (req) => {
    const viewer = me(req).id;
    const t = await target(req.params.username, viewer, true);
    await withTx(db, async (c) => {
      await c.query('INSERT INTO blocks (blocker_id, blocked_id) VALUES ($1, $2) ON CONFLICT DO NOTHING', [viewer, t.id]);
      await c.query('DELETE FROM vibemates WHERE (from_user_id = $1 AND to_user_id = $2) OR (from_user_id = $2 AND to_user_id = $1)', [viewer, t.id]);
      await c.query('DELETE FROM vibestars WHERE (user_id = $1 AND channel_id = $2) OR (user_id = $2 AND channel_id = $1)', [viewer, t.id]);
    });
    return { username: t.username, isBlocked: true };
  });

  app.delete('/users/:username/block', {
    schema: { tags: ['safety'], summary: 'Unblock a user', security: bearer, params: UsernameParam },
    ...write,
  }, async (req) => {
    const viewer = me(req).id;
    const t = await target(req.params.username, viewer, true);
    await db.query('DELETE FROM blocks WHERE blocker_id = $1 AND blocked_id = $2', [viewer, t.id]);
    return { username: t.username, isBlocked: false };
  });

  app.post('/users/:username/mute', {
    schema: { tags: ['safety'], summary: 'Mute a user (hides their posts from my feeds)', security: bearer, params: UsernameParam },
    ...write,
  }, async (req) => {
    const viewer = me(req).id;
    const t = await target(req.params.username, viewer, true);
    await db.query('INSERT INTO mutes (muter_id, muted_id) VALUES ($1, $2) ON CONFLICT DO NOTHING', [viewer, t.id]);
    return { username: t.username, isMuted: true };
  });

  app.delete('/users/:username/mute', {
    schema: { tags: ['safety'], summary: 'Unmute a user', security: bearer, params: UsernameParam },
    ...write,
  }, async (req) => {
    const viewer = me(req).id;
    const t = await target(req.params.username, viewer, true);
    await db.query('DELETE FROM mutes WHERE muter_id = $1 AND muted_id = $2', [viewer, t.id]);
    return { username: t.username, isMuted: false };
  });

  const myList = (path: string, summary: string, table: string, col: string, otherCol: string) =>
    app.get(path, { schema: { tags: ['safety'], summary, security: bearer, querystring: PageQuery }, onRequest: requireAuth }, async (req) => {
      const cur = decodeTimeCursor(req.query.cursor);
      const { rows } = await db.query(
        `SELECT x.id, x.created_at::text AS cursor_t, u.id AS user_id, u.username, u.display_name, am.object_key AS avatar_key
           FROM ${table} x JOIN users u ON u.id = x.${otherCol} LEFT JOIN media am ON am.id = u.avatar_media_id
          WHERE x.${col} = $1 AND ($2::timestamptz IS NULL OR (x.created_at, x.id) < ($2::timestamptz, $3::uuid))
          ORDER BY x.created_at DESC, x.id DESC LIMIT $4`,
        [me(req).id, cur?.t ?? null, cur?.id ?? null, req.query.limit + 1],
      );
      const page = pageByTime(rows, req.query.limit);
      return { items: page.items.map((r) => mapUserSummary({ ...r, id: r.user_id }, storage)), nextCursor: page.nextCursor };
    });
  myList('/me/blocks', 'Accounts I blocked', 'blocks', 'blocker_id', 'blocked_id');
  myList('/me/mutes', 'Accounts I muted', 'mutes', 'muter_id', 'muted_id');
}
