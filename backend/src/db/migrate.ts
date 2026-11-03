import { readdir, readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import type pg from 'pg';

// migrations/ sits at the package root; this file is src/db/ or dist/db/.
export const MIGRATIONS_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../migrations');

export async function migrate(pool: pg.Pool, log: (m: string) => void = console.log): Promise<string[]> {
  const client = await pool.connect();
  const applied: string[] = [];
  try {
    await client.query('SELECT pg_advisory_lock(727274)'); // one migrator at a time
    await client.query(`CREATE TABLE IF NOT EXISTS schema_migrations (
      name text PRIMARY KEY, checksum text NOT NULL, applied_at timestamptz NOT NULL DEFAULT now())`);
    const done = new Map<string, string>(
      (await client.query('SELECT name, checksum FROM schema_migrations')).rows.map((r) => [r.name, r.checksum]),
    );
    const files = (await readdir(MIGRATIONS_DIR)).filter((f) => f.endsWith('.sql')).sort();
    for (const file of files) {
      const sql = await readFile(path.join(MIGRATIONS_DIR, file), 'utf8');
      const sum = createHash('sha256').update(sql).digest('hex');
      if (done.has(file)) {
        if (done.get(file) !== sum) throw new Error(`Migration ${file} was edited after being applied`);
        continue;
      }
      await client.query('BEGIN');
      try {
        await client.query(sql);
        await client.query('INSERT INTO schema_migrations (name, checksum) VALUES ($1, $2)', [file, sum]);
        await client.query('COMMIT');
      } catch (e) {
        await client.query('ROLLBACK');
        throw new Error(`Migration ${file} failed: ${(e as Error).message}`);
      }
      log(`applied ${file}`);
      applied.push(file);
    }
  } finally {
    await client.query('SELECT pg_advisory_unlock(727274)').catch(() => {});
    client.release();
  }
  return applied;
}
