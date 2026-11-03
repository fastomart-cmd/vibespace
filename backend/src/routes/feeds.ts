import { z } from 'zod';
import type { FastifyInstance } from 'fastify';
import type { ZodTypeProvider } from 'fastify-type-provider-zod';
import { me } from '../lib/auth.js';
import { decodeOffsetCursor, decodeTimeCursor, encodeCursor, pageByTime } from '../lib/cursor.js';
import { POST_SELECT, mapPost, notMuted, postVisible } from '../lib/views.js';
import { PageQuery } from './users.js';

/**
 * Trending score (computed in SQL, server-side only):
 *   (claps + 2*comments + 3*saves + 0.5*ln(1 + views) + 1) / (age_hours + 2) ^ 1.5
 * over active posts by public accounts from the last 7 days (HN-style gravity decay).
 * Views count only lightly and logarithmically (1,000 views ≈ 3.5 VibeClaps), so they help break ties
 * between similar posts without letting cheap, easy-to-inflate views outrank real engagement.
 */
export const TRENDING_SCORE = `((p.clap_count + 2 * p.comment_count + 3 * p.save_count + 0.5 * ln(1 + p.view_count) + 1)::float8
   / power(extract(epoch FROM (now() - p.created_at)) / 3600.0 + 2, 1.5))`;

export default async function feedRoutes(app0: FastifyInstance) {
  const app = app0.withTypeProvider<ZodTypeProvider>();
  const { db, storage } = app.deps;
  const { requireAuth, optionalAuth } = app.deps.auth;

  app.get('/feed/following', {
    schema: {
      tags: ['feeds'],
      summary: 'Following feed: my posts and posts by accounts I VibeMate, newest first (login required)',
      security: [{ bearerAuth: [] }],
      querystring: PageQuery,
    },
    onRequest: requireAuth,
  }, async (req) => {
    const uid = me(req).id;
    const cur = decodeTimeCursor(req.query.cursor);
    const { rows } = await db.query(
      `${POST_SELECT(1)}
        WHERE (p.author_id = $1 OR p.author_id IN (SELECT to_user_id FROM vibemates WHERE from_user_id = $1 AND status = 'accepted'))
          AND ${postVisible(1)} AND ${notMuted(1, 'a.id')}
          AND ($2::timestamptz IS NULL OR (p.created_at, p.id) < ($2::timestamptz, $3::uuid))
        ORDER BY p.created_at DESC, p.id DESC LIMIT $4`,
      [uid, cur?.t ?? null, cur?.id ?? null, req.query.limit + 1],
    );
    const page = pageByTime(rows, req.query.limit);
    return { items: page.items.map((r) => mapPost(r, storage, uid)), nextCursor: page.nextCursor };
  });

  app.get('/feed/trending', {
    schema: {
      tags: ['feeds'],
      summary: 'Trending feed (guest OK): time-decayed engagement score over the last 7 days',
      querystring: PageQuery,
    },
    onRequest: optionalAuth,
  }, async (req) => {
    const viewer = req.user?.id ?? null;
    const offset = decodeOffsetCursor(req.query.cursor);
    const { rows } = await db.query(
      `SELECT * FROM (
         ${POST_SELECT(1).replace('SELECT p.id,', `SELECT ${TRENDING_SCORE} AS score, p.id,`)}
          WHERE a.is_private = false AND p.created_at > now() - interval '7 days'
            AND ${postVisible(1)} AND ${notMuted(1, 'a.id')}) t
        ORDER BY score DESC, id DESC OFFSET $2 LIMIT $3`,
      [viewer, offset, req.query.limit + 1],
    );
    const hasMore = rows.length > req.query.limit;
    const items = rows.slice(0, req.query.limit);
    return {
      items: items.map((r) => mapPost(r, storage, viewer)),
      nextCursor: hasMore ? encodeCursor({ o: offset + items.length }) : null,
    };
  });

  app.get('/feed/explore', {
    schema: {
      tags: ['feeds'],
      summary: 'Explore (guest OK): recent posts from public accounts you don’t VibeMate yet; filter by hashtag with ?tag=',
      querystring: PageQuery.extend({ tag: z.string().trim().min(1).max(100).optional() }),
    },
    onRequest: optionalAuth,
  }, async (req) => {
    const viewer = req.user?.id ?? null;
    const cur = decodeTimeCursor(req.query.cursor);
    const tag = req.query.tag?.replace(/^#/, '').normalize('NFC').toLowerCase() ?? null;
    const { rows } = await db.query(
      `${POST_SELECT(1)}
        WHERE a.is_private = false AND p.created_at > now() - interval '30 days'
          AND ${postVisible(1)} AND ${notMuted(1, 'a.id')}
          AND ($1::uuid IS NULL OR (p.author_id <> $1 AND NOT EXISTS (
                SELECT 1 FROM vibemates f WHERE f.from_user_id = $1 AND f.to_user_id = p.author_id)))
          AND ($5::text IS NULL OR EXISTS (SELECT 1 FROM post_hashtags ph JOIN hashtags h ON h.id = ph.hashtag_id
                                            WHERE ph.post_id = p.id AND h.tag = $5))
          AND ($2::timestamptz IS NULL OR (p.created_at, p.id) < ($2::timestamptz, $3::uuid))
        ORDER BY p.created_at DESC, p.id DESC LIMIT $4`,
      [viewer, cur?.t ?? null, cur?.id ?? null, req.query.limit + 1, tag],
    );
    const page = pageByTime(rows, req.query.limit);
    return { items: page.items.map((r) => mapPost(r, storage, viewer)), nextCursor: page.nextCursor };
  });
}
