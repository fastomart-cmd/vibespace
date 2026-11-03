import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { makeApp, makePost, signup, type TestApp } from './helpers.js';

let t: TestApp;
beforeAll(async () => { t = await makeApp(); });
afterAll(async () => { await t.app.close(); });

describe('VibeTalk (comments)', () => {
  it('comments, nested replies, counts and pagination', async () => {
    const author = await signup(t);
    const b = await signup(t);
    const post = await makePost(t, author);
    const c1 = await t.http().post(`/api/v1/posts/${post.id}/comments`).set(b.auth).send({ body: 'बहुत बढ़िया!' });
    expect(c1.status).toBe(201);
    const reply = await t.http().post(`/api/v1/posts/${post.id}/comments`).set(author.auth).send({ body: 'Thanks!', parentId: c1.body.id });
    expect(reply.status).toBe(201);
    expect(reply.body.parentId).toBe(c1.body.id);
    const deep = await t.http().post(`/api/v1/posts/${post.id}/comments`).set(b.auth).send({ body: 'reply to reply', parentId: reply.body.id });
    expect(deep.status).toBe(201);
    for (let i = 0; i < 3; i++) await t.http().post(`/api/v1/posts/${post.id}/comments`).set(b.auth).send({ body: `c${i}` });

    const p = await t.http().get(`/api/v1/posts/${post.id}`);
    expect(p.body.counts.vibeTalk).toBe(6);

    const page1 = await t.http().get(`/api/v1/posts/${post.id}/comments`).query({ limit: 2 });
    expect(page1.body.items).toHaveLength(2);
    expect(page1.body.items[0].id).toBe(c1.body.id);
    expect(page1.body.items[0].replyCount).toBe(1);
    const page2 = await t.http().get(`/api/v1/posts/${post.id}/comments`).query({ limit: 2, cursor: page1.body.nextCursor });
    const all = [...page1.body.items, ...page2.body.items].map((c: any) => c.body);
    expect(all).toEqual(['बहुत बढ़िया!', 'c0', 'c1', 'c2']);
    expect(page2.body.nextCursor).toBeNull();

    const replies = await t.http().get(`/api/v1/comments/${c1.body.id}/replies`);
    expect(replies.body.items.map((c: any) => c.body)).toEqual(['Thanks!']);
  });

  it('reply must belong to the same post', async () => {
    const a = await signup(t);
    const p1 = await makePost(t, a);
    const p2 = await makePost(t, a);
    const c = await t.http().post(`/api/v1/posts/${p1.id}/comments`).set(a.auth).send({ body: 'hi' });
    const bad = await t.http().post(`/api/v1/posts/${p2.id}/comments`).set(a.auth).send({ body: 'x', parentId: c.body.id });
    expect(bad.status).toBe(404);
  });

  it('edit/delete own only; deleted parent with replies stays as a placeholder', async () => {
    const a = await signup(t);
    const b = await signup(t);
    const post = await makePost(t, a);
    const c = await t.http().post(`/api/v1/posts/${post.id}/comments`).set(b.auth).send({ body: 'original' });
    expect((await t.http().patch(`/api/v1/comments/${c.body.id}`).set(a.auth).send({ body: 'nope' })).status).toBe(403);
    const ed = await t.http().patch(`/api/v1/comments/${c.body.id}`).set(b.auth).send({ body: 'edited' });
    expect(ed.body.body).toBe('edited');
    expect(ed.body.editedAt).toBeTruthy();
    await t.http().post(`/api/v1/posts/${post.id}/comments`).set(a.auth).send({ body: 'reply', parentId: c.body.id });
    expect((await t.http().delete(`/api/v1/comments/${c.body.id}`).set(a.auth)).status).toBe(403);
    expect((await t.http().delete(`/api/v1/comments/${c.body.id}`).set(b.auth)).status).toBe(204);
    const list = await t.http().get(`/api/v1/posts/${post.id}/comments`);
    expect(list.body.items[0].isDeleted).toBe(true);
    expect(list.body.items[0].body).toBeNull();
    expect(list.body.items[0].author).toBeNull();
    expect((await t.http().get(`/api/v1/posts/${post.id}`)).body.counts.vibeTalk).toBe(1);
  });

  it('only the post creator can pin; one pin per post; replies cannot be pinned', async () => {
    const creator = await signup(t);
    const b = await signup(t);
    const post = await makePost(t, creator);
    const c1 = await t.http().post(`/api/v1/posts/${post.id}/comments`).set(b.auth).send({ body: 'one' });
    const c2 = await t.http().post(`/api/v1/posts/${post.id}/comments`).set(b.auth).send({ body: 'two' });
    const r = await t.http().post(`/api/v1/posts/${post.id}/comments`).set(b.auth).send({ body: 'r', parentId: c1.body.id });
    expect((await t.http().post(`/api/v1/comments/${c1.body.id}/pin`).set(b.auth)).status).toBe(403);
    expect((await t.http().post(`/api/v1/comments/${c1.body.id}/pin`).set(creator.auth)).status).toBe(200);
    expect((await t.http().post(`/api/v1/comments/${c2.body.id}/pin`).set(creator.auth)).status).toBe(200);
    expect((await t.http().post(`/api/v1/comments/${r.body.id}/pin`).set(creator.auth)).status).toBe(400);
    const list = await t.http().get(`/api/v1/posts/${post.id}/comments`);
    expect(list.body.pinned.id).toBe(c2.body.id);
    expect(list.body.items.map((c: any) => c.id)).toEqual([c1.body.id]);
    await t.http().delete(`/api/v1/comments/${c2.body.id}/pin`).set(creator.auth);
    expect((await t.http().get(`/api/v1/posts/${post.id}/comments`)).body.pinned).toBeNull();
  });
});
