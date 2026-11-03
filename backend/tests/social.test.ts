import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { makeApp, signup, type TestApp } from './helpers.js';

let t: TestApp;
beforeAll(async () => { t = await makeApp(); });
afterAll(async () => { await t.app.close(); });
const profile = (u: string, auth?: object) => t.http().get(`/api/v1/users/${u}`).set(auth ?? {});

describe('VibeMates and VibeStars', () => {
  it('VibeMate a public account: counts update server-side, idempotent', async () => {
    const a = await signup(t);
    const b = await signup(t);
    const r = await t.http().post(`/api/v1/users/${b.username}/vibemate`).set(a.auth);
    expect(r.status).toBe(200);
    expect(r.body.vibeMateStatus).toBe('accepted');
    expect(r.body.counts.vibeMates).toBe(1);
    await t.http().post(`/api/v1/users/${b.username}/vibemate`).set(a.auth); // again
    const pb = await profile(b.username);
    expect(pb.body.counts.vibeMates).toBe(1);
    expect(pb.body.counts.vibeStars).toBe(0); // separate from VibeStars
    const pa = await profile(a.username, a.auth);
    expect(pa.body.counts.vibingWith).toBe(1);
    const rel = await profile(b.username, a.auth);
    expect(rel.body.viewer.vibeMateStatus).toBe('accepted');
    const list = await t.http().get(`/api/v1/users/${b.username}/vibemates`);
    expect(list.body.items.map((x: any) => x.username)).toEqual([a.username]);
    const un = await t.http().delete(`/api/v1/users/${b.username}/vibemate`).set(a.auth);
    expect(un.body.counts.vibeMates).toBe(0);
  });

  it('VibeStars are a separate table with their own count', async () => {
    const a = await signup(t);
    const creator = await signup(t);
    const r = await t.http().post(`/api/v1/users/${creator.username}/vibestar`).set(a.auth);
    expect(r.status).toBe(200);
    expect(r.body.counts).toEqual({ vibeMates: 0, vibeStars: 1 });
    const p = await profile(creator.username);
    expect(p.body.counts.vibeStars).toBe(1);
    expect(p.body.counts.vibeMates).toBe(0);
    const rows = await t.app.deps.db.query('SELECT count(*)::int AS n FROM vibemates WHERE to_user_id = $1', [creator.id]);
    expect(rows.rows[0].n).toBe(0);
    await t.http().delete(`/api/v1/users/${creator.username}/vibestar`).set(a.auth);
    expect((await profile(creator.username)).body.counts.vibeStars).toBe(0);
  });

  it('private accounts get requests; accept updates counts; private channels refuse VibeStars', async () => {
    const a = await signup(t);
    const priv = await signup(t, { isPrivate: true });
    const r = await t.http().post(`/api/v1/users/${priv.username}/vibemate`).set(a.auth);
    expect(r.body.vibeMateStatus).toBe('pending');
    expect(r.body.counts.vibeMates).toBe(0);
    const reqs = await t.http().get('/api/v1/me/vibemate-requests').set(priv.auth);
    expect(reqs.body.items.map((x: any) => x.username)).toContain(a.username);
    const acc = await t.http().post(`/api/v1/me/vibemate-requests/${a.username}/accept`).set(priv.auth);
    expect(acc.status).toBe(200);
    expect(acc.body.counts.vibeMates).toBe(1);
    const star = await t.http().post(`/api/v1/users/${priv.username}/vibestar`).set(a.auth);
    expect(star.status).toBe(403);
    expect(star.body.error.code).toBe('PRIVATE_CHANNEL');
    expect(star.body.error.message).toMatch(/VibeStars/);
  });

  it('cannot VibeMate yourself', async () => {
    const a = await signup(t);
    const r = await t.http().post(`/api/v1/users/${a.username}/vibemate`).set(a.auth);
    expect(r.status).toBe(400);
    expect(r.body.error.code).toBe('CANNOT_TARGET_SELF');
  });

  it('blocking removes links both ways, hides the blocker, and prevents re-following', async () => {
    const a = await signup(t);
    const b = await signup(t);
    await t.http().post(`/api/v1/users/${b.username}/vibemate`).set(a.auth);
    await t.http().post(`/api/v1/users/${a.username}/vibestar`).set(b.auth);
    const blk = await t.http().post(`/api/v1/users/${b.username}/block`).set(a.auth);
    expect(blk.status).toBe(200);
    const pa = await profile(a.username);
    expect(pa.body.counts.vibeStars).toBe(0);
    expect((await profile(b.username)).body.counts.vibeMates).toBe(0);
    // b can no longer see a, or VibeMate a
    expect((await profile(a.username, b.auth)).status).toBe(404);
    expect((await t.http().post(`/api/v1/users/${a.username}/vibemate`).set(b.auth)).status).toBe(404);
    // a can't VibeMate b while blocking
    const again = await t.http().post(`/api/v1/users/${b.username}/vibemate`).set(a.auth);
    expect(again.status).toBe(403);
    expect(again.body.error.code).toBe('BLOCKED');
    const list = await t.http().get('/api/v1/me/blocks').set(a.auth);
    expect(list.body.items.map((x: any) => x.username)).toEqual([b.username]);
    await t.http().delete(`/api/v1/users/${b.username}/block`).set(a.auth);
    expect((await profile(a.username, b.auth)).status).toBe(200);
  });

  it('mute and unmute', async () => {
    const a = await signup(t);
    const b = await signup(t);
    expect((await t.http().post(`/api/v1/users/${b.username}/mute`).set(a.auth)).body.isMuted).toBe(true);
    expect((await profile(b.username, a.auth)).body.viewer.isMuted).toBe(true);
    expect((await t.http().delete(`/api/v1/users/${b.username}/mute`).set(a.auth)).body.isMuted).toBe(false);
  });

  it('user-facing strings never say followers/subscribers', async () => {
    const doc = await t.http().get('/docs/json');
    expect(doc.status).toBe(200);
    const text = JSON.stringify(doc.body).toLowerCase();
    expect(text).not.toMatch(/follower|subscriber/);
    expect(text).toMatch(/vibemates/);
    expect(text).toMatch(/vibestars/);
  });
});
