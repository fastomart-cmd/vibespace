import { randomUUID } from 'node:crypto';
import { z } from 'zod';
import type { FastifyInstance } from 'fastify';
import type { ZodTypeProvider } from 'fastify-type-provider-zod';
import { AppError } from '../lib/errors.js';
import { me } from '../lib/auth.js';
import { writeLimit } from '../lib/limits.js';
import { maxUploadBytes } from '../services/storage.js';

const EXT: Record<string, string> = { 'image/jpeg': 'jpg', 'image/png': 'png', 'image/webp': 'webp', 'image/heic': 'heic' };

export default async function mediaRoutes(app0: FastifyInstance) {
  const app = app0.withTypeProvider<ZodTypeProvider>();
  const { db, storage, config } = app.deps;
  const { requireAuth } = app.deps.auth;

  app.post('/media/uploads', {
    schema: {
      tags: ['media'],
      summary: 'Get a presigned upload URL for R2 / S3 / Supabase Storage (creators only: login required)',
      description:
        'Step 1: call this. Step 2: PUT the file bytes to `upload.url` with exactly `upload.headers`. ' +
        'Step 3: pass `media.id` to POST /posts (purpose=post_image) or PATCH /me avatarMediaId (purpose=avatar). ' +
        'Only metadata is stored in Postgres.',
      security: [{ bearerAuth: [] }],
      body: z.object({
        purpose: z.enum(['avatar', 'post_image']),
        contentType: z.enum(['image/jpeg', 'image/png', 'image/webp', 'image/heic']),
        sizeBytes: z.number().int().positive(),
        width: z.number().int().positive().max(20000).optional(),
        height: z.number().int().positive().max(20000).optional(),
      }),
    },
    onRequest: requireAuth,
    config: writeLimit(config),
  }, async (req, reply) => {
    const b = req.body;
    if (!storage.configured) throw new AppError(503, 'STORAGE_NOT_CONFIGURED');
    const max = maxUploadBytes(config, storage, b.purpose); // includes the provider cap (Supabase free: 50 MB)
    if (b.sizeBytes > max) throw new AppError(400, 'MEDIA_INVALID', `File is too large (max ${max} bytes).`);
    const uid = me(req).id;
    const key = `${b.purpose === 'avatar' ? 'avatars' : 'posts'}/${uid}/${randomUUID()}.${EXT[b.contentType]}`;
    const upload = await storage.presignPut(key, b.contentType, b.sizeBytes);
    const { rows } = await db.query(
      `INSERT INTO media (owner_id, purpose, object_key, content_type, size_bytes, width, height)
       VALUES ($1, $2, $3, $4, $5, $6, $7) RETURNING id, status, created_at`,
      [uid, b.purpose, key, b.contentType, b.sizeBytes, b.width ?? null, b.height ?? null],
    );
    reply.status(201);
    return {
      media: { id: rows[0].id, purpose: b.purpose, key, status: rows[0].status, publicUrl: storage.publicUrl(key) },
      upload,
    };
  });
}
