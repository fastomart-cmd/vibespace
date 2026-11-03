import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { makeApp, makePost, signup, type TestApp, type TestUser } from './helpers.js';

let t: TestApp;
let creator: TestUser;
let priv: TestUser;
let post: any;
let privPost: any;
let comment: any;

beforeAll(async () => {
  t = await makeApp();
  creator = await signup(t);
  priv = await signup(t, { isPrivate: true });
  post = await makePost(t, creator, 'public post #guestview');
  privPost = await makePost(t, priv, 'private post');
  const fan = await signup(t);
  comment = (await t.http().post(`/api/v1/posts/${post.id}/comments`).set(fan.auth).send({ body: 'first!' })).body;
  await t.http().post(`/api/v1/posts/${post.id}/comments`).set(fan.auth).send({ body: 'reply', parentId: comment.id });
  await t.http().put(`/api/v1/posts/${post.id}/clap`).set(fan.auth).send({});
  await t.http().post(`/api/v1/users/${creator.username}/vibemate`).set(fan.auth);
  await t.http().post(`/api/v1/users/${creator.username}/vibestar`).set(fan.auth);
});
afterAll(async () => { await t.app.close(); });

describe('guest viewing (no account)', () => {
  it('can browse Trending and Explore', async () => {
    const tr = await t.http().get('/api/v1/feed/trending').query({ limit: 50 });
    expect(tr.status).toBe(200);
    expect(tr.body.items.map((p: any) => p.id)).toContain(post.id);
    expect(tr.body.items.every((p: any) => p.viewer === null)).toBe(true);
    const ex = await t.http().get('/api/v1/feed/explore').query({ tag: 'guestview' });
    expect(ex.status).toBe(200);
    expect(ex.body.items.map((p: any) => p.id)).toEqual([post.id]);
  });

  it('can view a public profile (API and /u/:username) with VibeMates/VibeStars counts', async () => {
    const api = await t.http().get(`/api/v1/users/${creator.username}`);
    expect(api.status).toBe(200);
    expect(api.body.counts.vibeMates).toBe(1);
    expect(api.body.counts.vibeStars).toBe(1);
    expect(api.body.viewer).toBeNull();
    const pub = await t.http().get(`/u/${creator.username}`);
    expect(pub.status).toBe(200);
    expect(pub.body.counts).toMatchObject({ vibeMates: 1, vibeStars: 1 });
    const html = await t.http().get(`/u/${creator.username}`).set('Accept', 'text/html');
    expect(html.headers['content-type']).toMatch(/text\/html/);
    expect(html.text).toMatch(/1<\/b> VibeMates/);
    expect((await t.http().get(`/api/v1/users/${creator.username}/posts`)).body.items.map((p: any) => p.id)).toContain(post.id);
    expect((await t.http().get(`/api/v1/users/${creator.username}/vibemates`)).status).toBe(200);
  });

  it('can view a single post and read comments and replies', async () => {
    const p = await t.http().get(`/api/v1/posts/${post.id}`);
    expect(p.status).toBe(200);
    expect(p.body.counts.vibeClaps).toBe(1);
    expect(p.body.viewer).toBeNull();
    const cs = await t.http().get(`/api/v1/posts/${post.id}/comments`);
    expect(cs.status).toBe(200);
    expect(cs.body.items[0].body).toBe('first!');
    const rs = await t.http().get(`/api/v1/comments/${comment.id}/replies`);
    expect(rs.body.items[0].body).toBe('reply');
  });

  it('private profiles and their posts stay hidden from guests', async () => {
    expect((await t.http().get(`/api/v1/users/${priv.username}`)).status).toBe(404);
    expect((await t.http().get(`/u/${priv.username}`)).status).toBe(404);
    expect((await t.http().get(`/api/v1/users/${priv.username}/posts`)).status).toBe(404);
    expect((await t.http().get(`/api/v1/posts/${privPost.id}`)).status).toBe(404);
    expect((await t.http().get(`/api/v1/posts/${privPost.id}/comments`)).status).toBe(404);
  });

  it('logged-in users get personalised fields on the same public routes', async () => {
    const viewer = await signup(t);
    await t.http().put(`/api/v1/posts/${post.id}/clap`).set(viewer.auth).send({ kind: 'wow' });
    const p = await t.http().get(`/api/v1/posts/${post.id}`).set(viewer.auth);
    expect(p.body.viewer).toMatchObject({ hasClapped: true, clapKind: 'wow', hasSaved: false, isAuthor: false });
    const tr = await t.http().get('/api/v1/feed/trending').set(viewer.auth).query({ limit: 50 });
    expect(tr.body.items.find((x: any) => x.id === post.id).viewer.hasClapped).toBe(true);
    const prof = await t.http().get(`/api/v1/users/${creator.username}`).set(viewer.auth);
    expect(prof.body.viewer.vibeMateStatus).toBe('none');
    // a logged-in non-VibeMate sees the private account's card but not its content
    const pp = await t.http().get(`/api/v1/users/${priv.username}`).set(viewer.auth);
    expect(pp.status).toBe(200);
    expect(pp.body.canViewContent).toBe(false);
    expect((await t.http().get(`/api/v1/users/${priv.username}/posts`).set(viewer.auth)).status).toBe(403);
  });

  it('an invalid token on a public route is a 401, not silently a guest', async () => {
    const r = await t.http().get('/api/v1/feed/trending').set('Authorization', 'Bearer not-a-jwt');
    expect(r.status).toBe(401);
  });
});

describe('guests are denied every write action', () => {
  const cases: [string, string, () => string, object?][] = [
    ['POST', 'create post', () => '/api/v1/posts', { type: 'text', caption: 'x' }],
    ['POST', 'presigned upload', () => '/api/v1/media/uploads', { purpose: 'post_image', contentType: 'image/jpeg', sizeBytes: 10 }],
    ['POST', 'comment', () => `/api/v1/posts/${post.id}/comments`, { body: 'x' }],
    ['PUT', 'VibeClap', () => `/api/v1/posts/${post.id}/clap`, {}],
    ['PUT', 'save', () => `/api/v1/posts/${post.id}/save`],
    ['POST', 'VibeMate', () => `/api/v1/users/${creator.username}/vibemate`],
    ['POST', 'VibeStar', () => `/api/v1/users/${creator.username}/vibestar`],
    ['POST', 'block', () => `/api/v1/users/${creator.username}/block`],
    ['POST', 'report', () => '/api/v1/reports', { targetType: 'post', targetId: '00000000-0000-0000-0000-000000000000', reason: 'spam' }],
    ['GET', 'Following feed', () => '/api/v1/feed/following'],
    ['GET', 'me', () => '/api/v1/me'],
    ['PATCH', 'edit comment', () => `/api/v1/comments/${comment.id}`, { body: 'x' }],
    ['GET', 'admin users', () => '/api/v1/admin/users'],
  ];
  for (const [method, name, path, body] of cases) {
    it(`${name} -> 401 UNAUTHENTICATED`, async () => {
      const req = (t.http() as any)[method.toLowerCase()](path());
      const res = body ? await req.send(body) : await req;
      expect(res.status).toBe(401);
      expect(res.body.error.code).toBe('UNAUTHENTICATED');
    });
  }

  it('nothing was written by guests', async () => {
    const p = await t.http().get(`/api/v1/posts/${post.id}`);
    expect(p.body.counts.saves).toBe(0);
    expect((await t.http().get(`/api/v1/users/${creator.username}`)).body.counts.vibeMates).toBe(1);
  });
});
