import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { makeApp, makePost, signup, type TestApp } from './helpers.js';

let t: TestApp;
beforeAll(async () => { t = await makeApp(); });
afterAll(async () => { await t.app.close(); });

describe('server-side authorization', () => {
  it('private account content is hidden from non-VibeMates and visible after acceptance', async () => {
    const priv = await signup(t, { isPrivate: true });
    const other = await signup(t);
    const post = await makePost(t, priv, 'secret');
    expect((await t.http().get(`/api/v1/posts/${post.id}`).set(other.auth)).status).toBe(404);
    expect((await t.http().put(`/api/v1/posts/${post.id}/clap`).set(other.auth).send({})).status).toBe(404);
    expect((await t.http().post(`/api/v1/posts/${post.id}/comments`).set(other.auth).send({ body: 'x' })).status).toBe(404);
    expect((await t.http().put(`/api/v1/posts/${post.id}/save`).set(other.auth)).status).toBe(404);
    await t.http().post(`/api/v1/users/${priv.username}/vibemate`).set(other.auth);
    expect((await t.http().get(`/api/v1/posts/${post.id}`).set(other.auth)).status).toBe(404); // still pending
    await t.http().post(`/api/v1/me/vibemate-requests/${other.username}/accept`).set(priv.auth);
    expect((await t.http().get(`/api/v1/posts/${post.id}`).set(other.auth)).status).toBe(200);
    const feed = await t.http().get('/api/v1/feed/following').set(other.auth);
    expect(feed.body.items.map((p: any) => p.id)).toContain(post.id);
  });

  it('blocked users cannot see or interact with posts', async () => {
    const a = await signup(t);
    const b = await signup(t);
    const post = await makePost(t, a);
    await t.http().post(`/api/v1/users/${b.username}/block`).set(a.auth);
    expect((await t.http().get(`/api/v1/posts/${post.id}`).set(b.auth)).status).toBe(404);
    expect((await t.http().post(`/api/v1/posts/${post.id}/comments`).set(b.auth).send({ body: 'x' })).status).toBe(404);
  });

  it('non-staff cannot use admin endpoints; moderators cannot use admin-only ones', async () => {
    const u = await signup(t);
    const mod = await signup(t);
    await t.app.deps.db.query(`UPDATE users SET role = 'moderator' WHERE id = $1`, [mod.id]);
    const r = await t.http().get('/api/v1/admin/users').set(u.auth);
    expect(r.status).toBe(403);
    expect(r.body.error.code).toBe('FORBIDDEN');
    expect((await t.http().post(`/api/v1/admin/users/${mod.id}/suspend`).set(u.auth).send({ reason: 'nope' })).status).toBe(403);
    expect((await t.http().get('/api/v1/admin/reports').set(mod.auth)).status).toBe(200);
    expect((await t.http().get('/api/v1/admin/audit-logs').set(mod.auth)).status).toBe(403);
    expect((await t.http().patch(`/api/v1/admin/users/${u.id}/role`).set(mod.auth).send({ role: 'admin' })).status).toBe(403);
  });

  it('role changes take effect immediately (DB is re-checked on every request)', async () => {
    const u = await signup(t);
    await t.app.deps.db.query(`UPDATE users SET role = 'moderator' WHERE id = $1`, [u.id]);
    expect((await t.http().get('/api/v1/admin/users').set(u.auth)).status).toBe(200);
    await t.app.deps.db.query(`UPDATE users SET role = 'user' WHERE id = $1`, [u.id]);
    expect((await t.http().get('/api/v1/admin/users').set(u.auth)).status).toBe(403);
  });

  it('unknown routes and bad ids use the standard error format', async () => {
    const a = await signup(t);
    const nf = await t.http().get('/api/v1/nope');
    expect(nf.status).toBe(404);
    expect(nf.body.error.code).toBe('NOT_FOUND');
    const bad = await t.http().get('/api/v1/posts/not-a-uuid').set(a.auth);
    expect(bad.status).toBe(400);
    expect(bad.body.error.code).toBe('VALIDATION_ERROR');
  });
});
