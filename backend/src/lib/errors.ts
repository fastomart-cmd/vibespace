// Consistent error envelope: { error: { code, message, details? }, requestId }
// Messages are localised (English / Hindi) from the Accept-Language header.

const MESSAGES: Record<string, { en: string; hi: string }> = {
  VALIDATION_ERROR: { en: 'Some fields are invalid.', hi: 'कुछ जानकारी सही नहीं है।' },
  UNAUTHENTICATED: { en: 'Please log in to continue.', hi: 'जारी रखने के लिए कृपया लॉग इन करें।' },
  INVALID_CREDENTIALS: { en: 'Email/username or password is incorrect.', hi: 'ईमेल/यूज़रनेम या पासवर्ड गलत है।' },
  INVALID_TOKEN: { en: 'This token is invalid or has expired.', hi: 'यह टोकन अमान्य है या समाप्त हो गया है।' },
  ACCOUNT_SUSPENDED: { en: 'This account has been suspended.', hi: 'यह अकाउंट निलंबित कर दिया गया है।' },
  FORBIDDEN: { en: 'You are not allowed to do this.', hi: 'आपको यह करने की अनुमति नहीं है।' },
  NOT_FOUND: { en: 'Not found.', hi: 'नहीं मिला।' },
  USERNAME_TAKEN: { en: 'That username is already taken.', hi: 'यह यूज़रनेम पहले से लिया जा चुका है।' },
  EMAIL_TAKEN: { en: 'An account with this email already exists.', hi: 'इस ईमेल से पहले से एक अकाउंट मौजूद है।' },
  CONFLICT: { en: 'This conflicts with existing data.', hi: 'यह मौजूदा जानकारी से टकराता है।' },
  BLOCKED: { en: 'You can’t interact with this account.', hi: 'आप इस अकाउंट से इंटरैक्ट नहीं कर सकते।' },
  CANNOT_TARGET_SELF: { en: 'You can’t do this to your own account.', hi: 'आप अपने ही अकाउंट के साथ ऐसा नहीं कर सकते।' },
  PRIVATE_CHANNEL: {
    en: 'This account is private, so it can’t take VibeStars yet.',
    hi: 'यह अकाउंट प्राइवेट है, इसलिए अभी VibeStars नहीं ले सकता।',
  },
  MEDIA_INVALID: { en: 'The uploaded media is missing or invalid.', hi: 'अपलोड किया गया मीडिया मौजूद नहीं है या अमान्य है।' },
  STORAGE_NOT_CONFIGURED: { en: 'Media storage is not configured.', hi: 'मीडिया स्टोरेज सेट नहीं है।' },
  RATE_LIMITED: { en: 'Too many requests. Please slow down.', hi: 'बहुत ज़्यादा अनुरोध। कृपया थोड़ा रुकें।' },
  INTERNAL: { en: 'Something went wrong.', hi: 'कुछ गलत हो गया।' },
};

export class AppError extends Error {
  constructor(
    public statusCode: number,
    public code: string,
    message?: string,
    public details?: unknown,
  ) {
    super(message ?? MESSAGES[code]?.en ?? code);
  }
}

export const Errors = {
  validation: (details?: unknown) => new AppError(400, 'VALIDATION_ERROR', undefined, details),
  unauthenticated: () => new AppError(401, 'UNAUTHENTICATED'),
  invalidCredentials: () => new AppError(401, 'INVALID_CREDENTIALS'),
  invalidToken: () => new AppError(401, 'INVALID_TOKEN'),
  suspended: () => new AppError(403, 'ACCOUNT_SUSPENDED'),
  forbidden: () => new AppError(403, 'FORBIDDEN'),
  notFound: (what = 'Resource') => new AppError(404, 'NOT_FOUND', `${what} not found.`),
  conflict: (code = 'CONFLICT') => new AppError(409, code),
  blocked: () => new AppError(403, 'BLOCKED'),
  self: () => new AppError(400, 'CANNOT_TARGET_SELF'),
};

export function localise(code: string, fallback: string, acceptLanguage?: string): string {
  const m = MESSAGES[code];
  if (!m) return fallback;
  const wantsHindi = /^\s*hi\b/i.test(acceptLanguage ?? '');
  // Keep a custom message (e.g. "Post not found.") in English unless Hindi is requested.
  if (wantsHindi) return m.hi;
  return fallback || m.en;
}
