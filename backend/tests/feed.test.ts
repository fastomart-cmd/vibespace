import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { makeApp, makePost, signup, uniq, type TestApp, type TestUser } from './helpers.js';

let t: TestApp;
beforeAll(async () => { t = await makeApp(); });
afterAll(async () => { await t.app.close(); });

async function collect(path: string, auth?: TestUser, query: Record<string, string> = {}) {
  const ids: string[] = [];
  let cursor: string | null = null;
  for (let i = 0; i < 50; i++) {
    const res: any = await t.http().get(path).set(auth?.auth ?? {}).query({ limit: 50, ...query, ...(cursor ? { cursor } : {}) });
    expect(res.status).toBe(200);
    ids.push(...res.body.items.map((p: any) => p.id));
    cursor = res.body.nextCursor;
    if (!cursor) break;
  }
  return ids;
}

describe('feeds', () => {
  it('Following: chronological, self + VibeMates only, cursor pagination without gaps or duplicates', async () => {
    const me = await signup(t);
    const friend = await signup(t);
    const stranger = await signup(t);
    await t.http().post(`/api/v1/users/${friend.username}/vibemate`).set(me.auth);
    const created: string[] = [];
    for (let i = 0; i < 5; i++) {
      created.push((await makePost(t, friend, `f${i}`)).id);
      created.push((await makePost(t, me, `m${i}`)).id);
    }
    await makePost(t, stranger, 'not for you');
    const p1 = await t.http().get('/api/v1/feed/following').set(me.auth).query({ limit: 4 });
    expect(p1.body.items).toHaveLength(4);
    const all = await collect('/api/v1/feed/following', me, { limit: '3' } as any);
    expect(all).toEqual([...created].reverse()); // newest first, exactly our 10
  });

  it('Following hides muted accounts; Explore and Trending hide muted and blocked', async () => {
    const me = await signup(t);
    const loud = await signup(t);
    const rude = await signup(t);
    await t.http().post(`/api/v1/users/${loud.username}/vibemate`).set(me.auth);
    const lp = await makePost(t, loud, 'loud post');
    const rp = await makePost(t, rude, 'rude post');
    expect(await collect('/api/v1/feed/following', me)).toContain(lp.id);
    await t.http().post(`/api/v1/users/${loud.username}/mute`).set(me.auth);
    await t.http().post(`/api/v1/users/${rude.username}/block`).set(me.auth);
    expect(await collect('/api/v1/feed/following', me)).not.toContain(lp.id);
    const explore = await collect('/api/v1/feed/explore', me);
    expect(explore).not.toContain(rp.id);
    expect(explore).not.toContain(lp.id);
    const trending = await collect('/api/v1/feed/trending', me);
    expect(trending).not.toContain(rp.id);
    expect(trending).not.toContain(lp.id);
  });

  it('Trending: engagement raises rank, age decays it, private accounts excluded', async () => {
    const author = await signup(t);
    const priv = await signup(t, { isPrivate: true });
    const fans = await Promise.all([1, 2, 3, 4].map(() => signup(t)));
    const quiet = await makePost(t, author, 'quiet');
    const hot = await makePost(t, author, 'hot');
    const oldHot = await makePost(t, author, 'old but clapped');
    const privPost = await makePost(t, priv, 'private');
    for (const f of fans) {
      await t.http().put(`/api/v1/posts/${hot.id}/clap`).set(f.auth).send({});
      await t.http().put(`/api/v1/posts/${oldHot.id}/clap`).set(f.auth).send({});
    }
    await t.http().post(`/api/v1/posts/${hot.id}/comments`).set(fans[0].auth).send({ body: 'nice' });
    await t.app.deps.db.query(`UPDATE posts SET created_at = now() - interval '3 days' WHERE id = $1`, [oldHot.id]);
    const ids = await collect('/api/v1/feed/trending');
    const pos = (id: string) => ids.indexOf(id);
    expect(pos(hot.id)).toBeGreaterThanOrEqual(0);
    expect(pos(hot.id)).toBeLessThan(pos(quiet.id));
    expect(pos(quiet.id)).toBeLessThan(pos(oldHot.id)); // 3 days of decay beats 4 claps
    expect(ids).not.toContain(privPost.id);
    const first = await t.http().get('/api/v1/feed/trending').query({ limit: 50 });
    const scores = first.body.items.map((p: any) => p.trendingScore);
    expect([...scores].sort((a, b) => b - a)).toEqual(scores);
  });

  it('Explore: excludes self and accounts I VibeMate; filters by hashtag', async () => {
    const me = await signup(t);
    const friend = await signup(t);
    const other = await signup(t);
    await t.http().post(`/api/v1/users/${friend.username}/vibemate`).set(me.auth);
    const tag = uniq('explore');
    const mine = await makePost(t, me, `#${tag} mine`);
    const fp = await makePost(t, friend, `#${tag} friend`);
    const op = await makePost(t, other, `#${tag} other`);
    const op2 = await makePost(t, other, 'untagged');
    const ids = await collect('/api/v1/feed/explore', me, { tag: `#${tag.toUpperCase()}` });
    expect(ids).toEqual([op.id]);
    const all = await collect('/api/v1/feed/explore', me);
    expect(all).toContain(op2.id);
    expect(all).not.toContain(mine.id);
    expect(all).not.toContain(fp.id);
  });

  it('rejects a malformed cursor with a 400', async () => {
    const res = await t.http().get('/api/v1/feed/explore').query({ cursor: 'garbage' });
    expect(res.status).toBe(400);
    expect(res.body.error.code).toBe('VALIDATION_ERROR');
  });
});
