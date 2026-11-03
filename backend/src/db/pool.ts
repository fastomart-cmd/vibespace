import pg from 'pg';
import type { Config } from '../config.js';

export type Db = pg.Pool;
export type Queryable = pg.Pool | pg.PoolClient;

export function createPool(config: Pick<Config, 'DATABASE_URL' | 'DATABASE_SSL' | 'DATABASE_POOL_MAX'>): pg.Pool {
  return new pg.Pool({
    connectionString: config.DATABASE_URL,
    max: config.DATABASE_POOL_MAX,
    // Supabase requires TLS. Its cert chain is not in Node's default store, so
    // either set NODE_EXTRA_CA_CERTS to Supabase's CA file or accept the server cert.
    ssl: config.DATABASE_SSL ? { rejectUnauthorized: !!process.env.NODE_EXTRA_CA_CERTS } : undefined,
  });
}

export async function withTx<T>(pool: pg.Pool, fn: (c: pg.PoolClient) => Promise<T>): Promise<T> {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const out = await fn(client);
    await client.query('COMMIT');
    return out;
  } catch (e) {
    await client.query('ROLLBACK').catch(() => {});
    throw e;
  } finally {
    client.release();
  }
}
