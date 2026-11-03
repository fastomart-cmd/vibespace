import { randomUUID } from 'node:crypto';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { makeApp, makePost, signup, type TestApp } from './helpers.js';

let t: TestApp;
beforeAll(async () => { t = await makeApp(); });
afterAll(async () => { await t.app.close(); });

const UA = 'okhttp/5.1.0';
const dev = () => randomUUID();
const view = (app: TestApp, postId: string, headers: Record<string, string> = {}) =>
  app.http().post(`/api/v1/posts/${postId}/view`).set({ 'User-Agent': UA, ...headers });
const batch = (app: TestApp, postIds: string[], headers: Record<string, string> = {}) =>
  app.http().post('/api/v1/posts/views').set({ 'User-Agent': UA, ...headers }).send({ postIds });
const viewCountOf = async (id: string) =>
  Number((await t.app.deps.db.query('SELECT view_count FROM posts WHERE id = $1', [id])).rows[0].view_count);

describe('post views', () => {
  it('every post response carries viewCount (create, detail, feeds, profile posts, saves)', async () => {
    const a = await signup(t);
    const b = await signup(t);
    const p = await makePost(t, a, 'count my views');
    expect(p.viewCount).toBe(0);
    await view(t, p.id, { 'X-Device-Id': dev() });

    const detail = await t.http().get(`/api/v1/posts/${p.id}`);
    expect(detail.body.viewCount).toBe(1);
    const find = (items: any[]) => items.find((x) => x.id === p.id);
    expect(find((await t.http().get('/api/v1/feed/trending').query({ limit: 50 })).body.items)?.viewCount).toBe(1);
    expect(find((await t.http().get('/api/v1/feed/explore').query({ limit: 50 })).body.items)?.viewCount).toBe(1);
    expect(find((await t.http().get(`/api/v1/users/${a.username}/posts`)).body.items)?.viewCount).toBe(1);
    expect(find((await t.http().get('/api/v1/feed/following').set(a.auth)).body.items)?.viewCount).toBe(1);
    await t.http().put(`/api/v1/posts/${p.id}/save`).set(b.auth);
    expect(find((await t.http().get('/api/v1/me/saves').set(b.auth)).body.items)?.viewCount).toBe(1);
  });

  it('counts one view per guest device and per logged-in user, at most once per 24 hours', async () => {
    const a = await signup(t);
    const b = await signup(t);
    const p = await makePost(t, a);
    const d1 = dev();

    let r = await view(t, p.id, { 'X-Device-Id': d1 });
    expect(r.status).toBe(200);
    expect(r.body).toEqual({ postId: p.id, viewCount: 1, counted: true });
    r = await view(t, p.id, { 'X-Device-Id': d1 }); // same device again: not counted
    expect(r.body).toEqual({ postId: p.id, viewCount: 1, counted: false });

    expect((await view(t, p.id, { 'X-Device-Id': dev() })).body.viewCount).toBe(2); // another device

    // logged-in users are counted by account, whatever device they use
    expect((await view(t, p.id, { ...b.auth, 'X-Device-Id': d1 })).body).toMatchObject({ viewCount: 3, counted: true });
    expect((await view(t, p.id, { ...b.auth, 'X-Device-Id': dev() })).body).toMatchObject({ viewCount: 3, counted: false });

    // 24 hours later the same viewer counts again
    await t.app.deps.db.query(`UPDATE post_views SET last_counted_at = now() - interval '25 hours' WHERE post_id = $1`, [p.id]);
    expect((await view(t, p.id, { 'X-Device-Id': d1 })).body).toMatchObject({ viewCount: 4, counted: true });
    expect((await view(t, p.id, b.auth)).body).toMatchObject({ viewCount: 5, counted: true });
    expect((await view(t, p.id, b.auth)).body).toMatchObject({ viewCount: 5, counted: false });

    // only hashed keys are stored, never the raw device id
    const { rows } = await t.app.deps.db.query('SELECT viewer_key FROM post_views WHERE post_id = $1', [p.id]);
    expect(rows.map((x) => x.viewer_key).join(' ')).not.toContain(d1);
    expect(rows.some((x) => x.viewer_key === `u:${b.id}`)).toBe(true);
  });

  it("does not count the author's own views", async () => {
    const a = await signup(t);
    const p = await makePost(t, a);
    const r = await view(t, p.id, { ...a.auth, 'X-Device-Id': dev() });
    expect(r.status).toBe(200);
    expect(r.body).toEqual({ postId: p.id, viewCount: 0, counted: false });
    expect((await batch(t, [p.id], a.auth)).body.counted).toBe(0);
    expect(await viewCountOf(p.id)).toBe(0);
  });

  it('guests without a valid X-Device-Id are counted by IP + user agent', async () => {
    const a = await signup(t);
    const p = await makePost(t, a);
    expect((await view(t, p.id)).body.counted).toBe(true);
    expect((await view(t, p.id, { 'X-Device-Id': 'bad id!' })).body.counted).toBe(false); // invalid id -> same fallback key
    expect((await view(t, p.id, { 'X-Device-Id': 'short' })).body.counted).toBe(false);
    expect(await viewCountOf(p.id)).toBe(1);
  });

  it('hidden, deleted and unknown posts: 404 for one, silently skipped in a batch', async () => {
    const a = await signup(t);
    const priv = await signup(t, { isPrivate: true });
    const visible = await makePost(t, a);
    const hidden = await makePost(t, priv);
    const deleted = await makePost(t, a);
    await t.http().delete(`/api/v1/posts/${deleted.id}`).set(a.auth);

    expect((await view(t, hidden.id, { 'X-Device-Id': dev() })).status).toBe(404);
    expect((await view(t, deleted.id, { 'X-Device-Id': dev() })).status).toBe(404);
    expect((await view(t, randomUUID(), { 'X-Device-Id': dev() })).status).toBe(404);
    expect((await view(t, 'not-a-uuid')).status).toBe(400);

    const r = await batch(t, [hidden.id, visible.id, deleted.id, randomUUID(), visible.id], { 'X-Device-Id': dev() });
    expect(r.status).toBe(200);
    expect(r.body).toEqual({ items: [{ postId: visible.id, viewCount: 1, counted: true }], counted: 1 });
    expect(await viewCountOf(hidden.id)).toBe(0);
    // the private author can still see (and is the author of) their post: no count
    expect((await view(t, hidden.id, priv.auth)).body.counted).toBe(false);
  });

  it('batch: up to 50 ids, results in request order, logged-in and guest', async () => {
    const a = await signup(t);
    const b = await signup(t);
    const posts = [await makePost(t, a, 'one'), await makePost(t, a, 'two'), await makePost(t, a, 'three')];
    const ids = posts.map((p) => p.id);

    const tooMany = await batch(t, Array.from({ length: 51 }, () => randomUUID()));
    expect(tooMany.status).toBe(400);
    expect(tooMany.body.error.code).toBe('VALIDATION_ERROR');
    expect((await batch(t, [])).status).toBe(400);
    expect((await batch(t, Array.from({ length: 50 }, () => randomUUID()))).status).toBe(200);

    const d = dev();
    let r = await batch(t, [ids[2], ids[0]], { 'X-Device-Id': d });
    expect(r.body.items.map((x: any) => x.postId)).toEqual([ids[2], ids[0]]);
    expect(r.body.counted).toBe(2);
    r = await batch(t, ids, { 'X-Device-Id': d });
    expect(r.body.items.map((x: any) => [x.viewCount, x.counted])).toEqual([[1, false], [1, true], [1, false]]);
    r = await batch(t, ids, b.auth);
    expect(r.body.counted).toBe(3);
    expect(r.body.items.map((x: any) => x.viewCount)).toEqual([2, 2, 2]);
  });

  it('obvious bots can read but never add views', async () => {
    const a = await signup(t);
    const p = await makePost(t, a);
    for (const ua of ['Googlebot/2.1 (+http://www.google.com/bot.html)', 'curl/8.5.0', 'python-requests/2.32', 'HeadlessChrome/120']) {
      const r = await t.http().post(`/api/v1/posts/${p.id}/view`).set({ 'User-Agent': ua, 'X-Device-Id': dev() });
      expect(r.status).toBe(200);
      expect(r.body.counted).toBe(false);
    }
    expect(await viewCountOf(p.id)).toBe(0);
  });

  it('views nudge trending lightly: they break ties but do not beat real VibeClaps', async () => {
    const a = await signup(t);
    const viewed = await makePost(t, a, 'viewed a lot');
    const plain = await makePost(t, a, 'plain, slightly newer');
    const clapped = await makePost(t, a, 'clapped');
    await t.app.deps.db.query('UPDATE posts SET view_count = 1000 WHERE id = $1', [viewed.id]);
    await t.app.deps.db.query('UPDATE posts SET clap_count = 4 WHERE id = $1', [clapped.id]);
    const items = (await t.http().get('/api/v1/feed/trending').query({ limit: 50 })).body.items;
    const pos = (id: string) => items.findIndex((x: any) => x.id === id);
    expect(pos(viewed.id)).toBeGreaterThanOrEqual(0);
    expect(pos(viewed.id)).toBeLessThan(pos(plain.id)); // 1,000 views lift it above an equal, newer post
    expect(pos(clapped.id)).toBeLessThan(pos(viewed.id)); // ...but 4 VibeClaps still rank higher
  });
});

