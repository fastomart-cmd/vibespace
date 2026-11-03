import { z } from 'zod';
import type { FastifyInstance } from 'fastify';
import type { ZodTypeProvider } from 'fastify-type-provider-zod';
import { withTx } from '../db/pool.js';
import { AppError, Errors } from '../lib/errors.js';
import { me } from '../lib/auth.js';
import { writeLimit } from '../lib/limits.js';
import { decodeTimeCursor, pageByTime } from '../lib/cursor.js';
import { POST_SELECT, USER_SELECT, mapPost, mapProfile, mapUserSummary, notBlocked, postVisible } from '../lib/views.js';
import { requireVisibleUser, viewerRelation } from '../lib/relations.js';

export const PageQuery = z.object({
  cursor: z.string().max(300).optional(),
  limit: z.coerce.number().int().min(1).max(50).default(20),
});
const UsernameParam = z.object({ username: z.string().min(1).max(30) });
const bearer = [{ bearerAuth: [] }];

export default async function userRoutes(app0: FastifyInstance) {
  const app = app0.withTypeProvider<ZodTypeProvider>();
  const { db, storage, config } = app.deps;
  const { requireAuth, optionalAuth } = app.deps.auth;

  async function loadMe(id: string) {
    const { rows } = await db.query(`${USER_SELECT} WHERE u.id = $1`, [id]);
    const extra = await db.query('SELECT email, role FROM users WHERE id = $1', [id]);
    return { ...mapProfile(rows[0], storage), language: rows[0].language, email: extra.rows[0].email, role: extra.rows[0].role };
  }

  // ------------------------------------------------------------ me
  app.get('/me', { schema: { tags: ['me'], summary: 'Current user', security: bearer }, onRequest: requireAuth }, async (req) =>
    loadMe(me(req).id),
  );

  app.patch('/me', {
    schema: {
      tags: ['me'],
      summary: 'Update profile (display name, bio, avatar, privacy, language)',
      description: 'Set avatarMediaId to the id returned by POST /media/uploads (purpose=avatar) after uploading the file, or null to remove it.',
      security: bearer,
      body: z
        .object({
          displayName: z.string().trim().min(1).max(60),
          bio: z.string().max(300),
          isPrivate: z.boolean(),
          language: z.enum(['en', 'hi']),
          avatarMediaId: z.string().uuid().nullable(),
        })
        .partial()
        .strict(),
    },
    onRequest: requireAuth,
    config: writeLimit(config),
  }, async (req) => {
    const uid = me(req).id;
    const b = req.body;
    await withTx(db, async (c) => {
      if (b.avatarMediaId) {
        const { rows } = await c.query(`SELECT id FROM media WHERE id = $1 AND owner_id = $2 AND purpose = 'avatar'`, [b.avatarMediaId, uid]);
        if (!rows[0]) throw new AppError(400, 'MEDIA_INVALID');
        await c.query(`UPDATE media SET status = 'attached' WHERE id = $1`, [b.avatarMediaId]);
      }
      const sets: string[] = [];
      const vals: unknown[] = [uid];
      const add = (col: string, val: unknown) => {
        vals.push(val);
        sets.push(`${col} = $${vals.length}`);
      };
      if (b.displayName !== undefined) add('display_name', b.displayName);
      if (b.bio !== undefined) add('bio', b.bio);
      if (b.isPrivate !== undefined) add('is_private', b.isPrivate);
      if (b.language !== undefined) add('language', b.language);
      if (b.avatarMediaId !== undefined) add('avatar_media_id', b.avatarMediaId);
      if (sets.length) await c.query(`UPDATE users SET ${sets.join(', ')}, updated_at = now() WHERE id = $1`, vals);
      // Going public auto-accepts pending VibeMate requests.
      if (b.isPrivate === false) await c.query(`UPDATE vibemates SET status = 'accepted' WHERE to_user_id = $1 AND status = 'pending'`, [uid]);
    });
    return loadMe(uid);
  });

  app.get('/me/saves', {
    schema: { tags: ['me'], summary: 'Posts I saved', security: bearer, querystring: PageQuery },
    onRequest: requireAuth,
  }, async (req) => {
    const uid = me(req).id;
    const cur = decodeTimeCursor(req.query.cursor);
    const { rows } = await db.query(
      `${POST_SELECT(1).replace('SELECT p.id,', 'SELECT sv.created_at::text AS saved_t, p.id,')}
         JOIN saves sv ON sv.post_id = p.id AND sv.user_id = $1
        WHERE ${postVisible(1)}
          AND ($2::timestamptz IS NULL OR (sv.created_at, p.id) < ($2::timestamptz, $3::uuid))
        ORDER BY sv.created_at DESC, p.id DESC LIMIT $4`,
      [uid, cur?.t ?? null, cur?.id ?? null, req.query.limit + 1],
    );
    const page = pageByTime(rows.map((r) => ({ ...r, cursor_t: r.saved_t })), req.query.limit);
    return { items: page.items.map((r) => mapPost(r, storage, uid)), nextCursor: page.nextCursor };
  });

  // ------------------------------------------------------------ public profiles (guest OK)
  app.get('/users/:username', {
    schema: {
      tags: ['users'],
      summary: 'Profile by username (guest OK; private profiles are hidden from guests)',
      params: UsernameParam,
    },
    onRequest: optionalAuth,
  }, async (req) => {
    const viewer = req.user?.id ?? null;
    const t = await requireVisibleUser(db, req.params.username, viewer);
    return {
      ...mapProfile(t.row, storage),
      canViewContent: t.canSeeContent,
      viewer: viewer && !t.isSelf ? viewerRelation(t.row) : null,
    };
  });

  app.get('/users/:username/posts', {
    schema: { tags: ['users'], summary: "A user's posts, newest first (guest OK for public accounts)", params: UsernameParam, querystring: PageQuery },
    onRequest: optionalAuth,
  }, async (req) => {
    const viewer = req.user?.id ?? null;
    const t = await requireVisibleUser(db, req.params.username, viewer);
    if (!t.canSeeContent) throw Errors.forbidden();
    const cur = decodeTimeCursor(req.query.cursor);
    const { rows } = await db.query(
      `${POST_SELECT(1)} WHERE p.author_id = $2 AND ${postVisible(1)}
         AND ($3::timestamptz IS NULL OR (p.created_at, p.id) < ($3::timestamptz, $4::uuid))
       ORDER BY p.created_at DESC, p.id DESC LIMIT $5`,
      [viewer, t.row.id, cur?.t ?? null, cur?.id ?? null, req.query.limit + 1],
    );
    const page = pageByTime(rows, req.query.limit);
    return { items: page.items.map((r) => mapPost(r, storage, viewer)), nextCursor: page.nextCursor };
  });

  // Lists of accounts. Counts are always on the profile; the lists follow content visibility.
  const listRoute = (path: string, summary: string, sql: string) =>
    app.get(path, {
      schema: { tags: ['vibemates-vibestars'], summary, params: UsernameParam, querystring: PageQuery },
      onRequest: optionalAuth,
    }, async (req) => {
      const viewer = req.user?.id ?? null;
      const t = await requireVisibleUser(db, req.params.username, viewer);
      if (!t.canSeeContent) throw Errors.forbidden();
      const cur = decodeTimeCursor(req.query.cursor);
      const { rows } = await db.query(sql, [t.row.id, cur?.t ?? null, cur?.id ?? null, req.query.limit + 1, viewer]);
      const page = pageByTime(rows, req.query.limit);
      return { items: page.items.map((r) => mapUserSummary({ ...r, id: r.user_id }, storage)), nextCursor: page.nextCursor };
    });

  const listSql = (table: string, joinCol: string, whereCol: string, extra = '') => `
    SELECT x.created_at::text AS cursor_t, x.id, u.id AS user_id, u.username, u.display_name, am.object_key AS avatar_key
      FROM ${table} x JOIN users u ON u.id = x.${joinCol} LEFT JOIN media am ON am.id = u.avatar_media_id
     WHERE x.${whereCol} = $1 ${extra} AND u.status = 'active' AND ${notBlocked(5, 'u.id')}
       AND ($2::timestamptz IS NULL OR (x.created_at, x.id) < ($2::timestamptz, $3::uuid))
     ORDER BY x.created_at DESC, x.id DESC LIMIT $4`;

  listRoute('/users/:username/vibemates', 'Accounts that VibeMate this user', listSql('vibemates', 'from_user_id', 'to_user_id', `AND x.status = 'accepted'`));
  listRoute('/users/:username/vibing-with', 'Accounts this user VibeMates', listSql('vibemates', 'to_user_id', 'from_user_id', `AND x.status = 'accepted'`));
  listRoute('/users/:username/vibestars', "VibeStars of this user's channel", listSql('vibestars', 'user_id', 'channel_id'));
}
