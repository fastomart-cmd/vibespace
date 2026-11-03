import { z } from 'zod';
import type { FastifyInstance } from 'fastify';
import type { ZodTypeProvider } from 'fastify-type-provider-zod';
import { withTx } from '../db/pool.js';
import { AppError, Errors } from '../lib/errors.js';
import { me } from '../lib/auth.js';
import { writeLimit } from '../lib/limits.js';
import { mapPost } from '../lib/views.js';
import { getVisiblePost, syncTagsAndMentions } from '../lib/posts.js';
import { IpViewBudget, isBot, recordViews, viewerKey } from '../lib/postViews.js';

const IdParam = z.object({ id: z.string().uuid() });
const bearer = [{ bearerAuth: [] }];

export default async function postRoutes(app0: FastifyInstance) {
  const app = app0.withTypeProvider<ZodTypeProvider>();
  const { db, storage, config } = app.deps;
  const { requireAuth, optionalAuth } = app.deps.auth;
  const write = { onRequest: requireAuth, config: writeLimit(config) };

  app.post('/posts', {
    schema: {
      tags: ['posts'],
      summary: 'Create a text or image post (login required)',
      description: 'Hashtags (#tag, Hindi supported) and @mentions are parsed from the caption server-side.',
      security: bearer,
      body: z.discriminatedUnion('type', [
        z.object({ type: z.literal('text'), caption: z.string().trim().min(1).max(2200) }),
        z.object({
          type: z.literal('image'),
          caption: z.string().trim().max(2200).default(''),
          mediaIds: z.array(z.string().uuid()).min(1).max(10),
        }),
      ]),
    },
    ...write,
  }, async (req, reply) => {
    const uid = me(req).id;
    const b = req.body;
    const postId = await withTx(db, async (c) => {
      let mediaRows: { id: string; object_key: string; size_bytes: number }[] = [];
      if (b.type === 'image') {
        const ids = [...new Set(b.mediaIds)];
        if (ids.length !== b.mediaIds.length) throw new AppError(400, 'MEDIA_INVALID', 'Duplicate media ids.');
        const { rows } = await c.query(
          `SELECT id, object_key, size_bytes FROM media
            WHERE id = ANY($1::uuid[]) AND owner_id = $2 AND purpose = 'post_image' AND status = 'pending' FOR UPDATE`,
          [ids, uid],
        );
        if (rows.length !== ids.length) throw new AppError(400, 'MEDIA_INVALID');
        mediaRows = rows;
        if (config.MEDIA_VERIFY_UPLOADS) {
          for (const m of mediaRows) {
            const head = await storage.head(m.object_key);
            if (!head || head.size !== m.size_bytes) throw new AppError(400, 'MEDIA_INVALID', 'Upload not found in storage or size mismatch.');
          }
        }
      }
      const { rows } = await c.query(`INSERT INTO posts (author_id, type, caption) VALUES ($1, $2, $3) RETURNING id`, [uid, b.type, b.caption]);
      const id = rows[0].id as string;
      if (b.type === 'image') {
        for (const [i, mid] of b.mediaIds.entries()) {
          await c.query(`UPDATE media SET status = 'attached', post_id = $2, position = $3 WHERE id = $1`, [mid, id, i]);
        }
      }
      await syncTagsAndMentions(c, id, uid, b.caption);
      return id;
    });
    reply.status(201);
    return mapPost(await getVisiblePost(db, postId, uid), storage, uid);
  });

  app.get('/posts/:id', {
    schema: { tags: ['posts'], summary: 'View a post (guest OK if the author is public)', params: IdParam },
    onRequest: optionalAuth,
  }, async (req) => {
    const viewer = req.user?.id ?? null;
    return mapPost(await getVisiblePost(db, req.params.id, viewer), storage, viewer);
  });

  app.patch('/posts/:id', {
    schema: {
      tags: ['posts'],
      summary: 'Edit the caption of my post',
      security: bearer,
      params: IdParam,
      body: z.object({ caption: z.string().trim().max(2200) }),
    },
    ...write,
  }, async (req) => {
    const uid = me(req).id;
    await withTx(db, async (c) => {
      const { rows } = await c.query(`SELECT author_id, type FROM posts WHERE id = $1 AND status = 'active' FOR UPDATE`, [req.params.id]);
      if (!rows[0]) throw Errors.notFound('Post');
      if (rows[0].author_id !== uid) throw Errors.forbidden();
      if (rows[0].type === 'text' && !req.body.caption) throw Errors.validation([{ path: '/caption', message: 'Text posts need a caption' }]);
      await c.query('UPDATE posts SET caption = $2, updated_at = now() WHERE id = $1', [req.params.id, req.body.caption]);
      await syncTagsAndMentions(c, req.params.id, uid, req.body.caption);
    });
    return mapPost(await getVisiblePost(db, req.params.id, uid), storage, uid);
  });

  app.delete('/posts/:id', {
    schema: { tags: ['posts'], summary: 'Delete my post', security: bearer, params: IdParam },
    ...write,
  }, async (req, reply) => {
    const uid = me(req).id;
    const { rows } = await db.query(`SELECT author_id FROM posts WHERE id = $1 AND status = 'active'`, [req.params.id]);
    if (!rows[0]) throw Errors.notFound('Post');
    if (rows[0].author_id !== uid) throw Errors.forbidden();
    await db.query(`UPDATE posts SET status = 'deleted', updated_at = now() WHERE id = $1`, [req.params.id]);
    reply.status(204);
  });

  // ------------------------------------------------------------ views
  // Guests and logged-in users. Send X-Device-Id (a random id the app stores) so guests are counted per device.
  const ipBudget = new IpViewBudget(config.VIEWS_MAX_NEW_PER_IP_PER_HOUR);
  const viewLimit = {
    onRequest: optionalAuth,
    config: {
      rateLimit: {
        max: config.RATE_LIMIT_VIEWS_PER_MIN,
        timeWindow: '1 minute',
        hook: 'preHandler' as const,
        keyGenerator: (req: any) => `views:${viewerKey(req, config)}`,
      },
    },
  };
  const countViews = async (req: any, postIds: string[]) => {
    const viewer = req.user?.id ?? null;
    const maxNew = isBot(req) ? 0 : ipBudget.allowance(req.ip, postIds.length);
    const results = await recordViews(db, { postIds, viewerId: viewer, viewerKey: viewerKey(req, config), maxNew });
    ipBudget.consume(req.ip, results.filter((r) => r.counted).length);
    return results;
  };

  app.post('/posts/:id/view', {
    schema: {
      tags: ['posts'],
      summary: 'Record that I saw a post (guest OK)',
      description:
        'Counts at most one view per viewer per post per 24 hours. Viewer = the logged-in user, or for guests the ' +
        'X-Device-Id header (hashed; never stored raw). The author’s own views are not counted. Rate limited per ' +
        'viewer, with a per-IP budget of new views. Returns the current count either way.',
      params: IdParam,
    },
    ...viewLimit,
  }, async (req) => {
    const [r] = await countViews(req, [req.params.id]);
    if (!r) throw Errors.notFound('Post');
    return { postId: r.postId, viewCount: r.viewCount, counted: r.counted };
  });

  app.post('/posts/views', {
    schema: {
      tags: ['posts'],
      summary: 'Record views of several posts at once (guest OK, max 50)',
      description:
        'Same rules as POST /posts/{id}/view. Posts that do not exist or that you cannot see are silently left out ' +
        'of `items`. Send the ids of posts that were on screen (the app sends a batch every few seconds).',
      body: z.object({ postIds: z.array(z.string().uuid()).min(1).max(50) }),
    },
    ...viewLimit,
  }, async (req) => {
    const results = await countViews(req, req.body.postIds);
    const order = new Map([...new Set(req.body.postIds)].map((id, i) => [id, i]));
    results.sort((a, b) => order.get(a.postId)! - order.get(b.postId)!);
    return { items: results, counted: results.filter((r) => r.counted).length };
  });

  // ------------------------------------------------------------ VibeClaps
  const countsOf = async (postId: string) => {
    const { rows } = await db.query('SELECT clap_count, comment_count, save_count FROM posts WHERE id = $1', [postId]);
    return { vibeClaps: rows[0].clap_count, vibeTalk: rows[0].comment_count, saves: rows[0].save_count };
  };

  app.put('/posts/:id/clap', {
    schema: {
      tags: ['posts'],
      summary: 'Give (or change) my VibeClap on a post',
      security: bearer,
      params: IdParam,
      body: z.object({ kind: z.enum(['clap', 'fire', 'love', 'laugh', 'wow']).default('clap') }).default({}),
    },
    ...write,
  }, async (req) => {
    const uid = me(req).id;
    await getVisiblePost(db, req.params.id, uid);
    await db.query(
      `INSERT INTO vibeclaps (post_id, user_id, kind) VALUES ($1, $2, $3)
       ON CONFLICT (post_id, user_id) DO UPDATE SET kind = EXCLUDED.kind`,
      [req.params.id, uid, req.body.kind],
    );
    return { hasClapped: true, clapKind: req.body.kind, counts: await countsOf(req.params.id) };
  });

  app.delete('/posts/:id/clap', {
    schema: { tags: ['posts'], summary: 'Remove my VibeClap', security: bearer, params: IdParam },
    ...write,
  }, async (req) => {
    const uid = me(req).id;
    await getVisiblePost(db, req.params.id, uid);
    await db.query('DELETE FROM vibeclaps WHERE post_id = $1 AND user_id = $2', [req.params.id, uid]);
    return { hasClapped: false, clapKind: null, counts: await countsOf(req.params.id) };
  });

  // ------------------------------------------------------------ saves
  app.put('/posts/:id/save', {
    schema: { tags: ['posts'], summary: 'Save a post', security: bearer, params: IdParam },
    ...write,
  }, async (req) => {
    const uid = me(req).id;
    await getVisiblePost(db, req.params.id, uid);
    await db.query('INSERT INTO saves (user_id, post_id) VALUES ($1, $2) ON CONFLICT DO NOTHING', [uid, req.params.id]);
    return { hasSaved: true, counts: await countsOf(req.params.id) };
  });

  app.delete('/posts/:id/save', {
    schema: { tags: ['posts'], summary: 'Unsave a post', security: bearer, params: IdParam },
    ...write,
  }, async (req) => {
    const uid = me(req).id;
    await db.query('DELETE FROM saves WHERE user_id = $1 AND post_id = $2', [uid, req.params.id]);
    const { rows } = await db.query('SELECT 1 FROM posts WHERE id = $1', [req.params.id]);
    if (!rows[0]) throw Errors.notFound('Post');
    return { hasSaved: false, counts: await countsOf(req.params.id) };
  });
}
