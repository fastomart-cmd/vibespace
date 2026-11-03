import { S3Client, PutObjectCommand, HeadObjectCommand, DeleteObjectCommand } from '@aws-sdk/client-s3';
import { getSignedUrl } from '@aws-sdk/s3-request-presigner';
import type { Config } from '../config.js';

export interface PresignedUpload {
  url: string;
  method: 'PUT';
  headers: Record<string, string>;
  expiresIn: number;
}

export interface Storage {
  readonly configured: boolean;
  /** Provider name, for logs. */
  readonly provider: string;
  /** Hard upper bound on one object, imposed by the provider/plan (Infinity when there is none). */
  readonly maxUploadBytes: number;
  presignPut(key: string, contentType: string, sizeBytes: number): Promise<PresignedUpload>;
  /** Returns object size/content type, or null if it doesn't exist. */
  head(key: string): Promise<{ size: number; contentType?: string } | null>;
  delete(key: string): Promise<void>;
  publicUrl(key: string): string | null;
}

/** Supabase free plan: global file size limit can't exceed 50 MB (supabase.com/docs/guides/storage/uploads/file-limits). */
export const SUPABASE_FREE_MAX_UPLOAD_BYTES = 50 * 1024 * 1024;

export interface S3Settings {
  provider: string;
  endpoint: string;
  region: string;
  bucket: string;
  accessKeyId: string;
  secretAccessKey: string;
  forcePathStyle: boolean;
  publicBaseUrl: string; // '' = no public URL
  maxUploadBytes: number;
  configured: boolean;
}

const trimSlash = (s: string) => s.replace(/\/+$/, '');

export function supabaseProjectUrl(config: Config): string {
  if (config.SUPABASE_URL) return trimSlash(config.SUPABASE_URL);
  return config.SUPABASE_PROJECT_REF ? `https://${config.SUPABASE_PROJECT_REF}.supabase.co` : '';
}

function supabaseStorageHost(config: Config): string {
  // Direct storage hostname (recommended by Supabase for uploads): <ref>.storage.supabase.co
  if (config.SUPABASE_PROJECT_REF) return `https://${config.SUPABASE_PROJECT_REF}.storage.supabase.co`;
  return supabaseProjectUrl(config);
}

function supabasePublicBase(config: Config): string {
  if (config.MEDIA_PUBLIC_BASE_URL) return trimSlash(config.MEDIA_PUBLIC_BASE_URL);
  const base = supabaseProjectUrl(config);
  return base ? `${base}/storage/v1/object/public/${config.SUPABASE_STORAGE_BUCKET}` : '';
}

/** Resolves the S3 settings for STORAGE_PROVIDER = r2 | s3 | supabase (s3 mode). Pure: unit-tested. */
export function resolveS3Settings(config: Config): S3Settings {
  switch (config.STORAGE_PROVIDER) {
    case 'r2':
      return {
        provider: 'r2',
        endpoint: config.R2_ENDPOINT || `https://${config.R2_ACCOUNT_ID || 'unconfigured'}.r2.cloudflarestorage.com`,
        region: 'auto',
        bucket: config.R2_BUCKET,
        accessKeyId: config.R2_ACCESS_KEY_ID,
        secretAccessKey: config.R2_SECRET_ACCESS_KEY,
        forcePathStyle: !!config.R2_ENDPOINT,
        publicBaseUrl: trimSlash(config.MEDIA_PUBLIC_BASE_URL),
        maxUploadBytes: Infinity,
        configured: !!(config.R2_ACCESS_KEY_ID && config.R2_SECRET_ACCESS_KEY && (config.R2_ACCOUNT_ID || config.R2_ENDPOINT)),
      };
    case 's3':
      return {
        provider: 's3',
        endpoint: config.S3_ENDPOINT,
        region: config.S3_REGION,
        bucket: config.S3_BUCKET,
        accessKeyId: config.S3_ACCESS_KEY_ID,
        secretAccessKey: config.S3_SECRET_ACCESS_KEY,
        forcePathStyle: config.S3_FORCE_PATH_STYLE,
        publicBaseUrl: trimSlash(config.MEDIA_PUBLIC_BASE_URL),
        maxUploadBytes: config.S3_MAX_UPLOAD_BYTES || Infinity,
        configured: !!(config.S3_ENDPOINT && config.S3_BUCKET && config.S3_ACCESS_KEY_ID && config.S3_SECRET_ACCESS_KEY),
      };
    case 'supabase': {
      const host = supabaseStorageHost(config);
      return {
        provider: 'supabase',
        endpoint: config.S3_ENDPOINT || (host ? `${host}/storage/v1/s3` : ''),
        region: config.SUPABASE_S3_REGION,
        bucket: config.SUPABASE_STORAGE_BUCKET,
        accessKeyId: config.SUPABASE_S3_ACCESS_KEY_ID,
        secretAccessKey: config.SUPABASE_S3_SECRET_ACCESS_KEY,
        forcePathStyle: true, // Supabase requires path-style
        publicBaseUrl: supabasePublicBase(config),
        maxUploadBytes: SUPABASE_FREE_MAX_UPLOAD_BYTES,
        configured: !!(host && config.SUPABASE_S3_REGION && config.SUPABASE_S3_ACCESS_KEY_ID && config.SUPABASE_S3_SECRET_ACCESS_KEY),
      };
    }
  }
}

