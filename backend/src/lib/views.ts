// Shared SQL fragments and row -> JSON mappers. "$v" placeholders are replaced with the
// parameter index that holds the viewer's id (NULL for guests).
import type { Storage } from '../services/storage.js';

const v = (i: number) => `$${i}::uuid`;

/** Not blocked in either direction (always true for guests). */
export const notBlocked = (vi: number, otherCol: string) => `(${v(vi)} IS NULL OR NOT EXISTS (
  SELECT 1 FROM blocks b WHERE (b.blocker_id = ${v(vi)} AND b.blocked_id = ${otherCol})
                            OR (b.blocker_id = ${otherCol} AND b.blocked_id = ${v(vi)})))`;

export const notMuted = (vi: number, otherCol: string) => `(${v(vi)} IS NULL OR NOT EXISTS (
  SELECT 1 FROM mutes mu WHERE mu.muter_id = ${v(vi)} AND mu.muted_id = ${otherCol}))`;

/** Viewer may see this account's content: public, self, or an accepted VibeMate. */
export const canSeeContentOf = (vi: number, alias: string) => `(${alias}.is_private = false OR ${alias}.id = ${v(vi)}
  OR EXISTS (SELECT 1 FROM vibemates vm WHERE vm.from_user_id = ${v(vi)} AND vm.to_user_id = ${alias}.id AND vm.status = 'accepted'))`;

/** Full visibility rule for a post row p joined with its author a. */
export const postVisible = (vi: number) =>
  `p.status = 'active' AND a.status = 'active' AND ${canSeeContentOf(vi, 'a')} AND ${notBlocked(vi, 'a.id')}`;

export const POST_SELECT = (vi: number) => `
SELECT p.id, p.type, p.caption, p.clap_count, p.comment_count, p.save_count, p.view_count,
       p.created_at, p.updated_at, p.created_at::text AS cursor_t,
       a.id AS author_id, a.username AS author_username, a.display_name AS author_display_name,
       am.object_key AS author_avatar_key,
       COALESCE((SELECT json_agg(json_build_object('id', m.id, 'key', m.object_key, 'contentType', m.content_type,
                 'width', m.width, 'height', m.height) ORDER BY m.position)
                 FROM media m WHERE m.post_id = p.id), '[]'::json) AS media,
       COALESCE((SELECT array_agg(h.tag ORDER BY h.tag) FROM post_hashtags ph JOIN hashtags h ON h.id = ph.hashtag_id
                 WHERE ph.post_id = p.id), '{}') AS hashtags,
       COALESCE((SELECT array_agg(mu.username ORDER BY mu.username) FROM post_mentions pm JOIN users mu ON mu.id = pm.mentioned_user_id
                 WHERE pm.post_id = p.id), '{}') AS mentions,
       (SELECT c.kind FROM vibeclaps c WHERE c.post_id = p.id AND c.user_id = ${v(vi)}) AS my_clap,
       EXISTS (SELECT 1 FROM saves s WHERE s.post_id = p.id AND s.user_id = ${v(vi)}) AS has_saved
  FROM posts p
  JOIN users a ON a.id = p.author_id
  LEFT JOIN media am ON am.id = a.avatar_media_id`;

export function mapPost(r: any, storage: Storage, viewerId: string | null) {
  return {
    id: r.id,
    type: r.type,
    caption: r.caption,
    hashtags: r.hashtags,
    mentions: r.mentions,
    media: (r.media as any[]).map((m) => ({
      id: m.id,
      url: storage.publicUrl(m.key),
      contentType: m.contentType,
      width: m.width,
      height: m.height,
    })),
    author: {
      id: r.author_id,
      username: r.author_username,
      displayName: r.author_display_name,
      avatarUrl: r.author_avatar_key ? storage.publicUrl(r.author_avatar_key) : null,
    },
    counts: { vibeClaps: r.clap_count, vibeTalk: r.comment_count, saves: r.save_count },
    viewCount: Number(r.view_count ?? 0), // unique viewers, at most one view per viewer per 24h
    viewer: viewerId
      ? { hasClapped: r.my_clap !== null, clapKind: r.my_clap, hasSaved: r.has_saved, isAuthor: r.author_id === viewerId }
      : null,
    createdAt: r.created_at,
    updatedAt: r.updated_at,
    ...(r.score !== undefined ? { trendingScore: Number(Number(r.score).toFixed(6)) } : {}),
  };
}

export const USER_SELECT = `
SELECT u.id, u.username, u.display_name, u.bio, u.is_private, u.status, u.language,
       u.vibemates_count, u.vibing_with_count, u.vibestars_count, u.starred_count, u.posts_count,
       u.created_at, am.object_key AS avatar_key
  FROM users u LEFT JOIN media am ON am.id = u.avatar_media_id`;

export function mapUserSummary(r: any, storage: Storage) {
  return {
    id: r.id,
    username: r.username,
    displayName: r.display_name,
    avatarUrl: r.avatar_key ? storage.publicUrl(r.avatar_key) : null,
  };
}

export function mapProfile(r: any, storage: Storage) {
  return {
    ...mapUserSummary(r, storage),
    bio: r.bio,
    isPrivate: r.is_private,
    profileUrl: `/u/${r.username}`,
    counts: {
      vibeMates: r.vibemates_count, // accounts that VibeMate this user
      vibingWith: r.vibing_with_count, // accounts this user VibeMates
      vibeStars: r.vibestars_count, // VibeStars of this user's channel
      starred: r.starred_count, // channels this user has starred
      posts: r.posts_count,
    },
    createdAt: r.created_at,
  };
}
