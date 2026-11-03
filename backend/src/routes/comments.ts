import { z } from 'zod';
import type { FastifyInstance } from 'fastify';
import type { ZodTypeProvider } from 'fastify-type-provider-zod';
import { withTx } from '../db/pool.js';
import { AppError, Errors } from '../lib/errors.js';
import { me } from '../lib/auth.js';
import { writeLimit } from '../lib/limits.js';
import { decodeTimeCursor, pageByTime } from '../lib/cursor.js';
import { notBlocked } from '../lib/views.js';
import { getVisiblePost } from '../lib/posts.js';
import { PageQuery } from './users.js';

const IdParam = z.object({ id: z.string().uuid() });
const bearer = [{ bearerAuth: [] }];
const Body = z.string().trim().min(1).max(1000);

// Deleted/removed comments that still have replies are kept as placeholders so threads stay intact.
const COMMENT_SELECT = (vi: number) => `
SELECT c.id, c.post_id, c.parent_id, c.status, c.is_pinned, c.reply_count, c.edited_at, c.created_at,
       c.created_at::text AS cursor_t,
       CASE WHEN c.status = 'active' THEN c.body END AS body,
       u.id AS author_id, u.username AS author_username, u.display_name AS author_display_name, am.object_key AS author_avatar_key
  FROM comments c JOIN users u ON u.id = c.author_id LEFT JOIN media am ON am.id = u.avatar_media_id
 WHERE (c.status = 'active' OR c.reply_count > 0) AND u.status = 'active' AND ${notBlocked(vi, 'u.id')}`;

