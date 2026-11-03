# VibeSpace Android (Phase 2 · app Phase 1)

**Create. Share. Earn.** · Made in India 🇮🇳

Kotlin + Jetpack Compose (Material 3) client for the VibeSpace Phase 1 API in `../backend`.

| | |
|---|---|
| Language / UI | Kotlin 2.4, Jetpack Compose, Material 3, dark theme only (black / deep purple, neon purple `#B05CFF`, cyan `#00E5FF`) |
| Architecture | MVVM: Compose screens → ViewModels (StateFlow) → repositories → Retrofit `VibeApi` |
| Networking | Retrofit 3 + OkHttp 5 + kotlinx.serialization; Coil 3 for images |
| Auth | JWT access token + rotating refresh token, stored in DataStore; one shared refresh on 401 (`TokenRefresher`) |
| DI | Manual (`di/AppContainer.kt`): no annotation processing, faster builds |
| SDK | minSdk 24, compileSdk / targetSdk 37, AGP 9.5, Gradle 9.8.2, JDK 17 |
| Languages | English (`res/values`) and Hindi (`res/values-hi`), switchable in Settings |

## Screens

- **Home / VibeFeed**: tabs *For You* (trending for now), *Following* (logged-in only), *Trending*, *Explore* (with hashtag search). Pull to refresh, infinite scroll (cursor pagination), loading / error / empty states.
- **Explore** (bottom nav): Explore feed + hashtag search (`#holi` or `holi`). Tapping a hashtag on any post opens it here.
- **Post detail**: the full post plus **VibeTalk**: pinned comment, top-level comments, nested replies (load on demand, paged), reply / delete own / report.
- **Create (+)**: text post, or image post. The image is picked with the system photo picker, downscaled to JPEG if needed, uploaded **directly to R2** with the presigned URL from `POST /media/uploads`, and then attached with `POST /posts`.
- **Profile**: your own profile and other people's: avatar, bio, VibeMates / Vibing with / VibeStars counts and lists, posts, VibeMate (or request, for private accounts), VibeStar, block / unblock, report, and edit profile (name, bio, avatar upload or remove).
- **Login, Sign up, Forgot password**: forgot password sends the reset email. The reset link `vibespace://reset-password?token=…` opens the app's reset screen; you can also paste the token.
- **Settings**: language (English / हिन्दी), private account toggle, blocked users, logout, app version and server URL.
- **Pulse**: "coming soon" placeholder.
- **Views** (eye icon on every post card and on post detail): Indian number style in Hindi (999, 1.2K, 3.4 लाख, 1.2 करोड़), K / M in English (1.2K, 1.2M), always rounded down. A view is recorded when a post is at least 50% on screen for 1 second (`data/repo/ViewTracker.kt`); ids are batched and sent every 5 s (max 50 per request) or when the screen pauses, and opening post detail records a view at once. Each install has a random device id (`data/device/DeviceId.kt`, own DataStore, survives logout) sent as `X-Device-Id` on every API request so guest views count once per device. The server decides what counts (one per viewer per 24 h, not the author's own).

**Guest-first:** the app opens straight to the feed. Guests can browse Trending, Explore, posts, VibeTalk and public profiles. If a guest taps post, VibeTalk, VibeClap, save, VibeMate, VibeStar or report, a login / sign-up prompt appears. The server also enforces all of this.

**Naming rule:** user-facing text says VibeMates, VibeStars, VibeClaps, VibeTalk and VibeFeed, never "followers" or "subscribers".

Deep links: `vibespace://u/<username>` (from the backend's `/u/:username` share page) and `vibespace://reset-password?token=…`.

## Point the app at your server

The API base URL is baked into `BuildConfig.API_BASE_URL` at build time. Give it **without** `/api/v1`, because the app adds that itself.

| Where you build | How to set it |
|---|---|
| Default | `http://10.0.2.2:3000` (the Android emulator's alias for your PC's `localhost`, where `npm run dev` runs) |
| Command line | `./gradlew assembleDebug -PVIBESPACE_API_BASE_URL=https://api.yourdomain.in` |
| Permanently on your PC | add `VIBESPACE_API_BASE_URL=https://api.yourdomain.in` to `~/.gradle/gradle.properties` (or edit `android/gradle.properties`) |
| Environment | `VIBESPACE_API_BASE_URL=https://api.yourdomain.in ./gradlew assembleDebug` |
| GitHub Actions | repo **Settings → Secrets and variables → Actions → Variables** → add `VIBESPACE_API_BASE_URL`, or type the URL in **Run workflow** |
| Real phone on the same Wi-Fi as your PC | use your PC's LAN IP over HTTPS, or add the IP to `res/xml/network_security_config.xml` (plain HTTP is only allowed for `10.0.2.2` / `localhost`) |

Settings → bottom of the screen shows which server the installed build uses.

## Build

**In the cloud (free):** push the repo to GitHub. The workflow `.github/workflows/android.yml` at the repo root runs the unit tests and builds the debug APK. Download it from the run's **Artifacts** (`VibeSpace-debug-apk`). Use **Actions → Android APK → Run workflow** to build on demand with a custom server URL.

**Locally:** use Android Studio (latest stable, which bundles JDK 17+), or:

```bash
cd android
./gradlew testDebugUnitTest        # unit tests
./gradlew assembleDebug            # → app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

You need the SDK platform `android-37.0` and build-tools 36.0.0. Android Studio's SDK Manager installs them, or run `sdkmanager "platforms;android-37.0" "build-tools;36.0.0"`.

## Code map

```
app/src/main/java/com/vibespace/app/
  data/api/      VibeApi (Retrofit, every Phase 1 route the app uses), DTOs, AppError + error-envelope parsing
  data/auth/     TokenStore (DataStore), AuthHeaderInterceptor (Bearer + Accept-Language), TokenRefresher/TokenAuthenticator
  data/repo/     AuthRepository, PostRepository (feeds, posts, VibeTalk, reports), UserRepository, MediaRepository (presigned upload)
  data/settings/ LanguageManager (AppCompat per-app locales)
  di/            AppContainer (manual DI)
  ui/            theme, components (PostCard, login gate, dialogs, states), feed, post, create, profile, auth, settings, pulse
app/src/test/    ViewModel / repository tests with FakeVibeApi
```

How the API contract is matched (from `backend/src`): base path `/api/v1`; `{ items, nextCursor }` pages with an opaque `cursor`; the error envelope `{ error: { code, message, details? }, requestId }`, where the message comes back in Hindi because the app sends `Accept-Language: hi` when the UI is in Hindi; refresh tokens are single use, so the app runs only one refresh at a time and retries other failed requests with the new token. If a refresh is rejected, the app turns back into a guest app and keeps working: GET requests are retried without a token.
