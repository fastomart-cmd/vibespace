import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { makeApp, makePost, setRole, signup, type TestApp } from './helpers.js';

let t: TestApp;
beforeAll(async () => { t = await makeApp(); });
afterAll(async () => { await t.app.close(); });

describe('admin', () => {
  it('lists users, suspends (revoking sessions), unsuspends, all audited', async () => {
    const admin = await signup(t);
    await setRole(t, admin.id, 'admin');
    const bad = await signup(t);
    const list = await t.http().get('/api/v1/admin/users').set(admin.auth).query({ q: bad.username });
    expect(list.status).toBe(200);
    expect(list.body.items.map((u: any) => u.id)).toEqual([bad.id]);

    const s = await t.http().post(`/api/v1/admin/users/${bad.id}/suspend`).set(admin.auth).send({ reason: 'spam bot' });
    expect(s.status).toBe(200);
    expect((await t.http().get('/api/v1/me').set(bad.auth)).status).toBe(401); // sessions revoked
    const login = await t.http().post('/api/v1/auth/login').send({ identifier: bad.username, password: bad.password });
    expect(login.status).toBe(403);
    expect(login.body.error.code).toBe('ACCOUNT_SUSPENDED');
    expect((await t.http().get(`/api/v1/users/${bad.username}`)).status).toBe(404); // hidden publicly

    expect((await t.http().post(`/api/v1/admin/users/${bad.id}/unsuspend`).set(admin.auth)).status).toBe(200);
    expect((await t.http().post('/api/v1/auth/login').send({ identifier: bad.username, password: bad.password })).status).toBe(200);

    const logs = await t.http().get('/api/v1/admin/audit-logs').set(admin.auth);
    const actions = logs.body.items.filter((l: any) => l.targetId === bad.id).map((l: any) => l.action);
    expect(actions).toEqual(['user.unsuspend', 'user.suspend']);
    expect(logs.body.items.find((l: any) => l.action === 'user.suspend').admin).toBe(admin.username);
  });

  it('views reports, removes reported content (closing its reports), resolves reports', async () => {
    const mod = await signup(t);
    await setRole(t, mod.id, 'moderator');
    const author = await signup(t);
    const r1 = await signup(t);
    const r2 = await signup(t);
    const post = await makePost(t, author, 'bad stuff');
    await t.http().post('/api/v1/reports').set(r1.auth).send({ targetType: 'post', targetId: post.id, reason: 'hate' });
    await t.http().post('/api/v1/reports').set(r2.auth).send({ targetType: 'post', targetId: post.id, reason: 'hate', details: 'see caption' });
    const c = await t.http().post(`/api/v1/posts/${post.id}/comments`).set(r1.auth).send({ body: 'meh' });
    await t.http().post('/api/v1/reports').set(author.auth).send({ targetType: 'comment', targetId: c.body.id, reason: 'spam' });

    const open = await t.http().get('/api/v1/admin/reports').set(mod.auth);
    const mine = open.body.items.filter((r: any) => r.targetId === post.id);
    expect(mine).toHaveLength(2);
    expect(mine[0].reportsOnTarget).toBe(2);

    const rm = await t.http().delete(`/api/v1/admin/posts/${post.id}`).set(mod.auth).send({ reason: 'hate speech' });
    expect(rm.status).toBe(200);
    expect((await t.http().get(`/api/v1/posts/${post.id}`)).status).toBe(404);
    const stillOpen = await t.http().get('/api/v1/admin/reports').set(mod.auth);
    expect(stillOpen.body.items.filter((r: any) => r.targetId === post.id)).toHaveLength(0);

    const commentReport = stillOpen.body.items.find((r: any) => r.targetId === c.body.id);
    const res = await t.http().post(`/api/v1/admin/reports/${commentReport.id}/resolve`).set(mod.auth).send({ status: 'dismissed' });
    expect(res.status).toBe(200);
    expect((await t.http().post(`/api/v1/admin/reports/${commentReport.id}/resolve`).set(mod.auth).send({ status: 'dismissed' })).status).toBe(404);

    const audit = await t.app.deps.db.query(`SELECT action FROM admin_audit_logs WHERE admin_id = $1 ORDER BY created_at`, [mod.id]);
    expect(audit.rows.map((r) => r.action)).toEqual(['post.remove', 'report.dismissed']);
  });

  it('removes a comment', async () => {
    const admin = await signup(t);
    await setRole(t, admin.id, 'admin');
    const a = await signup(t);
    const post = await makePost(t, a);
    const c = await t.http().post(`/api/v1/posts/${post.id}/comments`).set(a.auth).send({ body: 'remove me' });
    expect((await t.http().delete(`/api/v1/admin/comments/${c.body.id}`).set(admin.auth).send({ reason: 'abuse' })).status).toBe(200);
    expect((await t.http().get(`/api/v1/posts/${post.id}/comments`)).body.items).toHaveLength(0);
    expect((await t.http().get(`/api/v1/posts/${post.id}`)).body.counts.vibeTalk).toBe(0);
  });

  it('moderators cannot suspend staff; nobody can suspend themselves', async () => {
    const mod = await signup(t);
    const admin = await signup(t);
    await setRole(t, mod.id, 'moderator');
    await setRole(t, admin.id, 'admin');
    expect((await t.http().post(`/api/v1/admin/users/${admin.id}/suspend`).set(mod.auth).send({ reason: 'coup' })).status).toBe(403);
    expect((await t.http().post(`/api/v1/admin/users/${admin.id}/suspend`).set(admin.auth).send({ reason: 'self' })).status).toBe(400);
  });

  it('admin can change roles (audited)', async () => {
    const admin = await signup(t);
    await setRole(t, admin.id, 'admin');
    const u = await signup(t);
    const r = await t.http().patch(`/api/v1/admin/users/${u.id}/role`).set(admin.auth).send({ role: 'moderator' });
    expect(r.status).toBe(200);
    expect((await t.http().get('/api/v1/admin/reports').set(u.auth)).status).toBe(200);
  });
});
