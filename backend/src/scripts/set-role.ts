// Bootstrap staff accounts: npm run set-role -- <username> <user|moderator|admin>
import { loadConfig } from '../config.js';
import { createPool } from '../db/pool.js';

const [username, role] = process.argv.slice(2);
if (!username || !['user', 'moderator', 'admin'].includes(role)) {
  console.error('usage: npm run set-role -- <username> <user|moderator|admin>');
  process.exit(1);
}
const pool = createPool(loadConfig());
const { rowCount } = await pool.query('UPDATE users SET role = $2 WHERE username = $1', [username.toLowerCase(), role]);
await pool.query(
  `INSERT INTO admin_audit_logs (admin_id, action, target_type, target_id, details)
   SELECT NULL, 'user.role_change', 'user', id, jsonb_build_object('to', $2::text, 'via', 'cli') FROM users WHERE username = $1`,
  [username.toLowerCase(), role],
);
console.log(rowCount ? `${username} is now ${role}` : `no user named ${username}`);
await pool.end();
