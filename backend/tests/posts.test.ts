import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { makeApp, makePost, signup, uniq, type TestApp } from './helpers.js';

let t: TestApp;
beforeAll(async () => { t = await makeApp(); });
afterAll(async () => { await t.app.close(); });

describe('posts', () => {
  it('creates a text post and parses hashtags (incl. Hindi) and mentions server-side', async () => {
    const a = await signup(t);
    const b = await signup(t);
    const tag = uniq('tag');
    const res = await t.http().post('/api/v1/posts').set(a.auth)
      .send({ type: 'text', caption: `Namaste #${tag} #होली and hi @${b.username} @no_such_user_zz #${tag.toUpperCase()}` });
    expect(res.status).toBe(201);
    expect(res.body.hashtags.sort()).toEqual([tag, 'होली'].sort());
    expect(res.body.mentions).toEqual([b.username]);
    expect(res.body.counts).toEqual({ vibeClaps: 0, vibeTalk: 0, saves: 0 });
    expect(res.body.viewer).toEqual({ hasClapped: false, clapKind: null, hasSaved: false, isAuthor: true });
    expect((await t.http().get(`/api/v1/users/${a.username}`)).body.counts.posts).toBe(1);
  });

  it('ignores client-supplied counts', async () => {
    const a = await signup(t);
    const res = await t.http().post('/api/v1/posts').set(a.auth).send({ type: 'text', caption: 'x', clap_count: 9999, counts: { vibeClaps: 9999 } });
    expect(res.status).toBe(201);
    expect(res.body.counts.vibeClaps).toBe(0);
    const p = await t.http().patch('/api/v1/me').set(a.auth).send({ vibemates_count: 1000 });
    expect(p.status).toBe(400); // unknown fields are rejected on profile update
  });

  it('image post via presigned R2 upload: only metadata stored, media must be mine and pending', async () => {
    const a = await signup(t);
    const b = await signup(t);
    const up = await t.http().post('/api/v1/media/uploads').set(a.auth).send({ purpose: 'post_image', contentType: 'image/jpeg', sizeBytes: 123456, width: 1080, height: 1350 });
    expect(up.status).toBe(201);
    expect(up.body.upload.method).toBe('PUT');
    const url = new URL(up.body.upload.url);
    expect(url.hostname).toBe('vibespace-test.testaccount.r2.cloudflarestorage.com'); // bucket.account.r2...
    expect(url.pathname).toMatch(new RegExp(`^/posts/${a.id}/[0-9a-f-]{36}\\.jpg$`));
    expect(url.searchParams.get('X-Amz-Signature')).toMatch(/^[0-9a-f]{64}$/);
    expect(url.searchParams.get('X-Amz-SignedHeaders')).toContain('content-type');
    expect(up.body.upload.headers['Content-Type']).toBe('image/jpeg');

    // someone else can't attach my media
    const steal = await t.http().post('/api/v1/posts').set(b.auth).send({ type: 'image', mediaIds: [up.body.media.id] });
    expect(steal.status).toBe(400);
    expect(steal.body.error.code).toBe('MEDIA_INVALID');

    const post = await t.http().post('/api/v1/posts').set(a.auth).send({ type: 'image', caption: 'sunset', mediaIds: [up.body.media.id] });
    expect(post.status).toBe(201);
    expect(post.body.media).toHaveLength(1);
    expect(post.body.media[0].url).toBe(`https://media.test.local/${up.body.media.key}`);
    expect(post.body.media[0].width).toBe(1080);
    // reusing attached media fails
    const reuse = await t.http().post('/api/v1/posts').set(a.auth).send({ type: 'image', mediaIds: [up.body.media.id] });
    expect(reuse.status).toBe(400);
  });

  it('rejects oversize uploads and unsupported types', async () => {
    const a = await signup(t);
    const big = await t.http().post('/api/v1/media/uploads').set(a.auth).send({ purpose: 'post_image', contentType: 'image/jpeg', sizeBytes: 50 * 1024 * 1024 });
    expect(big.status).toBe(400);
    const exe = await t.http().post('/api/v1/media/uploads').set(a.auth).send({ purpose: 'post_image', contentType: 'application/x-msdownload', sizeBytes: 100 });
    expect(exe.status).toBe(400);
  });

  it('avatar via presigned upload shows on the profile', async () => {
    const a = await signup(t);
    const up = await t.http().post('/api/v1/media/uploads').set(a.auth).send({ purpose: 'avatar', contentType: 'image/png', sizeBytes: 2048 });
    const p = await t.http().patch('/api/v1/me').set(a.auth).send({ avatarMediaId: up.body.media.id, bio: 'नमस्ते! Creator from Patna', displayName: 'पुष्कर' });
    expect(p.status).toBe(200);
    expect(p.body.avatarUrl).toBe(`https://media.test.local/${up.body.media.key}`);
    expect(p.body.displayName).toBe('पुष्कर');
    const pub = await t.http().get(`/u/${a.username}`).set('Accept', 'text/html');
    expect(pub.status).toBe(200);
    expect(pub.text).toContain('पुष्कर');
    expect(pub.text).toContain('VibeMates');
    expect(pub.text).toContain('VibeStars');
  });

  it('VibeClaps: one per user, kind can change, counts are server-side', async () => {
    const a = await signup(t);
    const b = await signup(t);
    const c = await signup(t);
    const post = await makePost(t, a);
    const r1 = await t.http().put(`/api/v1/posts/${post.id}/clap`).set(b.auth).send({ kind: 'fire' });
    expect(r1.body.counts.vibeClaps).toBe(1);
    const r2 = await t.http().put(`/api/v1/posts/${post.id}/clap`).set(b.auth).send({ kind: 'love' });
    expect(r2.body.counts.vibeClaps).toBe(1);
    await t.http().put(`/api/v1/posts/${post.id}/clap`).set(c.auth).send({});
    const view = await t.http().get(`/api/v1/posts/${post.id}`).set(b.auth);
    expect(view.body.counts.vibeClaps).toBe(2);
    expect(view.body.viewer.hasClapped).toBe(true);
    expect(view.body.viewer.clapKind).toBe('love');
    const del = await t.http().delete(`/api/v1/posts/${post.id}/clap`).set(b.auth);
    expect(del.body.counts.vibeClaps).toBe(1);
  });

  it('saves and my saved list', async () => {
    const a = await signup(t);
    const b = await signup(t);
    const post = await makePost(t, a);
    const s = await t.http().put(`/api/v1/posts/${post.id}/save`).set(b.auth);
    expect(s.body.counts.saves).toBe(1);
    const list = await t.http().get('/api/v1/me/saves').set(b.auth);
    expect(list.body.items.map((p: any) => p.id)).toEqual([post.id]);
    expect(list.body.items[0].viewer.hasSaved).toBe(true);
    await t.http().delete(`/api/v1/posts/${post.id}/save`).set(b.auth);
    expect((await t.http().get('/api/v1/me/saves').set(b.auth)).body.items).toHaveLength(0);
  });

  it('edit and delete own post only; deleted posts disappear', async () => {
    const a = await signup(t);
    const b = await signup(t);
    const post = await makePost(t, a, 'first #draft');
    expect((await t.http().patch(`/api/v1/posts/${post.id}`).set(b.auth).send({ caption: 'hacked' })).status).toBe(403);
    const ed = await t.http().patch(`/api/v1/posts/${post.id}`).set(a.auth).send({ caption: 'edited #final' });
    expect(ed.body.hashtags).toEqual(['final']);
    expect((await t.http().delete(`/api/v1/posts/${post.id}`).set(b.auth)).status).toBe(403);
    expect((await t.http().delete(`/api/v1/posts/${post.id}`).set(a.auth)).status).toBe(204);
    expect((await t.http().get(`/api/v1/posts/${post.id}`)).status).toBe(404);
    expect((await t.http().get(`/api/v1/users/${a.username}`)).body.counts.posts).toBe(0);
  });

  it('reports: create once, duplicate is a no-op', async () => {
    const a = await signup(t);
    const b = await signup(t);
    const post = await makePost(t, a);
    const r = await t.http().post('/api/v1/reports').set(b.auth).send({ targetType: 'post', targetId: post.id, reason: 'spam' });
    expect(r.status).toBe(201);
    const d = await t.http().post('/api/v1/reports').set(b.auth).send({ targetType: 'post', targetId: post.id, reason: 'spam' });
    expect(d.status).toBe(200);
    expect(d.body.duplicate).toBe(true);
  });
});