/**
 * Any S3-compatible store (Cloudflare R2, Supabase Storage's S3 endpoint, MinIO, AWS...).
 * Presigned URL generation is pure local signing and is exercised by the tests; real uploads/HEAD/DELETE
 * need real credentials and are unverified in the sandbox.
 */
export class S3Storage implements Storage {
  private client: S3Client;
  readonly configured: boolean;
  readonly provider: string;
  readonly maxUploadBytes: number;

  constructor(private config: Config, readonly settings: S3Settings = resolveS3Settings(config)) {
    this.configured = settings.configured;
    this.provider = settings.provider;
    this.maxUploadBytes = settings.maxUploadBytes;
    this.client = new S3Client({
      region: settings.region || 'auto',
      endpoint: settings.endpoint || 'https://unconfigured.invalid',
      forcePathStyle: settings.forcePathStyle,
      credentials: { accessKeyId: settings.accessKeyId || 'x', secretAccessKey: settings.secretAccessKey || 'x' },
    });
  }

  async presignPut(key: string, contentType: string, sizeBytes: number): Promise<PresignedUpload> {
    const expiresIn = this.config.MEDIA_UPLOAD_URL_TTL_SECONDS;
    const cmd = new PutObjectCommand({ Bucket: this.settings.bucket, Key: key, ContentType: contentType, ContentLength: sizeBytes });
    // content-type and content-length are signed, so the client must send exactly these values.
    const url = await getSignedUrl(this.client, cmd, { expiresIn, signableHeaders: new Set(['content-type', 'content-length']) });
    return { url, method: 'PUT', headers: { 'Content-Type': contentType, 'Content-Length': String(sizeBytes) }, expiresIn };
  }

  async head(key: string) {
    try {
      const r = await this.client.send(new HeadObjectCommand({ Bucket: this.settings.bucket, Key: key }));
      return { size: Number(r.ContentLength ?? 0), contentType: r.ContentType };
    } catch (e: any) {
      if (e?.$metadata?.httpStatusCode === 404 || e?.name === 'NotFound') return null;
      throw e;
    }
  }

  async delete(key: string) {
    await this.client.send(new DeleteObjectCommand({ Bucket: this.settings.bucket, Key: key }));
  }

  publicUrl(key: string) {
    return this.settings.publicBaseUrl ? `${this.settings.publicBaseUrl}/${key}` : null;
  }
}

/** Kept for backwards compatibility: R2 is just S3Storage with STORAGE_PROVIDER=r2. */
export class R2Storage extends S3Storage {
  constructor(config: Config) {
    super(config, resolveS3Settings({ ...config, STORAGE_PROVIDER: 'r2' }));
  }
}

