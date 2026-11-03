import { Errors } from './errors.js';

// Keyset cursor over (created_at DESC, id DESC).
export type TimeCursor = { t: string; id: string };

export function encodeCursor(c: object): string {
  return Buffer.from(JSON.stringify(c)).toString('base64url');
}

export function decodeTimeCursor(s?: string): TimeCursor | null {
  if (!s) return null;
  try {
    const c = JSON.parse(Buffer.from(s, 'base64url').toString('utf8'));
    // t is Postgres' own text form of the timestamp (microsecond precision preserved).
    if (typeof c.t !== 'string' || typeof c.id !== 'string') throw new Error();
    if (!/^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}(\.\d{1,6})?([+-]\d{2}(:?\d{2})?|Z)?$/.test(c.t)) throw new Error();
    if (!/^[0-9a-f-]{36}$/i.test(c.id)) throw new Error();
    return c;
  } catch {
    throw Errors.validation([{ path: ['cursor'], message: 'Invalid cursor' }]);
  }
}

export function decodeOffsetCursor(s?: string): number {
  if (!s) return 0;
  try {
    const c = JSON.parse(Buffer.from(s, 'base64url').toString('utf8'));
    if (!Number.isInteger(c.o) || c.o < 0 || c.o > 10000) throw new Error();
    return c.o;
  } catch {
    throw Errors.validation([{ path: ['cursor'], message: 'Invalid cursor' }]);
  }
}

/** Given rows fetched with limit+1, return the page and the next cursor. */
/** Rows must include cursor_t (created_at::text) and id. */
export function pageByTime<T extends { cursor_t: string; id: string }>(rows: T[], limit: number) {
  const hasMore = rows.length > limit;
  const items = hasMore ? rows.slice(0, limit) : rows;
  const last = items[items.length - 1];
  return {
    items,
    nextCursor: hasMore && last ? encodeCursor({ t: last.cursor_t, id: last.id }) : null,
  };
}
