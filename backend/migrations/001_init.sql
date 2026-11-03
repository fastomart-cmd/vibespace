-- VibeSpace Phase 1 schema.
-- All counters are maintained by triggers inside the database; the API never
-- accepts counts from clients.

CREATE EXTENSION IF NOT EXISTS pgcrypto; -- gen_random_uuid() (built in on PG13+, harmless otherwise)

-- ---------------------------------------------------------------- users
CREATE TABLE users (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  email              text NOT NULL,
  username           text NOT NULL,
  password_hash      text NOT NULL,
  display_name       text NOT NULL,
  bio                text NOT NULL DEFAULT '',
  avatar_media_id    uuid,
  is_private         boolean NOT NULL DEFAULT false,
  language           text NOT NULL DEFAULT 'en' CHECK (language IN ('en','hi')),
  role               text NOT NULL DEFAULT 'user' CHECK (role IN ('user','moderator','admin')),
  status             text NOT NULL DEFAULT 'active' CHECK (status IN ('active','suspended')),
  suspended_reason   text,
  vibemates_count    integer NOT NULL DEFAULT 0 CHECK (vibemates_count >= 0),   -- people who VibeMate this user
  vibing_with_count  integer NOT NULL DEFAULT 0 CHECK (vibing_with_count >= 0), -- people this user VibeMates
  vibestars_count    integer NOT NULL DEFAULT 0 CHECK (vibestars_count >= 0),   -- VibeStars of this user's channel
  starred_count      integer NOT NULL DEFAULT 0 CHECK (starred_count >= 0),     -- channels this user has starred
  posts_count        integer NOT NULL DEFAULT 0 CHECK (posts_count >= 0),
  created_at         timestamptz NOT NULL DEFAULT now(),
  updated_at         timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT users_username_format CHECK (username ~ '^[a-z0-9_.]{3,30}$'),
  CONSTRAINT users_display_name_len CHECK (char_length(display_name) BETWEEN 1 AND 60),
  CONSTRAINT users_bio_len CHECK (char_length(bio) <= 300)
);
CREATE UNIQUE INDEX users_email_lower_uq ON users (lower(email));
CREATE UNIQUE INDEX users_username_uq ON users (username);
CREATE INDEX users_created_at_idx ON users (created_at DESC);

-- ---------------------------------------------------------------- sessions (refresh tokens, hashed)
CREATE TABLE sessions (
  id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id             uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  refresh_token_hash  text NOT NULL,
  user_agent          text,
  ip                  text,
  created_at          timestamptz NOT NULL DEFAULT now(),
  last_used_at        timestamptz NOT NULL DEFAULT now(),
  expires_at          timestamptz NOT NULL,
  revoked_at          timestamptz,
  replaced_by         uuid REFERENCES sessions(id) ON DELETE SET NULL
);
CREATE UNIQUE INDEX sessions_token_hash_uq ON sessions (refresh_token_hash);
CREATE INDEX sessions_user_idx ON sessions (user_id) WHERE revoked_at IS NULL;

CREATE TABLE password_reset_tokens (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash  text NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT now(),
  expires_at  timestamptz NOT NULL,
  used_at     timestamptz
);
CREATE UNIQUE INDEX password_reset_token_hash_uq ON password_reset_tokens (token_hash);
CREATE INDEX password_reset_user_idx ON password_reset_tokens (user_id);

