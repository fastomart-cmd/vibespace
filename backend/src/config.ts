import 'dotenv/config';
import { z } from 'zod';

const bool = z
  .enum(['true', 'false', '1', '0'])
  .transform((v) => v === 'true' || v === '1');

const EnvSchema = z.object({
  NODE_ENV: z.enum(['development', 'test', 'production']).default('development'),
  HOST: z.string().default('0.0.0.0'),
  PORT: z.coerce.number().int().default(3000),
  // Render sets RENDER_EXTERNAL_URL (https://<service>.onrender.com) at runtime; used when PUBLIC_BASE_URL is not set.
  PUBLIC_BASE_URL: z.string().url().default(process.env.RENDER_EXTERNAL_URL || 'http://localhost:3000'),
  LOG_LEVEL: z.string().default('info'),
  TRUST_PROXY: bool.default('false'),
  CORS_ORIGINS: z.string().default(''),

  DATABASE_URL: z.string().min(1),
  DATABASE_SSL: bool.default('false'),
  DATABASE_POOL_MAX: z.coerce.number().int().default(10),
  // Apply pending migrations at boot (Render free has no pre-deploy step or shell). Idempotent and advisory-locked.
  RUN_MIGRATIONS_ON_START: bool.default('false'),

  JWT_ACCESS_SECRET: z.string().min(32, 'JWT_ACCESS_SECRET must be at least 32 chars'),
  JWT_ISSUER: z.string().default('vibespace'),
  ACCESS_TOKEN_TTL_SECONDS: z.coerce.number().int().default(900),
  REFRESH_TOKEN_TTL_DAYS: z.coerce.number().int().default(30),
  PASSWORD_RESET_TTL_MINUTES: z.coerce.number().int().default(30),
  PASSWORD_RESET_URL: z.string().default('vibespace://reset-password?token='),

  RATE_LIMIT_GLOBAL_PER_MIN: z.coerce.number().int().default(300),
  RATE_LIMIT_AUTH_PER_MIN: z.coerce.number().int().default(10),
  RATE_LIMIT_WRITE_PER_MIN: z.coerce.number().int().default(60),
  // Post views (anti-bot): view-recording requests per viewer (user or device) per minute, and how many
  // NEW views one IP may add per hour. Indian mobile networks put many users behind one IP (CGNAT),
  // so the IP budget is generous; requests over it still succeed but stop adding views.
  RATE_LIMIT_VIEWS_PER_MIN: z.coerce.number().int().default(60),
  VIEWS_MAX_NEW_PER_IP_PER_HOUR: z.coerce.number().int().default(2000),

  // Unset = resend when RESEND_API_KEY is set, otherwise console (logs emails incl. reset links). HTTP API only:
  // Render free blocks outbound SMTP ports, so there is deliberately no SMTP mailer.
  MAILER: z.preprocess((v) => (v === '' ? undefined : v), z.enum(['console', 'resend']).optional()),
  MAIL_FROM: z.string().default('VibeSpace <no-reply@vibespace.local>'),
  RESEND_API_KEY: z.string().optional(),

  // ---- media storage: r2 (Cloudflare R2) | s3 (any S3-compatible endpoint) | supabase (Supabase Storage)
  STORAGE_PROVIDER: z.enum(['r2', 's3', 'supabase']).default('r2'),

  R2_ACCOUNT_ID: z.string().default(''),
  R2_ACCESS_KEY_ID: z.string().default(''),
  R2_SECRET_ACCESS_KEY: z.string().default(''),
  R2_BUCKET: z.string().default('vibespace-media'),
  R2_ENDPOINT: z.string().default(''), // optional override, e.g. a MinIO URL for local dev

  S3_ENDPOINT: z.string().default(''), // generic S3, or an override of the derived Supabase S3 endpoint
  S3_REGION: z.string().default('auto'),
  S3_BUCKET: z.string().default(''),
  S3_ACCESS_KEY_ID: z.string().default(''),
  S3_SECRET_ACCESS_KEY: z.string().default(''),
  S3_FORCE_PATH_STYLE: bool.default('true'),
  S3_MAX_UPLOAD_BYTES: z.coerce.number().int().nonnegative().default(0), // 0 = no provider cap

  SUPABASE_PROJECT_REF: z.string().default(''), // the <ref> in https://<ref>.supabase.co
  SUPABASE_URL: z.string().default(''), // optional; defaults to https://<ref>.supabase.co
  SUPABASE_STORAGE_BUCKET: z.string().default('vibespace-media'),
  // s3 = presigned PUT via the S3 endpoint (needs the S3 access keys); signed-url = REST signed upload URL (needs service role key)
  SUPABASE_UPLOAD_MODE: z.enum(['s3', 'signed-url']).default('s3'),
  SUPABASE_S3_REGION: z.string().default(''),
  SUPABASE_S3_ACCESS_KEY_ID: z.string().default(''),
  SUPABASE_S3_SECRET_ACCESS_KEY: z.string().default(''),
  SUPABASE_SERVICE_ROLE_KEY: z.string().default(''),

  // public base URL for reading media. R2: custom domain / r2.dev. Supabase: optional, defaults to
  // https://<ref>.supabase.co/storage/v1/object/public/<bucket>
  MEDIA_PUBLIC_BASE_URL: z.string().default(''),
  MEDIA_MAX_IMAGE_BYTES: z.coerce.number().int().default(10 * 1024 * 1024),
  MEDIA_UPLOAD_URL_TTL_SECONDS: z.coerce.number().int().default(600),
  MEDIA_VERIFY_UPLOADS: bool.default('false'), // HEAD the object in storage before attaching it
});

export type Config = z.infer<typeof EnvSchema>;

export function loadConfig(overrides: Partial<Record<keyof Config, string>> = {}): Config {
  const parsed = EnvSchema.safeParse({ ...process.env, ...overrides });
  if (!parsed.success) {
    const msg = parsed.error.issues.map((i) => `${i.path.join('.')}: ${i.message}`).join('; ');
    throw new Error(`Invalid environment configuration: ${msg}`);
  }
  return parsed.data;
}