/**
 * Supabase Storage via its REST "signed upload URL" (what supabase-js createSignedUploadUrl calls):
 *   POST {project}/storage/v1/object/upload/sign/{bucket}/{key}  (service role key)  -> { url: "/object/upload/sign/...?token=..." }
 * The client then does a single PUT of the raw bytes, with Content-Type, to that URL: the same shape as an S3 presigned PUT,
 * so the Android app needs no change. Use this if S3 presigned PUTs are rejected (SUPABASE_UPLOAD_MODE=signed-url).
 * Note: Supabase signed upload URLs are valid for 2 hours (fixed by Supabase), not MEDIA_UPLOAD_URL_TTL_SECONDS,
 * and the size is not signed into the URL, so the bucket's file size limit and MEDIA_VERIFY_UPLOADS are the guards.
 */
export class SupabaseSignedUrlStorage implements Storage {
  readonly configured: boolean;
  readonly provider = 'supabase';
  readonly maxUploadBytes = SUPABASE_FREE_MAX_UPLOAD_BYTES;
  private base: string; // https://<ref>.supabase.co/storage/v1
  private publicBase: string;

  constructor(private config: Config, private fetchImpl: typeof fetch = fetch) {
    const project = supabaseProjectUrl(config);
    this.base = project ? `${project}/storage/v1` : '';
    this.publicBase = supabasePublicBase(config);
    this.configured = !!(project && config.SUPABASE_SERVICE_ROLE_KEY);
  }

  private headers(extra: Record<string, string> = {}) {
    const k = this.config.SUPABASE_SERVICE_ROLE_KEY;
    return { Authorization: `Bearer ${k}`, apikey: k, ...extra };
  }

  private objectPath(key: string) {
    return `${encodeURIComponent(this.config.SUPABASE_STORAGE_BUCKET)}/${key.split('/').map(encodeURIComponent).join('/')}`;
  }

  async presignPut(key: string, contentType: string, _sizeBytes: number): Promise<PresignedUpload> {
    const res = await this.fetchImpl(`${this.base}/object/upload/sign/${this.objectPath(key)}`, {
      method: 'POST',
      headers: this.headers({ 'Content-Type': 'application/json' }),
      body: '{}',
    });
    if (!res.ok) throw new Error(`Supabase createSignedUploadUrl failed: ${res.status} ${await res.text()}`);
    const data = (await res.json()) as { url?: string };
    if (!data.url || !data.url.includes('token=')) throw new Error('Supabase createSignedUploadUrl returned no token');
    return { url: `${this.base}${data.url}`, method: 'PUT', headers: { 'Content-Type': contentType }, expiresIn: 7200 };
  }

  async head(key: string) {
    const res = await this.fetchImpl(`${this.base}/object/${this.objectPath(key)}`, { method: 'HEAD', headers: this.headers() });
    if (res.status === 404 || res.status === 400) return null;
    if (!res.ok) throw new Error(`Supabase HEAD failed: ${res.status}`);
    return { size: Number(res.headers.get('content-length') ?? 0), contentType: res.headers.get('content-type') ?? undefined };
  }

  async delete(key: string) {
    const res = await this.fetchImpl(`${this.base}/object/${encodeURIComponent(this.config.SUPABASE_STORAGE_BUCKET)}`, {
      method: 'DELETE',
      headers: this.headers({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ prefixes: [key] }),
    });
    if (!res.ok) throw new Error(`Supabase delete failed: ${res.status} ${await res.text()}`);
  }

  publicUrl(key: string) {
    return this.publicBase ? `${this.publicBase}/${key}` : null;
  }
}

export function createStorage(config: Config): Storage {
  if (config.STORAGE_PROVIDER === 'supabase' && config.SUPABASE_UPLOAD_MODE === 'signed-url') {
    return new SupabaseSignedUrlStorage(config);
  }
  return new S3Storage(config);
}

/** Largest allowed upload for a purpose: app limit, avatar cap, and the provider's hard limit (Supabase free: 50 MB). */
export function maxUploadBytes(config: Config, storage: Pick<Storage, 'maxUploadBytes'>, purpose: string): number {
  const appMax = purpose === 'avatar' ? Math.min(config.MEDIA_MAX_IMAGE_BYTES, 5 * 1024 * 1024) : config.MEDIA_MAX_IMAGE_BYTES;
  return Math.min(appMax, storage.maxUploadBytes);
}
