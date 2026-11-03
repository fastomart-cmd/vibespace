import { describe, it, expect, afterAll } from 'vitest';
import { loadConfig, type Config } from '../src/config.js';
import {
  createStorage,
  resolveS3Settings,
  maxUploadBytes,
  S3Storage,
  SupabaseSignedUrlStorage,
  SUPABASE_FREE_MAX_UPLOAD_BYTES,
} from '../src/services/storage.js';
import { makeApp, signup, type TestApp } from './helpers.js';
import { createMailer, ConsoleMailer, ResendMailer } from '../src/services/mailer.js';

const MB = 1024 * 1024;
const base = { DATABASE_URL: 'postgres://x', JWT_ACCESS_SECRET: 'x'.repeat(40) };
const cfg = (o: Partial<Record<keyof Config, string>>) => loadConfig({ ...base, ...o });

const supa = {
  STORAGE_PROVIDER: 'supabase',
  SUPABASE_PROJECT_REF: 'abcdefghijklmnop',
  SUPABASE_STORAGE_BUCKET: 'vibespace-media',
  SUPABASE_S3_REGION: 'ap-south-1',
  SUPABASE_S3_ACCESS_KEY_ID: 'supa-key',
  SUPABASE_S3_SECRET_ACCESS_KEY: 'supa-secret',
} as const;

