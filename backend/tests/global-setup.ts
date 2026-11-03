import pg from 'pg';
import { migrate } from '../src/db/migrate.js';

export const TEST_DATABASE_URL =
  process.env.TEST_DATABASE_URL ?? 'postgres://vibespace:vibespace@localhost:5432/vibespace_test';

// Fresh schema on every run, migrated with the real migration runner.
export default async function setup() {
  const pool = new pg.Pool({ connectionString: TEST_DATABASE_URL });
  await pool.query('DROP SCHEMA public CASCADE; CREATE SCHEMA public;');
  await migrate(pool, () => {});
  await pool.end();
}
