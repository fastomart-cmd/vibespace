// Post views: who counts as a viewer, the 24-hour unique-view rule, and basic anti-bot checks.
import { createHmac } from 'node:crypto';
import type { FastifyRequest } from 'fastify';
import type { Config } from '../config.js';
import type { Queryable } from '../db/pool.js';
import { postVisible } from './views.js';

/** A device id is an app-generated random id (UUID or similar); anything else is ignored. */
const DEVICE_ID_RE = /^[A-Za-z0-9_-]{16,64}$/;

/** User agents that are obviously not people. They can read posts but never add views. */
const BOT_UA_RE = /bot\b|bot\/|crawler|spider|slurp|curl\/|wget|python-requests|python-urllib|httpclient|headless|phantomjs|scrapy|go-http-client|facebookexternalhit|preview/i;

export const VIEW_WINDOW_HOURS = 24;

const hmac = (secret: string, s: string) => createHmac('sha256', secret).update(s).digest('hex').slice(0, 40);

/**
 * Identify the viewer. Logged-in users are counted by user id (the same person on two phones is one viewer).
 * Guests are counted by a keyed hash of the X-Device-Id header; without one, by a hash of IP + user agent.
 * Raw device ids and IPs are never stored.
 */
export function viewerKey(req: FastifyRequest, config: Config): string {
  if (req.user) return `u:${req.user.id}`;
  const raw = req.headers['x-device-id'];
  const device = typeof raw === 'string' ? raw.trim() : '';
  if (DEVICE_ID_RE.test(device)) return `d:${hmac(config.JWT_ACCESS_SECRET, `device:${device}`)}`;
  return `i:${hmac(config.JWT_ACCESS_SECRET, `ip:${req.ip}|${req.headers['user-agent'] ?? ''}`)}`;
}

export function isBot(req: FastifyRequest) {
  const ua = req.headers['user-agent'];
  return typeof ua === 'string' && BOT_UA_RE.test(ua);
}

/**
 * Per-IP budget of NEW views per hour (in memory, per server process). Stops one machine from inflating
 * views by rotating device ids. Requests over the budget still succeed; they just stop adding views.
 */
export class IpViewBudget {
  private buckets = new Map<string, { windowStart: number; used: number }>();
  constructor(private maxPerHour: number, private now: () => number = Date.now) {}

  /** How many of `wanted` new views this IP may still add right now. */
  allowance(ip: string, wanted: number) {
    const t = this.now();
    let b = this.buckets.get(ip);
    if (!b || t - b.windowStart >= 3_600_000) {
      b = { windowStart: t, used: 0 };
      this.buckets.set(ip, b);
      if (this.buckets.size > 50_000) this.prune(t);
    }
    return Math.max(0, Math.min(wanted, this.maxPerHour - b.used));
  }

  consume(ip: string, n: number) {
    const b = this.buckets.get(ip);
    if (b) b.used += n;
  }

  private prune(t: number) {
    for (const [k, b] of this.buckets) if (t - b.windowStart >= 3_600_000) this.buckets.delete(k);
  }
}

export interface ViewResult {
  postId: string;
  viewCount: number;
  counted: boolean;
}

/**
 * Record views of the given posts for one viewer, in a single statement:
 *  - only posts the viewer is allowed to see (others are silently left out),
 *  - never the author's own posts,
 *  - at most one view per viewer per post per 24 hours (the post_views row is re-armed after 24h),
 *  - when `countable` is false (bot, IP budget used up) nothing is counted but current counts are returned.
 * Returns one entry per visible post, in no particular order.
 */
export async function recordViews(
  db: Queryable,
  opts: { postIds: string[]; viewerId: string | null; viewerKey: string; maxNew: number },
): Promise<ViewResult[]> {
  const ids = [...new Set(opts.postIds)];
  if (!ids.length) return [];
  const { rows } = await db.query(
    `WITH eligible AS (
       SELECT p.id, p.author_id, p.view_count
         FROM posts p JOIN users a ON a.id = p.author_id
        WHERE p.id = ANY($2::uuid[]) AND ${postVisible(1)}),
     candidates AS (
       SELECT e.id FROM eligible e
        WHERE ($1::uuid IS NULL OR e.author_id <> $1::uuid)
        ORDER BY e.id LIMIT $4),
     ins AS (
       INSERT INTO post_views (post_id, viewer_key, user_id)
       SELECT c.id, $3, $1::uuid FROM candidates c
       ON CONFLICT (post_id, viewer_key) DO UPDATE
          SET last_counted_at = now(), times_counted = post_views.times_counted + 1
        WHERE post_views.last_counted_at <= now() - interval '${VIEW_WINDOW_HOURS} hours'
       RETURNING post_id),
     upd AS (
       UPDATE posts SET view_count = view_count + 1
        WHERE id IN (SELECT post_id FROM ins)
       RETURNING id, view_count)
     SELECT e.id, COALESCE(upd.view_count, e.view_count) AS view_count, (upd.id IS NOT NULL) AS counted
       FROM eligible e LEFT JOIN upd ON upd.id = e.id`,
    [opts.viewerId, ids, opts.viewerKey, Math.max(0, opts.maxNew)],
  );
  return rows.map((r) => ({ postId: r.id, viewCount: Number(r.view_count), counted: r.counted }));
}
