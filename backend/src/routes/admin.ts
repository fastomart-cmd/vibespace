import { z } from 'zod';
import type { FastifyInstance } from 'fastify';
import type { ZodTypeProvider } from 'fastify-type-provider-zod';
import { withTx, type Queryable } from '../db/pool.js';
import { AppError, Errors } from '../lib/errors.js';
import { me } from '../lib/auth.js';
import { decodeTimeCursor, pageByTime } from '../lib/cursor.js';
import { PageQuery } from './users.js';

const IdParam = z.object({ id: z.string().uuid() });
const bearer = [{ bearerAuth: [] }];

async function audit(c: Queryable, adminId: string, action: string, targetType: string, targetId: string | null, details: object = {}) {
  await c.query(
    `INSERT INTO admin_audit_logs (admin_id, action, target_type, target_id, details) VALUES ($1, $2, $3, $4, $5)`,
    [adminId, action, targetType, targetId, JSON.stringify(details)],
  );
}

/** Mounted at /api/v1/admin. Moderators: users, reports, content removal. Admins additionally: role changes, audit log. */
export default async function adminRoutes(app0: FastifyInstance) {
  const app = app0.withTypeProvider<ZodTypeProvider>();
  const { db } = app.deps;
  const { requireRole } = app.deps.auth;
  const staff = requireRole('moderator', 'admin');
  const adminOnly = requireRole('admin');

  app.get('/users', {
    schema: {
      tags: ['admin'],
      summary: 'List users (search by username/email, filter by status)',
      security: bearer,
      querystring: PageQuery.extend({ q: z.string().trim().max(100).optional(), status: z.enum(['active', 'suspended']).optional() }),
    },
    onRequest: staff,
  }, async (req) => {
    const cur = decodeTimeCursor(req.query.cursor);
    const q = req.query.q ? `%${req.query.q.toLowerCase().replace(/[%_\\]/g, '\\$&')}%` : null;
    const { rows } = await db.query(
      `SELECT id, email, username, display_name, role, status, suspended_reason, is_private,
              vibemates_count, vibestars_count, posts_count, created_at, created_at::text AS cursor_t
         FROM users
        WHERE ($1::text IS NULL OR username LIKE $1 OR lower(email) LIKE $1)
          AND ($2::text IS NULL OR status = $2)
          AND ($3::timestamptz IS NULL OR (created_at, id) < ($3::timestamptz, $4::uuid))
        ORDER BY created_at DESC, id DESC LIMIT $5`,
      [q, req.query.status ?? null, cur?.t ?? null, cur?.id ?? null, req.query.limit + 1],
    );
    const page = pageByTime(rows, req.query.limit);
    return {
      items: page.items.map((r) => ({
        id: r.id, email: r.email, username: r.username, displayName: r.display_name, role: r.role, status: r.status,
        suspendedReason: r.suspended_reason, isPrivate: r.is_private,
        counts: { vibeMates: r.vibemates_count, vibeStars: r.vibestars_count, posts: r.posts_count }, createdAt: r.created_at,
      })),
      nextCursor: page.nextCursor,
    };
  });

  async function loadTarget(c: Queryable, id: string, actorId: string, actorRole: string) {
    const { rows } = await c.query('SELECT id, role, status FROM users WHERE id = $1 FOR UPDATE', [id]);
    const t = rows[0];
    if (!t) throw Errors.notFound('User');
    if (t.id === actorId) throw Errors.self();
    if (t.role !== 'user' && actorRole !== 'admin') throw Errors.forbidden(); // moderators can't act on staff
    return t;
  }

  app.post('/users/:id/suspend', {
    schema: { tags: ['admin'], summary: 'Suspend a user (signs them out everywhere)', security: bearer, params: IdParam, body: z.object({ reason: z.string().trim().min(3).max(500) }) },
    onRequest: staff,
  }, async (req) => {
    const actor = me(req);
    await withTx(db, async (c) => {
      await loadTarget(c, req.params.id, actor.id, actor.role);
      await c.query(`UPDATE users SET status = 'suspended', suspended_reason = $2, updated_at = now() WHERE id = $1`, [req.params.id, req.body.reason]);
      await c.query('UPDATE sessions SET revoked_at = now() WHERE user_id = $1 AND revoked_at IS NULL', [req.params.id]);
      await audit(c, actor.id, 'user.suspend', 'user', req.params.id, { reason: req.body.reason });
    });
    return { id: req.params.id, status: 'suspended' };
  });

  app.post('/users/:id/unsuspend', {
    schema: { tags: ['admin'], summary: 'Lift a suspension', security: bearer, params: IdParam },
    onRequest: staff,
  }, async (req) => {
    const actor = me(req);
    await withTx(db, async (c) => {
      await loadTarget(c, req.params.id, actor.id, actor.role);
      await c.query(`UPDATE users SET status = 'active', suspended_reason = NULL, updated_at = now() WHERE id = $1`, [req.params.id]);
      await audit(c, actor.id, 'user.unsuspend', 'user', req.params.id);
    });
    return { id: req.params.id, status: 'active' };
  });

  app.patch('/users/:id/role', {
    schema: { tags: ['admin'], summary: 'Change a user’s role (admin only)', security: bearer, params: IdParam, body: z.object({ role: z.enum(['user', 'moderator', 'admin']) }) },
    onRequest: adminOnly,
  }, async (req) => {
    const actor = me(req);
    await withTx(db, async (c) => {
      const t = await loadTarget(c, req.params.id, actor.id, actor.role);
      await c.query('UPDATE users SET role = $2, updated_at = now() WHERE id = $1', [req.params.id, req.body.role]);
      await audit(c, actor.id, 'user.role_change', 'user', req.params.id, { from: t.role, to: req.body.role });
    });
    return { id: req.params.id, role: req.body.role };
  });

  app.get('/reports', {
    schema: { tags: ['admin'], summary: 'View reports', security: bearer, querystring: PageQuery.extend({ status: z.enum(['open', 'resolved', 'dismissed']).default('open') }) },
    onRequest: staff,
  }, async (req) => {
    const cur = decodeTimeCursor(req.query.cursor);
    const { rows } = await db.query(
      `SELECT r.id, r.target_type, r.target_id, r.reason, r.details, r.status, r.resolved_at, r.created_at, r.created_at::text AS cursor_t,
              ru.username AS reporter_username,
              (SELECT count(*)::int FROM reports r2 WHERE r2.target_type = r.target_type AND r2.target_id = r.target_id) AS reports_on_target
         FROM reports r JOIN users ru ON ru.id = r.reporter_id
        WHERE r.status = $1 AND ($2::timestamptz IS NULL OR (r.created_at, r.id) < ($2::timestamptz, $3::uuid))
        ORDER BY r.created_at DESC, r.id DESC LIMIT $4`,
      [req.query.status, cur?.t ?? null, cur?.id ?? null, req.query.limit + 1],
    );
    const page = pageByTime(rows, req.query.limit);
    return {
      items: page.items.map((r) => ({
        id: r.id, targetType: r.target_type, targetId: r.target_id, reason: r.reason, details: r.details, status: r.status,
        reporter: r.reporter_username, reportsOnTarget: r.reports_on_target, resolvedAt: r.resolved_at, createdAt: r.created_at,
      })),
      nextCursor: page.nextCursor,
    };
  });

  app.post('/reports/:id/resolve', {
    schema: { tags: ['admin'], summary: 'Resolve or dismiss a report', security: bearer, params: IdParam, body: z.object({ status: z.enum(['resolved', 'dismissed']), note: z.string().max(500).optional() }) },
    onRequest: staff,
  }, async (req) => {
    const actor = me(req);
    await withTx(db, async (c) => {
      const { rowCount } = await c.query(
        `UPDATE reports SET status = $2, resolved_by = $3, resolved_at = now() WHERE id = $1 AND status = 'open'`,
        [req.params.id, req.body.status, actor.id],
      );
      if (!rowCount) throw Errors.notFound('Open report');
      await audit(c, actor.id, `report.${req.body.status}`, 'report', req.params.id, { note: req.body.note ?? null });
    });
    return { id: req.params.id, status: req.body.status };
  });

  const removeRoute = (kind: 'post' | 'comment') =>
    app.delete(`/${kind}s/:id`, {
      schema: { tags: ['admin'], summary: `Remove a ${kind} (moderation)`, security: bearer, params: IdParam, body: z.object({ reason: z.string().trim().min(3).max(500) }) },
      onRequest: staff,
    }, async (req) => {
      const actor = me(req);
      await withTx(db, async (c) => {
        const sql = kind === 'post'
          ? `UPDATE posts SET status = 'removed', removed_reason = $2, updated_at = now() WHERE id = $1 AND status <> 'removed'`
          : `UPDATE comments SET status = 'removed', is_pinned = false WHERE id = $1 AND status <> 'removed' AND $2::text IS NOT NULL`;
        const { rowCount } = await c.query(sql, [req.params.id, req.body.reason]);
        if (!rowCount) throw Errors.notFound(kind === 'post' ? 'Post' : 'Comment');
        // Close open reports on this target.
        await c.query(`UPDATE reports SET status = 'resolved', resolved_by = $3, resolved_at = now() WHERE target_type = $1 AND target_id = $2 AND status = 'open'`, [kind, req.params.id, actor.id]);
        await audit(c, actor.id, `${kind}.remove`, kind, req.params.id, { reason: req.body.reason });
      });
      return { id: req.params.id, status: 'removed' };
    });
  removeRoute('post');
  removeRoute('comment');

  app.get('/audit-logs', {
    schema: { tags: ['admin'], summary: 'Admin audit log (admin only)', security: bearer, querystring: PageQuery },
    onRequest: adminOnly,
  }, async (req) => {
    const cur = decodeTimeCursor(req.query.cursor);
    const { rows } = await db.query(
      `SELECT l.id, l.action, l.target_type, l.target_id, l.details, l.created_at, l.created_at::text AS cursor_t, u.username AS admin_username
         FROM admin_audit_logs l LEFT JOIN users u ON u.id = l.admin_id
        WHERE ($1::timestamptz IS NULL OR (l.created_at, l.id) < ($1::timestamptz, $2::uuid))
        ORDER BY l.created_at DESC, l.id DESC LIMIT $3`,
      [cur?.t ?? null, cur?.id ?? null, req.query.limit + 1],
    );
    const page = pageByTime(rows, req.query.limit);
    return {
      items: page.items.map((r) => ({ id: r.id, admin: r.admin_username, action: r.action, targetType: r.target_type, targetId: r.target_id, details: r.details, createdAt: r.created_at })),
      nextCursor: page.nextCursor,
    };
  });

  void AppError;
}
