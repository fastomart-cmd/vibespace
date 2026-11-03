import { loadConfig } from './config.js';
import { buildApp } from './app.js';
import { createPool } from './db/pool.js';
import { migrate } from './db/migrate.js';

const config = loadConfig();

// Render free has no pre-deploy command or shell, so migrations can run at boot (RUN_MIGRATIONS_ON_START=true).
// Safe to repeat: applied files are recorded in schema_migrations (with checksums), each file runs in its own
// transaction, and a Postgres advisory lock makes concurrent boots wait for each other instead of racing.
// Needs a session-level connection: Supabase *Session* pooler (5432) or direct, never the transaction pooler (6543).
if (config.RUN_MIGRATIONS_ON_START) {
  const pool = createPool({ ...config, DATABASE_POOL_MAX: 1 });
  try {
    const applied = await migrate(pool, (m) => console.log(`[migrate] ${m}`));
    console.log(`[migrate] ${applied.length ? `${applied.length} applied` : 'up to date'}`);
  } catch (e) {
    console.error(`[migrate] failed, not starting: ${(e as Error).message}`);
    await pool.end().catch(() => {});
    process.exit(1);
  }
  await pool.end();
}

const app = await buildApp(config);
const { storage } = app.deps;
app.log.info({ provider: storage.provider, configured: storage.configured }, 'media storage');
if (config.NODE_ENV === 'production' && !config.RESEND_API_KEY && config.MAILER !== 'resend') {
  app.log.warn('No RESEND_API_KEY: password-reset emails are written to the log instead of being sent');
}

const shutdown = async (sig: string) => {
  app.log.info(`${sig} received, shutting down`);
  await app.close();
  process.exit(0);
};
process.on('SIGTERM', () => void shutdown('SIGTERM'));
process.on('SIGINT', () => void shutdown('SIGINT'));

// Render injects PORT (default 10000) and needs 0.0.0.0, which is the HOST default.
await app.listen({ host: config.HOST, port: config.PORT });