describe('storage provider config (unit)', () => {
  it('defaults to R2 and keeps the old R2 behaviour', () => {
    const s = resolveS3Settings(cfg({ R2_ACCOUNT_ID: 'acc', R2_ACCESS_KEY_ID: 'k', R2_SECRET_ACCESS_KEY: 's', R2_BUCKET: 'b', MEDIA_PUBLIC_BASE_URL: 'https://media.x/' }));
    expect(s).toMatchObject({ provider: 'r2', endpoint: 'https://acc.r2.cloudflarestorage.com', region: 'auto', bucket: 'b', forcePathStyle: false, configured: true, publicBaseUrl: 'https://media.x' });
    expect(s.maxUploadBytes).toBe(Infinity);
  });

  it('R2 is not configured without keys', () => {
    expect(resolveS3Settings(cfg({})).configured).toBe(false);
  });

  it('generic S3: endpoint/region/bucket/keys/public URL', async () => {
    const c = cfg({ STORAGE_PROVIDER: 's3', S3_ENDPOINT: 'http://localhost:9000', S3_REGION: 'us-east-1', S3_BUCKET: 'media', S3_ACCESS_KEY_ID: 'k', S3_SECRET_ACCESS_KEY: 's', MEDIA_PUBLIC_BASE_URL: 'http://localhost:9000/media' });
    const st = createStorage(c);
    expect(st).toBeInstanceOf(S3Storage);
    expect(st.configured).toBe(true);
    const up = await st.presignPut('posts/u/1.jpg', 'image/jpeg', 100);
    const u = new URL(up.url);
    expect(u.host).toBe('localhost:9000');
    expect(u.pathname).toBe('/media/posts/u/1.jpg'); // path style
    expect(u.searchParams.get('X-Amz-Credential')).toContain('/us-east-1/s3/aws4_request');
    expect(st.publicUrl('posts/u/1.jpg')).toBe('http://localhost:9000/media/posts/u/1.jpg');
    expect(createStorage(cfg({ STORAGE_PROVIDER: 's3', S3_ENDPOINT: 'http://x' })).configured).toBe(false);
  });

  it('supabase (s3 mode): derived endpoint, path style, region, public URL, 50 MB cap', async () => {
    const st = createStorage(cfg(supa));
    expect(st).toBeInstanceOf(S3Storage);
    expect(st.configured).toBe(true);
    expect(st.maxUploadBytes).toBe(50 * MB);
    const s = (st as S3Storage).settings;
    expect(s.endpoint).toBe('https://abcdefghijklmnop.storage.supabase.co/storage/v1/s3');
    expect(s.forcePathStyle).toBe(true);
    const up = await st.presignPut('posts/u/1.jpg', 'image/jpeg', 1234);
    const u = new URL(up.url);
    expect(u.host).toBe('abcdefghijklmnop.storage.supabase.co');
    expect(u.pathname).toBe('/storage/v1/s3/vibespace-media/posts/u/1.jpg');
    expect(u.searchParams.get('X-Amz-Credential')).toMatch(/^supa-key\/\d{8}\/ap-south-1\/s3\/aws4_request$/);
    expect(u.searchParams.get('X-Amz-SignedHeaders')).toContain('content-length');
    expect(up).toMatchObject({ method: 'PUT', headers: { 'Content-Type': 'image/jpeg', 'Content-Length': '1234' } });
    expect(st.publicUrl('posts/u/1.jpg')).toBe('https://abcdefghijklmnop.supabase.co/storage/v1/object/public/vibespace-media/posts/u/1.jpg');
  });

  it('supabase: not configured without S3 keys; MEDIA_PUBLIC_BASE_URL overrides the public URL', () => {
    expect(createStorage(cfg({ STORAGE_PROVIDER: 'supabase', SUPABASE_PROJECT_REF: 'ref' })).configured).toBe(false);
    const st = createStorage(cfg({ ...supa, MEDIA_PUBLIC_BASE_URL: 'https://cdn.example.in' }));
    expect(st.publicUrl('a/b.png')).toBe('https://cdn.example.in/a/b.png');
  });

  it('max upload: app limit, avatar 5 MB, and never above 50 MB on Supabase', () => {
    const big = cfg({ ...supa, MEDIA_MAX_IMAGE_BYTES: String(200 * MB) });
    const st = createStorage(big);
    expect(maxUploadBytes(big, st, 'post_image')).toBe(SUPABASE_FREE_MAX_UPLOAD_BYTES);
    expect(maxUploadBytes(big, st, 'avatar')).toBe(5 * MB);
    const r2 = cfg({ MEDIA_MAX_IMAGE_BYTES: String(200 * MB) });
    expect(maxUploadBytes(r2, createStorage(r2), 'post_image')).toBe(200 * MB);
  });

  it('supabase signed-url mode: calls createSignedUploadUrl REST and returns a single-PUT upload of the same shape', async () => {
    const c = cfg({ STORAGE_PROVIDER: 'supabase', SUPABASE_UPLOAD_MODE: 'signed-url', SUPABASE_PROJECT_REF: 'ref123', SUPABASE_SERVICE_ROLE_KEY: 'service-role' });
    expect(createStorage(c)).toBeInstanceOf(SupabaseSignedUrlStorage);
    const calls: { url: string; init: RequestInit }[] = [];
    const fakeFetch = (async (url: string, init: RequestInit) => {
      calls.push({ url, init });
      return new Response(JSON.stringify({ url: '/object/upload/sign/vibespace-media/posts/u/1.jpg?token=abc.def' }), { status: 200 });
    }) as unknown as typeof fetch;
    const st = new SupabaseSignedUrlStorage(c, fakeFetch);
    expect(st.configured).toBe(true);
    expect(st.maxUploadBytes).toBe(50 * MB);
    const up = await st.presignPut('posts/u/1.jpg', 'image/jpeg', 99);
    expect(calls[0].url).toBe('https://ref123.supabase.co/storage/v1/object/upload/sign/vibespace-media/posts/u/1.jpg');
    expect(calls[0].init.method).toBe('POST');
    expect((calls[0].init.headers as Record<string, string>).Authorization).toBe('Bearer service-role');
    expect(up).toEqual({
      url: 'https://ref123.supabase.co/storage/v1/object/upload/sign/vibespace-media/posts/u/1.jpg?token=abc.def',
      method: 'PUT',
      headers: { 'Content-Type': 'image/jpeg' },
      expiresIn: 7200,
    });
    expect(st.publicUrl('posts/u/1.jpg')).toBe('https://ref123.supabase.co/storage/v1/object/public/vibespace-media/posts/u/1.jpg');
  });

  it('supabase signed-url mode: errors from Supabase surface, missing key = not configured', async () => {
    const c = cfg({ STORAGE_PROVIDER: 'supabase', SUPABASE_UPLOAD_MODE: 'signed-url', SUPABASE_PROJECT_REF: 'ref123', SUPABASE_SERVICE_ROLE_KEY: 'k' });
    const st = new SupabaseSignedUrlStorage(c, (async () => new Response('{"error":"Bucket not found"}', { status: 400 })) as unknown as typeof fetch);
    await expect(st.presignPut('a.jpg', 'image/jpeg', 1)).rejects.toThrow(/400/);
    expect(createStorage(cfg({ STORAGE_PROVIDER: 'supabase', SUPABASE_UPLOAD_MODE: 'signed-url', SUPABASE_PROJECT_REF: 'r' })).configured).toBe(false);
  });

  it('PUBLIC_BASE_URL and MAILER defaults', () => {
    expect(cfg({}).MAILER).toBeUndefined();
    expect(cfg({ MAILER: '' }).MAILER).toBeUndefined(); // MAILER= in .env.example
    expect(cfg({ RUN_MIGRATIONS_ON_START: 'true' }).RUN_MIGRATIONS_ON_START).toBe(true);
    expect(createMailer(cfg({}))).toBeInstanceOf(ConsoleMailer); // dev without a key: reset links are logged
    expect(createMailer(cfg({ RESEND_API_KEY: 're_x' }))).toBeInstanceOf(ResendMailer);
    expect(createMailer(cfg({ MAILER: 'console', RESEND_API_KEY: 're_x' }))).toBeInstanceOf(ConsoleMailer);
  });
});

describe('POST /media/uploads with STORAGE_PROVIDER=supabase (integration)', () => {
  let t: TestApp;
  afterAll(async () => t?.app.close());

  it('presigns to the Supabase S3 endpoint and rejects files over 50 MB', async () => {
    t = await makeApp({ ...supa, MEDIA_MAX_IMAGE_BYTES: String(100 * MB), MEDIA_PUBLIC_BASE_URL: '' });
    const a = await signup(t);
    const ok = await t.http().post('/api/v1/media/uploads').set(a.auth).send({ purpose: 'post_image', contentType: 'image/jpeg', sizeBytes: 50 * MB });
    expect(ok.status).toBe(201);
    expect(new URL(ok.body.upload.url).host).toBe('abcdefghijklmnop.storage.supabase.co');
    expect(ok.body.media.publicUrl).toMatch(/^https:\/\/abcdefghijklmnop\.supabase\.co\/storage\/v1\/object\/public\/vibespace-media\/posts\//);
    const big = await t.http().post('/api/v1/media/uploads').set(a.auth).send({ purpose: 'post_image', contentType: 'image/jpeg', sizeBytes: 50 * MB + 1 });
    expect(big.status).toBe(400);
    expect(big.body.error.code).toBe('MEDIA_INVALID');
  });
});