-- ---------------------------------------------------------------- media (metadata only; bytes live in R2)
CREATE TABLE media (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_id      uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  purpose       text NOT NULL CHECK (purpose IN ('avatar','post_image')),
  object_key    text NOT NULL,
  content_type  text NOT NULL,
  size_bytes    integer NOT NULL CHECK (size_bytes > 0),
  width         integer,
  height        integer,
  status        text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','attached')),
  post_id       uuid,
  position      smallint,
  created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX media_object_key_uq ON media (object_key);
CREATE INDEX media_owner_idx ON media (owner_id, created_at DESC);
CREATE INDEX media_post_idx ON media (post_id) WHERE post_id IS NOT NULL;

ALTER TABLE users ADD CONSTRAINT users_avatar_fk FOREIGN KEY (avatar_media_id) REFERENCES media(id) ON DELETE SET NULL;

-- ---------------------------------------------------------------- VibeMates (follow) and VibeStars (channel subscription): separate tables
CREATE TABLE vibemates (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  from_user_id  uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  to_user_id    uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  status        text NOT NULL DEFAULT 'accepted' CHECK (status IN ('pending','accepted')),
  created_at    timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT vibemates_not_self CHECK (from_user_id <> to_user_id),
  CONSTRAINT vibemates_pair_uq UNIQUE (from_user_id, to_user_id)
);
CREATE INDEX vibemates_to_idx ON vibemates (to_user_id, status, created_at DESC);
CREATE INDEX vibemates_from_idx ON vibemates (from_user_id, status, created_at DESC);

CREATE TABLE vibestars (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id        uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE, -- who starred
  channel_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE, -- the creator's channel
  created_at     timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT vibestars_not_self CHECK (user_id <> channel_id),
  CONSTRAINT vibestars_pair_uq UNIQUE (user_id, channel_id)
);
CREATE INDEX vibestars_channel_idx ON vibestars (channel_id, created_at DESC);

CREATE TABLE blocks (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  blocker_id  uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  blocked_id  uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at  timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT blocks_not_self CHECK (blocker_id <> blocked_id),
  CONSTRAINT blocks_pair_uq UNIQUE (blocker_id, blocked_id)
);
CREATE INDEX blocks_blocked_idx ON blocks (blocked_id);

CREATE TABLE mutes (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  muter_id    uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  muted_id    uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at  timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT mutes_not_self CHECK (muter_id <> muted_id),
  CONSTRAINT mutes_pair_uq UNIQUE (muter_id, muted_id)
);

-- ---------------------------------------------------------------- posts
CREATE TABLE posts (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  author_id       uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  type            text NOT NULL CHECK (type IN ('text','image')),
  caption         text NOT NULL DEFAULT '',
  status          text NOT NULL DEFAULT 'active' CHECK (status IN ('active','deleted','removed')),
  removed_reason  text,
  clap_count      integer NOT NULL DEFAULT 0 CHECK (clap_count >= 0),
  comment_count   integer NOT NULL DEFAULT 0 CHECK (comment_count >= 0),
  save_count      integer NOT NULL DEFAULT 0 CHECK (save_count >= 0),
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT posts_caption_len CHECK (char_length(caption) <= 2200)
);
CREATE INDEX posts_author_idx ON posts (author_id, created_at DESC, id DESC) WHERE status = 'active';
CREATE INDEX posts_recent_idx ON posts (created_at DESC, id DESC) WHERE status = 'active';

ALTER TABLE media ADD CONSTRAINT media_post_fk FOREIGN KEY (post_id) REFERENCES posts(id) ON DELETE CASCADE;

CREATE TABLE hashtags (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tag         text NOT NULL,          -- stored lower-cased, without '#'
  post_count  integer NOT NULL DEFAULT 0 CHECK (post_count >= 0),
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX hashtags_tag_uq ON hashtags (tag);

CREATE TABLE post_hashtags (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  post_id     uuid NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
  hashtag_id  uuid NOT NULL REFERENCES hashtags(id) ON DELETE CASCADE,
  CONSTRAINT post_hashtags_uq UNIQUE (post_id, hashtag_id)
);
CREATE INDEX post_hashtags_tag_idx ON post_hashtags (hashtag_id);

CREATE TABLE post_mentions (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  post_id            uuid NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
  mentioned_user_id  uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  CONSTRAINT post_mentions_uq UNIQUE (post_id, mentioned_user_id)
);
CREATE INDEX post_mentions_user_idx ON post_mentions (mentioned_user_id);

-- VibeClaps: one reaction per user per post; kind can be changed
CREATE TABLE vibeclaps (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  post_id     uuid NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
  user_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  kind        text NOT NULL DEFAULT 'clap' CHECK (kind IN ('clap','fire','love','laugh','wow')),
  created_at  timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT vibeclaps_uq UNIQUE (post_id, user_id)
);
CREATE INDEX vibeclaps_user_idx ON vibeclaps (user_id);

-- VibeTalk: comments with nested replies
CREATE TABLE comments (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  post_id      uuid NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
  author_id    uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  parent_id    uuid REFERENCES comments(id) ON DELETE CASCADE,
  body         text NOT NULL,
  status       text NOT NULL DEFAULT 'active' CHECK (status IN ('active','deleted','removed')),
  is_pinned    boolean NOT NULL DEFAULT false,
  reply_count  integer NOT NULL DEFAULT 0 CHECK (reply_count >= 0),
  edited_at    timestamptz,
  created_at   timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT comments_body_len CHECK (char_length(body) BETWEEN 1 AND 1000),
  CONSTRAINT comments_pin_top_level CHECK (NOT is_pinned OR parent_id IS NULL)
);
CREATE INDEX comments_post_idx ON comments (post_id, parent_id, created_at, id);
CREATE INDEX comments_parent_idx ON comments (parent_id, created_at, id) WHERE parent_id IS NOT NULL;
CREATE UNIQUE INDEX comments_one_pin_per_post ON comments (post_id) WHERE is_pinned;

CREATE TABLE saves (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  post_id     uuid NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
  created_at  timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT saves_uq UNIQUE (user_id, post_id)
);
CREATE INDEX saves_user_idx ON saves (user_id, created_at DESC);

CREATE TABLE reports (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  reporter_id  uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  target_type  text NOT NULL CHECK (target_type IN ('post','comment','user')),
  target_id    uuid NOT NULL,
  reason       text NOT NULL CHECK (reason IN ('spam','harassment','hate','nudity','violence','misinformation','impersonation','other')),
  details      text NOT NULL DEFAULT '' CHECK (char_length(details) <= 1000),
  status       text NOT NULL DEFAULT 'open' CHECK (status IN ('open','resolved','dismissed')),
  resolved_by  uuid REFERENCES users(id) ON DELETE SET NULL,
  resolved_at  timestamptz,
  created_at   timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT reports_once_uq UNIQUE (reporter_id, target_type, target_id)
);
CREATE INDEX reports_status_idx ON reports (status, created_at DESC);
CREATE INDEX reports_target_idx ON reports (target_type, target_id);

CREATE TABLE admin_audit_logs (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  admin_id     uuid REFERENCES users(id) ON DELETE SET NULL,
  action       text NOT NULL,
  target_type  text NOT NULL,
  target_id    uuid,
  details      jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX admin_audit_logs_created_idx ON admin_audit_logs (created_at DESC);
CREATE INDEX admin_audit_logs_target_idx ON admin_audit_logs (target_type, target_id);

-- ================================================================ counter triggers
CREATE OR REPLACE FUNCTION trg_vibemates_counts() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN
    IF NEW.status = 'accepted' THEN
      UPDATE users SET vibemates_count = vibemates_count + 1 WHERE id = NEW.to_user_id;
      UPDATE users SET vibing_with_count = vibing_with_count + 1 WHERE id = NEW.from_user_id;
    END IF;
  ELSIF TG_OP = 'UPDATE' THEN
    IF OLD.status <> 'accepted' AND NEW.status = 'accepted' THEN
      UPDATE users SET vibemates_count = vibemates_count + 1 WHERE id = NEW.to_user_id;
      UPDATE users SET vibing_with_count = vibing_with_count + 1 WHERE id = NEW.from_user_id;
    ELSIF OLD.status = 'accepted' AND NEW.status <> 'accepted' THEN
      UPDATE users SET vibemates_count = vibemates_count - 1 WHERE id = NEW.to_user_id;
      UPDATE users SET vibing_with_count = vibing_with_count - 1 WHERE id = NEW.from_user_id;
    END IF;
  ELSIF TG_OP = 'DELETE' THEN
    IF OLD.status = 'accepted' THEN
      UPDATE users SET vibemates_count = GREATEST(vibemates_count - 1, 0) WHERE id = OLD.to_user_id;
      UPDATE users SET vibing_with_count = GREATEST(vibing_with_count - 1, 0) WHERE id = OLD.from_user_id;
    END IF;
  END IF;
  RETURN NULL;
END $$;
CREATE TRIGGER vibemates_counts AFTER INSERT OR UPDATE OR DELETE ON vibemates
  FOR EACH ROW EXECUTE FUNCTION trg_vibemates_counts();

CREATE OR REPLACE FUNCTION trg_vibestars_counts() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN
    UPDATE users SET vibestars_count = vibestars_count + 1 WHERE id = NEW.channel_id;
    UPDATE users SET starred_count = starred_count + 1 WHERE id = NEW.user_id;
  ELSIF TG_OP = 'DELETE' THEN
    UPDATE users SET vibestars_count = GREATEST(vibestars_count - 1, 0) WHERE id = OLD.channel_id;
    UPDATE users SET starred_count = GREATEST(starred_count - 1, 0) WHERE id = OLD.user_id;
  END IF;
  RETURN NULL;
END $$;
CREATE TRIGGER vibestars_counts AFTER INSERT OR DELETE ON vibestars
  FOR EACH ROW EXECUTE FUNCTION trg_vibestars_counts();

-- posts_count counts only active posts
CREATE OR REPLACE FUNCTION trg_posts_counts() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'INSERT' AND NEW.status = 'active' THEN
    UPDATE users SET posts_count = posts_count + 1 WHERE id = NEW.author_id;
  ELSIF TG_OP = 'UPDATE' AND OLD.status = 'active' AND NEW.status <> 'active' THEN
    UPDATE users SET posts_count = GREATEST(posts_count - 1, 0) WHERE id = NEW.author_id;
  ELSIF TG_OP = 'UPDATE' AND OLD.status <> 'active' AND NEW.status = 'active' THEN
    UPDATE users SET posts_count = posts_count + 1 WHERE id = NEW.author_id;
  ELSIF TG_OP = 'DELETE' AND OLD.status = 'active' THEN
    UPDATE users SET posts_count = GREATEST(posts_count - 1, 0) WHERE id = OLD.author_id;
  END IF;
  RETURN NULL;
END $$;
CREATE TRIGGER posts_counts AFTER INSERT OR UPDATE OF status OR DELETE ON posts
  FOR EACH ROW EXECUTE FUNCTION trg_posts_counts();

CREATE OR REPLACE FUNCTION trg_vibeclaps_counts() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN
    UPDATE posts SET clap_count = clap_count + 1 WHERE id = NEW.post_id;
  ELSIF TG_OP = 'DELETE' THEN
    UPDATE posts SET clap_count = GREATEST(clap_count - 1, 0) WHERE id = OLD.post_id;
  END IF;
  RETURN NULL;
END $$;
CREATE TRIGGER vibeclaps_counts AFTER INSERT OR DELETE ON vibeclaps
  FOR EACH ROW EXECUTE FUNCTION trg_vibeclaps_counts();

CREATE OR REPLACE FUNCTION trg_saves_counts() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN
    UPDATE posts SET save_count = save_count + 1 WHERE id = NEW.post_id;
  ELSIF TG_OP = 'DELETE' THEN
    UPDATE posts SET save_count = GREATEST(save_count - 1, 0) WHERE id = OLD.post_id;
  END IF;
  RETURN NULL;
END $$;
CREATE TRIGGER saves_counts AFTER INSERT OR DELETE ON saves
  FOR EACH ROW EXECUTE FUNCTION trg_saves_counts();

-- comment_count = active comments (incl. replies); reply_count = active direct replies
CREATE OR REPLACE FUNCTION trg_comments_counts() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  delta int := 0;
  r comments%ROWTYPE;
BEGIN
  IF TG_OP = 'INSERT' THEN
    IF NEW.status = 'active' THEN delta := 1; END IF; r := NEW;
  ELSIF TG_OP = 'UPDATE' THEN
    IF OLD.status = 'active' AND NEW.status <> 'active' THEN delta := -1;
    ELSIF OLD.status <> 'active' AND NEW.status = 'active' THEN delta := 1; END IF;
    r := NEW;
  ELSIF TG_OP = 'DELETE' THEN
    IF OLD.status = 'active' THEN delta := -1; END IF; r := OLD;
  END IF;
  IF delta <> 0 THEN
    UPDATE posts SET comment_count = GREATEST(comment_count + delta, 0) WHERE id = r.post_id;
    IF r.parent_id IS NOT NULL THEN
      UPDATE comments SET reply_count = GREATEST(reply_count + delta, 0) WHERE id = r.parent_id;
    END IF;
  END IF;
  RETURN NULL;
END $$;
CREATE TRIGGER comments_counts AFTER INSERT OR UPDATE OF status OR DELETE ON comments
  FOR EACH ROW EXECUTE FUNCTION trg_comments_counts();

CREATE OR REPLACE FUNCTION trg_post_hashtags_counts() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN
    UPDATE hashtags SET post_count = post_count + 1 WHERE id = NEW.hashtag_id;
  ELSIF TG_OP = 'DELETE' THEN
    UPDATE hashtags SET post_count = GREATEST(post_count - 1, 0) WHERE id = OLD.hashtag_id;
  END IF;
  RETURN NULL;
END $$;
CREATE TRIGGER post_hashtags_counts AFTER INSERT OR DELETE ON post_hashtags
  FOR EACH ROW EXECUTE FUNCTION trg_post_hashtags_counts();
