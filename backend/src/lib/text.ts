// Hashtags support Hindi (Devanagari) and other scripts: letters, combining marks, digits, underscore.
const HASHTAG_RE = /(?:^|[^\p{L}\p{M}\p{N}_&])#([\p{L}\p{M}\p{N}_]{1,100})/gu;
const MENTION_RE = /(?:^|[^a-zA-Z0-9_.@])@([a-zA-Z0-9_.]{3,30})/g;

export function extractHashtags(text: string): string[] {
  const out = new Set<string>();
  for (const m of text.matchAll(HASHTAG_RE)) {
    const tag = m[1].normalize('NFC').toLowerCase();
    if (/^\d+$/.test(tag)) continue; // "#1" is not a hashtag
    out.add(tag);
    if (out.size >= 30) break;
  }
  return [...out];
}

export function extractMentions(text: string): string[] {
  const out = new Set<string>();
  for (const m of text.matchAll(MENTION_RE)) {
    out.add(m[1].toLowerCase().replace(/\.+$/, ''));
    if (out.size >= 20) break;
  }
  return [...out].filter((u) => u.length >= 3);
}
