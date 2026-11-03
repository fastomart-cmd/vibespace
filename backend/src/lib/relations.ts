import type { Queryable } from '../db/pool.js';
import { USER_SELECT } from './views.js';
import { Errors } from './errors.js';

/** Load a user by username together with the viewer's relationship to them. */
export async function loadUserWithRelation(db: Queryable, username: string, viewerId: string | null) {
  const { rows } = await db.query(
    `${USER_SELECT.replace('SELECT', `SELECT
       (SELECT status FROM vibemates WHERE from_user_id = $2::uuid AND to_user_id = u.id) AS my_mate_status,
       (SELECT status FROM vibemates WHERE from_user_id = u.id AND to_user_id = $2::uuid) AS their_mate_status,
       EXISTS (SELECT 1 FROM vibestars WHERE user_id = $2::uuid AND channel_id = u.id) AS i_starred,
       EXISTS (SELECT 1 FROM blocks WHERE blocker_id = $2::uuid AND blocked_id = u.id) AS i_blocked,
       EXISTS (SELECT 1 FROM blocks WHERE blocker_id = u.id AND blocked_id = $2::uuid) AS blocked_me,
       EXISTS (SELECT 1 FROM mutes WHERE muter_id = $2::uuid AND muted_id = u.id) AS i_muted,`)}
     WHERE u.username = $1`,
    [username.toLowerCase(), viewerId],
  );
  const r = rows[0];
  if (!r) return null;
  const isSelf = viewerId === r.id;
  return {
    row: r,
    isSelf,
    canSeeContent: isSelf || ((!r.is_private || r.my_mate_status === 'accepted') && !r.blocked_me && !r.i_blocked),
  };
}

/**
 * Profile visibility:
 *  - suspended accounts and accounts that blocked the viewer: 404
 *  - private accounts: 404 for guests (hidden), basic card for logged-in users
 */
export async function requireVisibleUser(db: Queryable, username: string, viewerId: string | null) {
  const t = await loadUserWithRelation(db, username, viewerId);
  if (!t || (t.row.status !== 'active' && !t.isSelf) || t.row.blocked_me) throw Errors.notFound('User');
  if (!viewerId && t.row.is_private) throw Errors.notFound('User');
  return t;
}

export function viewerRelation(r: any) {
  return {
    vibeMateStatus: (r.my_mate_status as 'pending' | 'accepted' | null) ?? 'none',
    isVibeMateOfYou: r.their_mate_status === 'accepted',
    isVibeStar: r.i_starred,
    isBlocked: r.i_blocked,
    isMuted: r.i_muted,
  };
}
