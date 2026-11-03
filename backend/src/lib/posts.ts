import type pg from 'pg';
import type { Queryable } from '../db/pool.js';
import { extractHashtags, extractMentions } from './text.js';
import { POST_SELECT, postVisible } from './views.js';
import { Errors } from './errors.js';

/** Fetch a post the viewer is allowed to see, or throw 404 (never reveal hidden posts). */
export async function getVisiblePost(db: Queryable, postId: string, viewerId: string | null) {
  const { rows } = await db.query(`${POST_SELECT(1)} WHERE p.id = $2 AND ${postVisible(1)}`, [viewerId, postId]);
  if (!rows[0]) throw Errors.notFound('Post');
  return rows[0];
}

/** (Re)write hashtags and mentions for a post from its caption. Runs inside a transaction. */
export async function syncTagsAndMentions(c: pg.PoolClient, postId: string, authorId: string, caption: string) {
  await c.query('DELETE FROM post_hashtags WHERE post_id = $1', [postId]);
  await c.query('DELETE FROM post_mentions WHERE post_id = $1', [postId]);
  const tags = extractHashtags(caption);
  if (tags.length) {
    await c.query(
      `WITH t AS (
         INSERT INTO hashtags (tag) SELECT unnest($2::text[])
         ON CONFLICT (tag) DO UPDATE SET tag = EXCLUDED.tag RETURNING id)
       INSERT INTO post_hashtags (post_id, hashtag_id) SELECT $1, id FROM t ON CONFLICT DO NOTHING`,
      [postId, tags],
    );
  }
  const names = extractMentions(caption);
  if (names.length) {
    // Only real, active accounts that haven't blocked (and aren't blocked by) the author.
    await c.query(
      `INSERT INTO post_mentions (post_id, mentioned_user_id)
       SELECT $1, u.id FROM users u
        WHERE u.username = ANY($2::text[]) AND u.status = 'active' AND u.id <> $3
          AND NOT EXISTS (SELECT 1 FROM blocks b WHERE (b.blocker_id = u.id AND b.blocked_id = $3) OR (b.blocker_id = $3 AND b.blocked_id = u.id))
       ON CONFLICT DO NOTHING`,
      [postId, names, authorId],
    );
  }
}
