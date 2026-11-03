import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { makeApp, signup, uniq, type TestApp } from './helpers.js';

let t: TestApp;
beforeAll(async () => { t = await makeApp(); });
afterAll(async () => { await t.app.close(); });

describe('auth', () => {
  it('signs up, returns tokens, never returns the password hash', async () => {
    const username = uniq('Signup_');
    const res = await t.http().post('/api/v1/auth/signup').send({ email: `${username}@Example.com`, username, password: 'password123', language: 'hi' });
    expect(res.status).toBe(201);
    expect(res.body.user.username).toBe(username.toLowerCase());
    expect(res.body.accessToken).toBeTypeOf('string');
    expect(res.body.refreshToken).toBeTypeOf('string');
    expect(JSON.stringify(res.body)).not.toMatch(/argon2|password_hash/);
    const row = await t.app.deps.db.query('SELECT refresh_token_hash FROM sessions WHERE user_id = $1', [res.body.user.id]);
    expect(row.rows[0].refresh_token_hash).not.toBe(res.body.refreshToken); // stored hashed
    expect(row.rows[0].refresh_token_hash).toMatch(/^[0-9a-f]{64}$/);
  });

  it('enforces unique username and email (case-insensitive)', async () => {
    const u = await signup(t);
    const dupUser = await t.http().post('/api/v1/auth/signup').send({ email: `${uniq()}@x.com`, username: u.username.toUpperCase(), password: 'password123' });
    expect(dupUser.status).toBe(409);
    expect(dupUser.body.error.code).toBe('USERNAME_TAKEN');
    const dupEmail = await t.http().post('/api/v1/auth/signup').send({ email: u.email.toUpperCase(), username: uniq('x_'), password: 'password123' });
    expect(dupEmail.status).toBe(409);
    expect(dupEmail.body.error.code).toBe('EMAIL_TAKEN');
    const avail = await t.http().get('/api/v1/auth/username-available').query({ username: u.username });
    expect(avail.body.available).toBe(false);
    const reserved = await t.http().post('/api/v1/auth/signup').send({ email: `${uniq()}@x.com`, username: 'admin', password: 'password123' });
    expect(reserved.status).toBe(409);
  });

  it('validates input with a consistent error envelope (localised to Hindi)', async () => {
    const res = await t.http().post('/api/v1/auth/signup').set('Accept-Language', 'hi-IN').send({ email: 'nope', username: 'a', password: 'x' });
    expect(res.status).toBe(400);
    expect(res.body.error.code).toBe('VALIDATION_ERROR');
    expect(res.body.error.message).toBe('कुछ जानकारी सही नहीं है।');
    expect(Array.isArray(res.body.error.details)).toBe(true);
    expect(res.body.requestId).toBeTruthy();
  });

  it('logs in by username or email; rejects bad passwords', async () => {
    const u = await signup(t);
    const byName = await t.http().post('/api/v1/auth/login').send({ identifier: u.username, password: u.password });
    expect(byName.status).toBe(200);
    const byEmail = await t.http().post('/api/v1/auth/login').send({ identifier: u.email, password: u.password });
    expect(byEmail.status).toBe(200);
    const bad = await t.http().post('/api/v1/auth/login').send({ identifier: u.username, password: 'wrong-password' });
    expect(bad.status).toBe(401);
    expect(bad.body.error.code).toBe('INVALID_CREDENTIALS');
    const ghost = await t.http().post('/api/v1/auth/login').send({ identifier: 'nobody_here', password: 'whatever' });
    expect(ghost.status).toBe(401);
  });

  it('rotates refresh tokens and detects reuse', async () => {
    const u = await signup(t);
    const r1 = await t.http().post('/api/v1/auth/refresh').send({ refreshToken: u.refreshToken });
    expect(r1.status).toBe(200);
    expect(r1.body.refreshToken).not.toBe(u.refreshToken);
    const me = await t.http().get('/api/v1/me').set('Authorization', `Bearer ${r1.body.accessToken}`);
    expect(me.status).toBe(200);
    // Re-using the old token => 401 and every session is revoked (including r1's)
    const reuse = await t.http().post('/api/v1/auth/refresh').send({ refreshToken: u.refreshToken });
    expect(reuse.status).toBe(401);
    const r2 = await t.http().post('/api/v1/auth/refresh').send({ refreshToken: r1.body.refreshToken });
    expect(r2.status).toBe(401);
    const meAfter = await t.http().get('/api/v1/me').set('Authorization', `Bearer ${r1.body.accessToken}`);
    expect(meAfter.status).toBe(401);
  });

  it('logout revokes the session immediately', async () => {
    const u = await signup(t);
    expect((await t.http().get('/api/v1/me').set(u.auth)).status).toBe(200);
    expect((await t.http().post('/api/v1/auth/logout').send({ refreshToken: u.refreshToken })).status).toBe(204);
    expect((await t.http().get('/api/v1/me').set(u.auth)).status).toBe(401);
    expect((await t.http().post('/api/v1/auth/refresh').send({ refreshToken: u.refreshToken })).status).toBe(401);
  });

  it('password reset: email via mailer, single-use token, signs out other sessions', async () => {
    const u = await signup(t);
    const unknown = await t.http().post('/api/v1/auth/password/forgot').send({ email: 'nobody@example.com' });
    expect(unknown.status).toBe(202); // no account enumeration
    const before = t.mailer.sent.length;
    const res = await t.http().post('/api/v1/auth/password/forgot').send({ email: u.email });
    expect(res.status).toBe(202);
    expect(t.mailer.sent.length).toBe(before + 1);
    const mail = t.mailer.sent.at(-1)!;
    expect(mail.to).toBe(u.email);
    const token = /token=([A-Za-z0-9_-]+)/.exec(mail.text)![1];
    const reset = await t.http().post('/api/v1/auth/password/reset').send({ token, newPassword: 'brand new password' });
    expect(reset.status).toBe(204);
    expect((await t.http().get('/api/v1/me').set(u.auth)).status).toBe(401);
    const again = await t.http().post('/api/v1/auth/password/reset').send({ token, newPassword: 'another password' });
    expect(again.status).toBe(401);
    expect((await t.http().post('/api/v1/auth/login').send({ identifier: u.username, password: u.password })).status).toBe(401);
    expect((await t.http().post('/api/v1/auth/login').send({ identifier: u.username, password: 'brand new password' })).status).toBe(200);
  });

  it('rejects tampered or garbage access tokens', async () => {
    const u = await signup(t);
    const [h, p] = u.accessToken.split('.');
    const forged = `${h}.${p}.invalidsignature`;
    expect((await t.http().get('/api/v1/me').set('Authorization', `Bearer ${forged}`)).status).toBe(401);
    expect((await t.http().get('/api/v1/me').set('Authorization', 'Basic abc')).status).toBe(401);
  });
});
