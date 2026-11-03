import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { makeApp, signup, type TestApp } from './helpers.js';

let t: TestApp;
beforeAll(async () => {
  t = await makeApp({ RATE_LIMIT_AUTH_PER_MIN: '3', RATE_LIMIT_WRITE_PER_MIN: '4', RATE_LIMIT_GLOBAL_PER_MIN: '40' });
});
afterAll(async () => { await t.app.close(); });

describe('rate limiting', () => {
  it('limits login attempts per IP with a 429 in the standard error format', async () => {
    const codes: number[] = [];
    let last: any;
    for (let i = 0; i < 5; i++) {
      last = await t.http().post('/api/v1/auth/login').send({ identifier: 'someone', password: 'wrong' });
      codes.push(last.status);
    }
    expect(codes.slice(0, 3)).toEqual([401, 401, 401]);
    expect(codes.slice(3)).toEqual([429, 429]);
    expect(last.body.error.code).toBe('RATE_LIMITED');
    expect(last.headers['retry-after']).toBeTruthy();
  });

  it('limits write actions per user, independently for each user', async () => {
    // signups would hit the auth limit (3/min), so create users directly through the app's DB-backed signup in a fresh app
    const t2 = await makeApp({ RATE_LIMIT_WRITE_PER_MIN: '4' });
    const a = await signup(t2);
    const b = await signup(t2);
    const statuses: number[] = [];
    for (let i = 0; i < 6; i++) {
      statuses.push((await t2.http().post('/api/v1/posts').set(a.auth).send({ type: 'text', caption: `spam ${i}` })).status);
    }
    expect(statuses.filter((s) => s === 201)).toHaveLength(4);
    expect(statuses.slice(4)).toEqual([429, 429]);
    expect((await t2.http().post('/api/v1/posts').set(b.auth).send({ type: 'text', caption: 'fine' })).status).toBe(201);
    // reads are not blocked by the write limit
    expect((await t2.http().get('/api/v1/feed/following').set(a.auth)).status).toBe(200);
    await t2.app.close();
  });

  it('global per-IP limit also applies to guests on public routes', async () => {
    const codes: number[] = [];
    for (let i = 0; i < 45; i++) codes.push((await t.http().get('/api/v1/feed/trending')).status);
    expect(codes).toContain(429);
    expect(codes.filter((c) => c === 200).length).toBeLessThanOrEqual(40);
  });

  void signup;
});
