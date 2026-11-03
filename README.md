# VibeSpace — Create. Share. Earn.

A social platform for Indian users, in Hindi and English. Founder: Pushkar Kumar.

## Plan

| Phase | Scope | Status |
|---|---|---|
| **1. Core backend** | Auth, profiles, VibeMates, VibeStars, blocks and mutes, text and image posts, VibeClaps, VibeTalk, saves, reports, the Following, Trending and Explore feeds, admin and moderation, rate limiting, guest browsing | **Built and tested** (`backend/`) |
| 2. Android app | Kotlin and Jetpack Compose client for the Phase 1 API (guest browsing, then sign-up to act) | **Phase 1 screens built**, debug APK builds (`android/`, see `android/README.md`). Not yet run on a device or against a live server |
| 3. Admin panel | A web UI on the `/api/v1/admin/*` endpoints | Later |
| 4. Growth | VibeShorts and VibeLong video (FFmpeg on the VM), VibeChat, notifications, VibeEarn payouts, VibeMarket ads | Later |

**Hosting (no credit card, default):** see [`docs/DEPLOY_FREE.md`](docs/DEPLOY_FREE.md)
- API: Render free web service (`render.yaml` Blueprint)
- Database: Supabase Postgres (free)
- Media: Supabase Storage (free, 1 GB, max 50 MB per file). Clients upload directly with a presigned URL, and Postgres stores only the metadata.

**Alternative (needs a card):** API on an Oracle Always Free ARM VM with Caddy (`backend/deploy/`), media on Cloudflare R2. Pick with `STORAGE_PROVIDER=r2|s3|supabase`.

## Backend (Phase 1)

**Stack:** Node 22, TypeScript, Fastify 5, and zod. The database is PostgreSQL, set up with plain SQL migrations and a small runner. API docs are in OpenAPI 3 at `/docs`. Logins use JWT access tokens (15 min) with rotating refresh tokens, which are stored as SHA-256 hashes in `sessions`. Passwords are hashed with argon2id. Media goes to Supabase Storage, Cloudflare R2 or any S3-compatible store through the AWS S3 SDK (`STORAGE_PROVIDER`), and the tests use vitest and supertest.

**Key rules:**
- **Guests can browse.** Without an account you can see Trending, Explore, public profiles (`/u/:username`), single posts, comments and replies, and VibeMates/VibeStars counts. Guests can't see private profiles. You need to log in to post, upload, comment, clap, save, VibeMate, VibeStar, block, report, or open the Following feed.
- **The server checks every permission.** Database triggers keep all counts up to date, and the API ignores any counts a client sends.
- **VibeMates (follows) and VibeStars (channel subscriptions) are separate tables**, and each has its own counters.
- **Private accounts:** a VibeMate request stays pending until the owner accepts it. Private accounts can't receive VibeStars.
- **One error format:** `{ error: { code, message, details? }, requestId }`. The message is in Hindi when the request sends `Accept-Language: hi`.
- **Rate limits:**
  - Everything: 300 requests/min per IP
  - Auth endpoints: 10/min per IP
  - Writes: 60/min per user
  - The limit store is in memory, which works for one VM. Switch to Redis if you run more than one API instance.
- **Trending score:** `(claps + 2·comments + 3·saves + 0.5·ln(1 + views) + 1) / (age_hours + 2)^1.5`. It covers public posts from the last 7 days and is computed in SQL. Views count only lightly (1,000 views ≈ 3.5 VibeClaps).
- **Post views** (migration `002_post_views.sql`): every post response has `viewCount`. `POST /api/v1/posts/:id/view` and `POST /api/v1/posts/views` `{ "postIds": [...] }` (max 50) record views for guests and logged-in users. One view per viewer per post per 24 hours; the viewer is the user id, or for guests a keyed hash of the `X-Device-Id` header (fallback: hash of IP + user agent); raw ids are never stored. Authors' own views don't count. Anti-bot: obvious bot user agents never add views, view requests are rate limited per viewer (`RATE_LIMIT_VIEWS_PER_MIN`, default 60 per endpoint), and each IP can add at most `VIEWS_MAX_NEW_PER_IP_PER_HOUR` new views (default 2000, generous because Indian mobile networks share IPs; kept in memory per server process).
- **Explore:** the newest public posts from accounts you don't VibeMate yet, from the last 30 days. Add `?tag=` to filter by hashtag.
- **Password reset email:** this goes through the `Mailer` interface in `src/services/mailer.ts`.
  - With no `RESEND_API_KEY` (the dev default) the email, including the reset link, is printed to the server log.
  - With `RESEND_API_KEY` set it goes through the Resend HTTP API. There is no SMTP mailer on purpose: Render free blocks SMTP ports.
  - To add another provider, implement `send()` and add it to `createMailer()`.

## Run locally

```bash
# Postgres 14+ running locally
createdb vibespace
cd backend
cp .env.example .env            # set JWT_ACCESS_SECRET: openssl rand -base64 48
npm install
npm run migrate:dev             # applies migrations/*.sql
npm run dev                     # http://localhost:3000, docs at /docs
npm run set-role -- <username> admin   # make your account an admin
```

**Tests** run against a real Postgres. Each run drops and re-creates the schema in the test database.

```bash
createdb vibespace_test
TEST_DATABASE_URL=postgres://USER:PASS@localhost:5432/vibespace_test npm test
```

## Deploy free: Render + Supabase (no credit card)

