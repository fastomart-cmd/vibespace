import { z } from 'zod';
import type { FastifyInstance } from 'fastify';
import type { ZodTypeProvider } from 'fastify-type-provider-zod';
import { Errors } from '../lib/errors.js';
import { me } from '../lib/auth.js';
import { writeLimit } from '../lib/limits.js';

export default async function reportRoutes(app0: FastifyInstance) {
  const app = app0.withTypeProvider<ZodTypeProvider>();
  const { db, config } = app.deps;
  const { requireAuth } = app.deps.auth;

  app.post('/reports', {
    schema: {
      tags: ['reports'],
      summary: 'Report a post, comment or user (login required). Reporting the same thing twice is a no-op.',
      security: [{ bearerAuth: [] }],
      body: z.object({
        targetType: z.enum(['post', 'comment', 'user']),
        targetId: z.string().uuid(),
        reason: z.enum(['spam', 'harassment', 'hate', 'nudity', 'violence', 'misinformation', 'impersonation', 'other']),
        details: z.string().max(1000).default(''),
      }),
    },
    onRequest: requireAuth,
    config: writeLimit(config),
  }, async (req, reply) => {
    const uid = me(req).id;
    const b = req.body;
    const table = { post: 'posts', comment: 'comments', user: 'users' }[b.targetType];
    const { rows } = await db.query(`SELECT 1 FROM ${table} WHERE id = $1`, [b.targetId]);
    if (!rows[0]) throw Errors.notFound('Target');
    if (b.targetType === 'user' && b.targetId === uid) throw Errors.self();
    const ins = await db.query(
      `INSERT INTO reports (reporter_id, target_type, target_id, reason, details) VALUES ($1, $2, $3, $4, $5)
       ON CONFLICT (reporter_id, target_type, target_id) DO NOTHING RETURNING id`,
      [uid, b.targetType, b.targetId, b.reason, b.details],
    );
    reply.status(ins.rows[0] ? 201 : 200);
    return { reported: true, duplicate: !ins.rows[0] };
  });
}