describe('post view anti-abuse limits', () => {
  it('rate limits view requests per viewer (device or user), independently per viewer', async () => {
    const t2 = await makeApp({ RATE_LIMIT_VIEWS_PER_MIN: '3' });
    const a = await signup(t2);
    const p = await makePost(t2, a);
    const d = dev();
    const codes: number[] = [];
    for (let i = 0; i < 5; i++) codes.push((await view(t2, p.id, { 'X-Device-Id': d })).status);
    expect(codes).toEqual([200, 200, 200, 429, 429]);
    const bcodes: number[] = []; // the batch endpoint has its own bucket with the same limit
    for (let i = 0; i < 4; i++) bcodes.push((await batch(t2, [p.id], { 'X-Device-Id': d })).status);
    expect(bcodes).toEqual([200, 200, 200, 429]);
    const limited = await batch(t2, [p.id], { 'X-Device-Id': d });
    expect(limited.body.error.code).toBe('RATE_LIMITED');
    expect((await view(t2, p.id, { 'X-Device-Id': dev() })).status).toBe(200); // another device is fine
    await t2.app.close();
  });

  it('caps new views per IP per hour, so rotating device ids cannot inflate counts', async () => {
    const t3 = await makeApp({ VIEWS_MAX_NEW_PER_IP_PER_HOUR: '3' });
    const a = await signup(t3);
    const p1 = await makePost(t3, a);
    const p2 = await makePost(t3, a);
    const results: boolean[] = [];
    for (let i = 0; i < 3; i++) results.push((await view(t3, p1.id, { 'X-Device-Id': dev() })).body.counted);
    const r = await batch(t3, [p1.id, p2.id], { 'X-Device-Id': dev() });
    expect(r.status).toBe(200);
    expect(results).toEqual([true, true, true]);
    expect(r.body.counted).toBe(0);
    expect(r.body.items.find((x: any) => x.postId === p1.id).viewCount).toBe(3);
    await t3.app.close();
  });
});
