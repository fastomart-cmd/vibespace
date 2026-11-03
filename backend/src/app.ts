import Fastify, { type FastifyInstance, type FastifyError } from 'fastify';
import cors from '@fastify/cors';
import helmet from '@fastify/helmet';
import rateLimit from '@fastify/rate-limit';
import swagger from '@fastify/swagger';
import swaggerUi from '@fastify/swagger-ui';
import {
  serializerCompiler,
  validatorCompiler,
  jsonSchemaTransform,
  hasZodFastifySchemaValidationErrors,
  type ZodTypeProvider,
} from 'fastify-type-provider-zod';
import type { Config } from './config.js';
import { createPool, type Db } from './db/pool.js';
import { AppError, localise } from './lib/errors.js';
import { makeAuthHooks } from './lib/auth.js';
import { createMailer, type Mailer } from './services/mailer.js';
import { createStorage, type Storage } from './services/storage.js';
import authRoutes from './routes/auth.js';
import userRoutes from './routes/users.js';
import socialRoutes from './routes/social.js';
import mediaRoutes from './routes/media.js';
import postRoutes from './routes/posts.js';
import commentRoutes from './routes/comments.js';
import feedRoutes from './routes/feeds.js';
import reportRoutes from './routes/reports.js';
import adminRoutes from './routes/admin.js';
import publicRoutes from './routes/public.js';

export interface Deps {
  config: Config;
  db: Db;
  mailer: Mailer;
  storage: Storage;
  auth: ReturnType<typeof makeAuthHooks>;
}

declare module 'fastify' {
  interface FastifyInstance {
    deps: Deps;
  }
}

export type App = FastifyInstance;

export async function buildApp(config: Config, overrides: Partial<Pick<Deps, 'db' | 'mailer' | 'storage'>> = {}) {
  const app = Fastify({
    logger: config.NODE_ENV === "test" && !process.env.DEBUG_LOG ? false : { level: config.LOG_LEVEL },
    trustProxy: config.TRUST_PROXY,
    bodyLimit: 256 * 1024, // media never goes through the API; it's uploaded straight to object storage
  }).withTypeProvider<ZodTypeProvider>();

  app.setValidatorCompiler(validatorCompiler);
  app.setSerializerCompiler(serializerCompiler);

  const db = overrides.db ?? createPool(config);
  const deps: Deps = {
    config,
    db,
    mailer: overrides.mailer ?? createMailer(config, (o, m) => app.log.info(o, m)),
    storage: overrides.storage ?? createStorage(config),
    auth: makeAuthHooks(db, config),
  };
  app.decorate('deps', deps);
  app.decorateRequest('user', null);
  if (!overrides.db) app.addHook('onClose', async () => db.end());

  await app.register(helmet, { contentSecurityPolicy: false });
  await app.register(cors, {
    origin: config.CORS_ORIGINS ? config.CORS_ORIGINS.split(',').map((s) => s.trim()) : false,
  });

  // Global per-IP limit. Auth and write routes add stricter per-route limits.
  await app.register(rateLimit, {
    global: true,
    max: config.RATE_LIMIT_GLOBAL_PER_MIN,
    timeWindow: '1 minute',
    keyGenerator: (req) => (req.user ? `u:${req.user.id}` : `ip:${req.ip}`),
    errorResponseBuilder: (_req, ctx) => {
      const e = new AppError(429, 'RATE_LIMITED', undefined, { retryAfterSeconds: Math.ceil(ctx.ttl / 1000) });
      return e as any;
    },
  });

  await app.register(swagger, {
    openapi: {
      info: {
        title: 'VibeSpace API',
        description:
          'Create. Share. Earn. Phase 1 backend. Guests can browse public content; acting requires a Bearer access token.',
        version: '0.1.0',
      },
      servers: [{ url: config.PUBLIC_BASE_URL }],
      components: { securitySchemes: { bearerAuth: { type: 'http', scheme: 'bearer', bearerFormat: 'JWT' } } },
      tags: [
        { name: 'auth' }, { name: 'me' }, { name: 'users' }, { name: 'vibemates-vibestars' }, { name: 'safety' },
        { name: 'media' }, { name: 'posts' }, { name: 'vibetalk' }, { name: 'feeds' }, { name: 'reports' }, { name: 'admin' },
      ],
    },
    transform: jsonSchemaTransform,
  });
  await app.register(swaggerUi, { routePrefix: '/docs' });

  app.setErrorHandler((err: FastifyError & { details?: unknown }, req, reply) => {
    const lang = req.headers['accept-language'];
    let status = 500;
    let code = 'INTERNAL';
    let message = '';
    let details: unknown;
    const pg = err as unknown as { code?: string; constraint?: string };

    if (hasZodFastifySchemaValidationErrors(err)) {
      status = 400;
      code = 'VALIDATION_ERROR';
      details = err.validation.map((v) => ({ path: v.instancePath, message: v.message }));
    } else if (err instanceof AppError) {
      status = err.statusCode;
      code = err.code;
      message = err.message;
      details = err.details;
    } else if (err.statusCode === 429) {
      status = 429;
      code = 'RATE_LIMITED';
    } else if (pg.code === '23505') {
      status = 409;
      code = 'CONFLICT';
    } else if (pg.code === '22P02' || pg.code === '22007') {
      status = 400;
      code = 'VALIDATION_ERROR';
    } else if (err.statusCode && err.statusCode >= 400 && err.statusCode < 500) {
      status = err.statusCode;
      code = err.code === 'FST_ERR_CTP_INVALID_MEDIA_TYPE' ? 'VALIDATION_ERROR' : 'BAD_REQUEST';
      message = err.message;
    }
    if (status >= 500) req.log.error({ err }, 'unhandled error');
    reply.status(status).send({ error: { code, message: localise(code, message, lang), ...(details ? { details } : {}) }, requestId: req.id });
  });

  app.setNotFoundHandler((req, reply) => {
    reply.status(404).send({ error: { code: 'NOT_FOUND', message: localise('NOT_FOUND', 'Route not found.', req.headers['accept-language']) }, requestId: req.id });
  });

  app.get('/health', { schema: { hide: true } }, async () => {
    await db.query('SELECT 1');
    return { ok: true };
  });

  await app.register(publicRoutes);
  await app.register(
    async (api) => {
      await api.register(authRoutes, { prefix: '/auth' });
      await api.register(userRoutes);
      await api.register(socialRoutes);
      await api.register(mediaRoutes);
      await api.register(postRoutes);
      await api.register(commentRoutes);
      await api.register(feedRoutes);
      await api.register(reportRoutes);
      await api.register(adminRoutes, { prefix: '/admin' });
    },
    { prefix: '/api/v1' },
  );

  return app;
}