Plain step-by-step guide: [`docs/DEPLOY_FREE.md`](docs/DEPLOY_FREE.md). In short: `render.yaml` at the repo root is a Render Blueprint (free Node web service, `rootDir: backend`, build `npm ci --include=dev && npm run build`, start `npm start`, health check `GET /health`). Migrations run at boot (`RUN_MIGRATIONS_ON_START=true`) because Render free has no pre-deploy step or shell; they are idempotent (checksummed `schema_migrations`, one transaction per file) and guarded by a Postgres advisory lock, so two instances booting at once don't race. The app binds to Render's `PORT` on `0.0.0.0`.

**Storage settings for Supabase:** `STORAGE_PROVIDER=supabase`, `SUPABASE_PROJECT_REF`, `SUPABASE_STORAGE_BUCKET` (public), and either
- `SUPABASE_UPLOAD_MODE=s3` (default): `SUPABASE_S3_REGION`, `SUPABASE_S3_ACCESS_KEY_ID`, `SUPABASE_S3_SECRET_ACCESS_KEY`. The API presigns a PUT to `https://<ref>.storage.supabase.co/storage/v1/s3`. Supabase documents SigV4 query-string presigning as supported once the S3 connection is enabled in Storage settings.
- `SUPABASE_UPLOAD_MODE=signed-url` (fallback): `SUPABASE_SERVICE_ROLE_KEY`. The API calls Supabase's signed upload URL endpoint (what `createSignedUploadUrl` uses). The app still does one `PUT` with `Content-Type` to `upload.url`, so the response shape and the Android app are unchanged.

Public media URLs are `https://<ref>.supabase.co/storage/v1/object/public/<bucket>/<key>`. Uploads over 50 MB are rejected (Supabase free plan file limit).

**Free-tier limits to know:**
- **Render free sleeps after 15 minutes without traffic** and takes about a minute to wake up on the next request. To keep it awake for free, create a job on [cron-job.org](https://cron-job.org) that calls `https://<service>.onrender.com/health` every 14 minutes. One always-on service uses about 744 hours in a 31-day month, inside Render's 750 free instance-hours per month (don't keep a second free service awake the same way).
- **Supabase free pauses a project after 1 week of inactivity.** You can restore it from the dashboard. The `/health` ping runs a database query, which probably counts as activity, but that isn't verified; log in to Supabase now and then.

## Deploy (alternative, needs a card): Oracle Always Free VM + Supabase + R2

1. **Supabase:** create a project, then go to Project Settings → Database → Connection string and copy the **Session pooler** URI on port 5432. That one works over IPv4, while the direct `db.<ref>.supabase.co` host is IPv6-only. Set `DATABASE_URL` to it and `DATABASE_SSL=true`.
2. **R2:** create a bucket and an API token with Object Read & Write access. Set `R2_ACCOUNT_ID`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY` and `R2_BUCKET`.
   - Connect a custom domain (or turn on r2.dev) and set `MEDIA_PUBLIC_BASE_URL` to it.
   - Add a CORS rule on the bucket that allows `PUT` with the `Content-Type` header.
   - Set `MEDIA_VERIFY_UPLOADS=true`.
3. **VM** (Ubuntu 22.04/24.04, Ampere A1):
   ```bash
   # open ports 80/443 in the VCN security list AND in the VM firewall:
   sudo iptables -I INPUT 6 -p tcp -m multiport --dports 80,443 -j ACCEPT && sudo netfilter-persistent save
   curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash - && sudo apt install -y nodejs caddy git
   sudo useradd -r -m -d /opt/vibespace vibespace
   sudo -u vibespace git clone <your-repo> /opt/vibespace     # backend/ lives inside
   cd /opt/vibespace/backend && sudo -u vibespace cp .env.example .env   # fill in real values, NODE_ENV=production, TRUST_PROXY=true
   sudo -u vibespace ./deploy/deploy.sh   # first run: npm ci, build, migrate (systemd restart will fail until step 4)
   sudo cp deploy/vibespace-api.service /etc/systemd/system/ && sudo systemctl daemon-reload && sudo systemctl enable --now vibespace-api
   sudo cp deploy/Caddyfile /etc/caddy/Caddyfile   # set your domain, then:
   sudo systemctl reload caddy
   ```
4. **Later deploys:** `git pull && ./deploy/deploy.sh`

## Verified vs. unverified (as of 2 Nov 2026)

- **Verified in the sandbox:** 66 of 66 integration tests pass against real PostgreSQL 17, on two runs. The suite covers all the endpoints and permission rules above. The compiled build (`node dist/server.js`) starts, and `/health` and `/docs/json` respond.
- **Verified (no-card update):** 76 of 76 tests (the 66 integration tests plus 10 for storage config, Supabase presigning, the 50 MB cap and mailer selection). `node dist/server.js` with `RUN_MIGRATIONS_ON_START=true` migrates a fresh database at boot; two servers started at once applied the migration exactly once; a restart reports "up to date"; a bad DB URL makes it exit with code 1.
- **Not verified, because no credentials were available:**
  - Real uploads to Supabase Storage (S3 presigned PUT or signed upload URL), HEAD and delete. URLs are checked for format only.
  - Render: the Blueprint, build and boot on Render itself.
  - Real uploads to R2 and the HEAD check. The presigned URLs are generated with test credentials and checked for format only.
  - Sending email through Resend.
  - Connecting to Supabase. The SQL is standard Postgres 13+ (`gen_random_uuid`, `pgcrypto`).
  - The Oracle VM, systemd and Caddy setup.
