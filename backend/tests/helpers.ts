import { randomBytes } from 'node:crypto';
import request from 'supertest';
import { loadConfig, type Config } from '../src/config.js';
import { buildApp } from '../src/app.js';
import { MemoryMailer } from '../src/services/mailer.js';
import { TEST_DATABASE_URL } from './global-setup.js';

export async function makeApp(overrides: Partial<Record<keyof Config, string>> = {}) {
  const config = loadConfig({
    NODE_ENV: 'test',
    DATABASE_URL: TEST_DATABASE_URL,
    JWT_ACCESS_SECRET: 'test-secret-test-secret-test-secret-123456',
    RATE_LIMIT_GLOBAL_PER_MIN: '100000',
    RATE_LIMIT_AUTH_PER_MIN: '100000',
    RATE_LIMIT_WRITE_PER_MIN: '100000',
    RATE_LIMIT_VIEWS_PER_MIN: '100000',
    // Dummy R2 credentials: presigned URLs are signed locally, nothing is uploaded.
    R2_ACCOUNT_ID: 'testaccount',
    R2_ACCESS_KEY_ID: 'test-access-key',
    R2_SECRET_ACCESS_KEY: 'test-secret-key',
    R2_BUCKET: 'vibespace-test',
    MEDIA_PUBLIC_BASE_URL: 'https://media.test.local',
    MEDIA_VERIFY_UPLOADS: 'false',
    ...overrides,
  });
  const mailer = new MemoryMailer();
  const app = await buildApp(config, { mailer });
  await app.ready();
  return { app, mailer, config, http: () => request(app.server) };
}

export type TestApp = Awaited<ReturnType<typeof makeApp>>;

export const uniq = (p = 'u') => `${p}${randomBytes(4).toString('hex')}`;

export interface TestUser {
  id: string;
  username: string;
  email: string;
  password: string;
  accessToken: string;
  refreshToken: string;
  auth: { Authorization: string };
}

export async function signup(t: TestApp, opts: { username?: string; isPrivate?: boolean } = {}): Promise<TestUser> {
  const username = opts.username ?? uniq('user_');
  const email = `${username}@example.com`;
  const password = 'correct horse battery';
  const res = await t.http().post('/api/v1/auth/signup').send({ email, username, password, displayName: `Name ${username}` });
  if (res.status !== 201) throw new Error(`signup failed ${res.status} ${JSON.stringify(res.body)}`);
  const u: TestUser = {
    id: res.body.user.id,
    username,
    email,
    password,
    accessToken: res.body.accessToken,
    refreshToken: res.body.refreshToken,
    auth: { Authorization: `Bearer ${res.body.accessToken}` },
  };
  if (opts.isPrivate) {
    const r = await t.http().patch('/api/v1/me').set(u.auth).send({ isPrivate: true });
    if (r.status !== 200) throw new Error('private failed');
  }
  return u;
}

export async function makePost(t: TestApp, u: TestUser, caption = 'hello vibes') {
  const res = await t.http().post('/api/v1/posts').set(u.auth).send({ type: 'text', caption });
  if (res.status !== 201) throw new Error(`post failed ${res.status} ${JSON.stringify(res.body)}`);
  return res.body;
}

export async function setRole(t: TestApp, userId: string, role: string) {
  await t.app.deps.db.query('UPDATE users SET role = $2 WHERE id = $1', [userId, role]);
}
