import { loadConfig } from '../config.js';
import { createPool } from './pool.js';
import { migrate } from './migrate.js';

const config = loadConfig();
const pool = createPool(config);
migrate(pool)
  .then((applied) => console.log(applied.length ? `done (${applied.length} applied)` : 'up to date'))
  .catch((e) => {
    console.error(e.message);
    process.exitCode = 1;
  })
  .finally(() => pool.end());
