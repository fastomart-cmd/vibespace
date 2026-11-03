-- Post view counts (VibeSpace "views", like YouTube / Instagram).
-- posts.view_count is a denormalised counter; post_views records unique viewers so one viewer
-- adds at most one view per post per 24 hours. viewer_key is 'u:<user id>' for logged-in users,
-- or 'd:<HMAC-SHA256 of the X-Device-Id header>' (or 'i:<hash of IP + user agent>' as a fallback)
-- for guests. Raw device ids and IPs are never stored.
ALTER TABLE posts ADD COLUMN view_count bigint NOT NULL DEFAULT 0 CHECK (view_count >= 0);

CREATE TABLE post_views (
  post_id          uuid NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
  viewer_key       text NOT NULL CHECK (char_length(viewer_key) BETWEEN 3 AND 100),
  user_id          uuid REFERENCES users(id) ON DELETE SET NULL,
  first_viewed_at  timestamptz NOT NULL DEFAULT now(),
  last_counted_at  timestamptz NOT NULL DEFAULT now(),
  times_counted    integer NOT NULL DEFAULT 1 CHECK (times_counted >= 1),
  PRIMARY KEY (post_id, viewer_key)
);
-- for pruning old rows (rows older than 24h only matter for "first viewed" history)
CREATE INDEX post_views_last_counted_idx ON post_views (last_counted_at);
CREATE INDEX post_views_user_idx ON post_views (user_id) WHERE user_id IS NOT NULL;
