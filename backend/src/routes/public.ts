import { z } from 'zod';
import type { FastifyInstance } from 'fastify';
import type { ZodTypeProvider } from 'fastify-type-provider-zod';
import { mapProfile } from '../lib/views.js';
import { requireVisibleUser, viewerRelation } from '../lib/relations.js';

const esc = (s: string) => s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!);

/**
 * Public, shareable profile URL. Guests can open it; private profiles are hidden (404) from guests.
 * Browsers (Accept: text/html) get a small HTML page with Open Graph tags for link previews and an
 * Android deep link; API clients get JSON.
 */
export default async function publicRoutes(app0: FastifyInstance) {
  const app = app0.withTypeProvider<ZodTypeProvider>();
  const { db, storage } = app.deps;

  app.get('/u/:username', {
    schema: { tags: ['users'], summary: 'Public profile URL (guest OK; HTML for browsers, JSON otherwise)', params: z.object({ username: z.string().min(1).max(30) }) },
    onRequest: app.deps.auth.optionalAuth,
  }, async (req, reply) => {
    const viewer = req.user?.id ?? null;
    const t = await requireVisibleUser(db, req.params.username, viewer);
    const profile = { ...mapProfile(t.row, storage), canViewContent: t.canSeeContent, viewer: viewer && !t.isSelf ? viewerRelation(t.row) : null };
    if (!(req.headers.accept ?? '').includes('text/html')) return profile;
    const name = esc(profile.displayName);
    const user = esc(profile.username);
    const desc = esc(profile.bio || `${profile.counts.vibeMates} VibeMates · ${profile.counts.vibeStars} VibeStars on VibeSpace`);
    reply.type('text/html; charset=utf-8');
    return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${name} (@${user}) · VibeSpace</title>
<meta property="og:title" content="${name} (@${user}) on VibeSpace"><meta property="og:description" content="${desc}">
${profile.avatarUrl ? `<meta property="og:image" content="${esc(profile.avatarUrl)}">` : ''}
<meta property="al:android:url" content="vibespace://u/${user}"></head>
<body style="font-family:system-ui;background:#0b0710;color:#eee;text-align:center;padding:3rem">
<h1>${name}</h1><p>@${user}</p><p>${esc(profile.bio)}</p>
<p><b>${profile.counts.vibeMates}</b> VibeMates · <b>${profile.counts.vibeStars}</b> VibeStars · <b>${profile.counts.posts}</b> posts</p>
<p><a style="color:#b18cff" href="vibespace://u/${user}">Open in the VibeSpace app</a></p>
<p style="opacity:.6">Create. Share. Earn.</p></body></html>`;
  });
}