export default async function commentRoutes(app0: FastifyInstance) {
  const app = app0.withTypeProvider<ZodTypeProvider>();
  const { db, storage, config } = app.deps;
  const { requireAuth, optionalAuth } = app.deps.auth;
  const write = { onRequest: requireAuth, config: writeLimit(config) };

  const mapComment = (r: any, viewer: string | null, postAuthorId?: string) => ({
    id: r.id,
    postId: r.post_id,
    parentId: r.parent_id,
    body: r.body,
    isDeleted: r.status !== 'active',
    isPinned: r.is_pinned,
    replyCount: r.reply_count,
    author:
      r.status === 'active'
        ? {
            id: r.author_id,
            username: r.author_username,
            displayName: r.author_display_name,
            avatarUrl: r.author_avatar_key ? storage.publicUrl(r.author_avatar_key) : null,
          }
        : null,
    editedAt: r.edited_at,
    createdAt: r.created_at,
    viewer: viewer
      ? { isAuthor: r.author_id === viewer && r.status === 'active', canPin: postAuthorId === viewer && !r.parent_id && r.status === 'active' }
      : null,
  });

  app.get('/posts/:id/comments', {
    schema: {
      tags: ['vibetalk'],
      summary: 'Read top-level VibeTalk on a post, oldest first (guest OK). The pinned comment is returned separately on the first page.',
      params: IdParam,
      querystring: PageQuery,
    },
    onRequest: optionalAuth,
  }, async (req) => {
    const viewer = req.user?.id ?? null;
    const post = await getVisiblePost(db, req.params.id, viewer);
    const cur = decodeTimeCursor(req.query.cursor);
    const { rows } = await db.query(
      `${COMMENT_SELECT(1)} AND c.post_id = $2 AND c.parent_id IS NULL AND NOT c.is_pinned
         AND ($3::timestamptz IS NULL OR (c.created_at, c.id) > ($3::timestamptz, $4::uuid))
       ORDER BY c.created_at ASC, c.id ASC LIMIT $5`,
      [viewer, req.params.id, cur?.t ?? null, cur?.id ?? null, req.query.limit + 1],
    );
    let pinned = null;
    if (!cur) {
      const p = await db.query(`${COMMENT_SELECT(1)} AND c.post_id = $2 AND c.is_pinned`, [viewer, req.params.id]);
      if (p.rows[0]) pinned = mapComment(p.rows[0], viewer, post.author_id);
    }
    const page = pageByTime(rows, req.query.limit);
    return { pinned, items: page.items.map((r) => mapComment(r, viewer, post.author_id)), nextCursor: page.nextCursor };
  });

  app.get('/comments/:id/replies', {
    schema: { tags: ['vibetalk'], summary: 'Read replies to a comment, oldest first (guest OK)', params: IdParam, querystring: PageQuery },
    onRequest: optionalAuth,
  }, async (req) => {
    const viewer = req.user?.id ?? null;
    const { rows: parent } = await db.query('SELECT post_id FROM comments WHERE id = $1', [req.params.id]);
    if (!parent[0]) throw Errors.notFound('Comment');
    const post = await getVisiblePost(db, parent[0].post_id, viewer);
    const cur = decodeTimeCursor(req.query.cursor);
    const { rows } = await db.query(
      `${COMMENT_SELECT(1)} AND c.parent_id = $2
         AND ($3::timestamptz IS NULL OR (c.created_at, c.id) > ($3::timestamptz, $4::uuid))
       ORDER BY c.created_at ASC, c.id ASC LIMIT $5`,
      [viewer, req.params.id, cur?.t ?? null, cur?.id ?? null, req.query.limit + 1],
    );
    const page = pageByTime(rows, req.query.limit);
    return { items: page.items.map((r) => mapComment(r, viewer, post.author_id)), nextCursor: page.nextCursor };
  });

  app.post('/posts/:id/comments', {
    schema: {
      tags: ['vibetalk'],
      summary: 'Add VibeTalk (comment or nested reply) — login required',
      security: bearer,
      params: IdParam,
      body: z.object({ body: Body, parentId: z.string().uuid().optional() }),
    },
    ...write,
  }, async (req, reply) => {
    const uid = me(req).id;
    const post = await getVisiblePost(db, req.params.id, uid);
    if (req.body.parentId) {
      const { rows } = await db.query(`SELECT post_id, status FROM comments WHERE id = $1`, [req.body.parentId]);
      if (!rows[0] || rows[0].post_id !== req.params.id || rows[0].status !== 'active') throw Errors.notFound('Parent comment');
    }
    const { rows } = await db.query(
      `INSERT INTO comments (post_id, author_id, parent_id, body) VALUES ($1, $2, $3, $4) RETURNING id`,
      [req.params.id, uid, req.body.parentId ?? null, req.body.body],
    );
    const c = await db.query(`${COMMENT_SELECT(1)} AND c.id = $2`, [uid, rows[0].id]);
    reply.status(201);
    return mapComment(c.rows[0], uid, post.author_id);
  });

  async function ownComment(id: string, uid: string) {
    const { rows } = await db.query(`SELECT author_id, status FROM comments WHERE id = $1`, [id]);
    if (!rows[0] || rows[0].status !== 'active') throw Errors.notFound('Comment');
    if (rows[0].author_id !== uid) throw Errors.forbidden();
  }

  app.patch('/comments/:id', {
    schema: { tags: ['vibetalk'], summary: 'Edit my comment', security: bearer, params: IdParam, body: z.object({ body: Body }) },
    ...write,
  }, async (req) => {
    const uid = me(req).id;
    await ownComment(req.params.id, uid);
    await db.query('UPDATE comments SET body = $2, edited_at = now() WHERE id = $1', [req.params.id, req.body.body]);
    const c = await db.query(`${COMMENT_SELECT(1)} AND c.id = $2`, [uid, req.params.id]);
    return mapComment(c.rows[0], uid);
  });

  app.delete('/comments/:id', {
    schema: { tags: ['vibetalk'], summary: 'Delete my comment', security: bearer, params: IdParam },
    ...write,
  }, async (req, reply) => {
    const uid = me(req).id;
    await ownComment(req.params.id, uid);
    await db.query(`UPDATE comments SET status = 'deleted', is_pinned = false WHERE id = $1`, [req.params.id]);
    reply.status(204);
  });

  const pinRoute = (pin: boolean) => async (req: any) => {
    const uid = me(req).id;
    return withTx(db, async (c) => {
      const { rows } = await c.query(
        `SELECT c.id, c.post_id, c.parent_id, c.status, p.author_id AS post_author
           FROM comments c JOIN posts p ON p.id = c.post_id WHERE c.id = $1 AND p.status = 'active' FOR UPDATE OF p`,
        [req.params.id],
      );
      const r = rows[0];
      if (!r || r.status !== 'active') throw Errors.notFound('Comment');
      if (r.post_author !== uid) throw Errors.forbidden(); // only the post's creator can pin
      if (pin) {
        if (r.parent_id) throw new AppError(400, 'VALIDATION_ERROR', 'Only top-level comments can be pinned.');
        await c.query('UPDATE comments SET is_pinned = false WHERE post_id = $1 AND is_pinned', [r.post_id]);
      }
      await c.query('UPDATE comments SET is_pinned = $2 WHERE id = $1', [r.id, pin]);
      return { id: r.id, isPinned: pin };
    });
  };
  app.post('/comments/:id/pin', {
    schema: { tags: ['vibetalk'], summary: "Pin a comment on my post (replaces any existing pin)", security: bearer, params: IdParam },
    ...write,
  }, pinRoute(true));
  app.delete('/comments/:id/pin', {
    schema: { tags: ['vibetalk'], summary: 'Unpin a comment on my post', security: bearer, params: IdParam },
    ...write,
  }, pinRoute(false));
}
