# Put VibeSpace online for free (no credit card)

What you get:
- **Supabase** (free): the database and the photo storage.
- **Render** (free): the API server, at `https://<service>.onrender.com`.
- **GitHub** (free): holds the code and builds the Android APK.

Takes about 30–45 minutes. Use the same email for all three, e.g. `vibespace.pushkar@gmail.com`.

Keep a notepad open. You will copy 6 things into it: **project ref, database URL, S3 region, S3 access key ID, S3 secret key, Render URL.**

---

## Part 1: Supabase (database + photos)

### 1. Create the project
1. Go to <https://supabase.com> → **Start your project** → sign in with GitHub or your email.
2. Click **New project**.
   - Name: `vibespace`
   - Database password: click **Generate a password**, then **copy it to your notepad**. You need it in step 2.
   - Region: **South Asia (Mumbai)**.
   - Plan: **Free**.
3. Click **Create new project** and wait about 2 minutes.
4. Look at the address bar: `https://supabase.com/dashboard/project/abcdxyz...`. That last part is your **project ref**. Copy it to the notepad.

### 2. Get the database connection string
1. Click the **Connect** button at the top of the project.
2. Pick **Session pooler** (not "Direct", not "Transaction"). The port in the link must be **5432**.
3. Copy the URI. It looks like
   `postgresql://postgres.abcdxyz:[YOUR-PASSWORD]@aws-0-ap-south-1.pooler.supabase.com:5432/postgres`
4. Replace `[YOUR-PASSWORD]` with the password from step 1, brackets included. Save it in the notepad as **database URL**.

> You do not need to create any tables. The server creates them by itself the first time it starts.

### 3. Create the photo bucket (public)
1. Left menu → **Storage** → **New bucket**.
2. Name: `vibespace-media` (exactly this).
3. Turn **Public bucket** ON.
4. Optional: turn on **Restrict file size** and set **50 MB**. That's the free-plan maximum anyway.
5. Click **Create**.

### 4. Get the S3 keys
1. **Storage** → **Settings** (or **S3 Configuration**).
2. Make sure **S3 connection** / "Enable connection via S3 protocol" is **ON**. Save if you changed it.
3. Copy the **Region** (e.g. `ap-south-1`) to the notepad.
4. Under **Access keys** click **New access key** → name it `render` → **Create**.
5. Copy the **Access key ID** and the **Secret access key** to the notepad. The secret is shown **only once**.

> Keep these keys secret. Never paste them into the code or a GitHub file.

---

## Part 2: Put the code on GitHub

1. Go to <https://github.com> → sign up / sign in.
2. Click **+** (top right) → **New repository**. Name: `vibespace`. Choose **Private**. Don't add a README. Click **Create repository**.
3. On your computer, open a terminal in the `vibespace` folder (the one that has `backend`, `android`, `render.yaml`) and run:

   ```bash
   git init
   git add .
   git commit -m "VibeSpace"
   git branch -M main
   git remote add origin https://github.com/<your-username>/vibespace.git
   git push -u origin main
   ```

   When it asks for a password, use a GitHub **personal access token** (GitHub → Settings → Developer settings → Personal access tokens), not your normal password.

   No terminal? You can also use **GitHub Desktop** (free): File → Add local repository → pick the folder → Publish repository.

---

## Part 3: Render (the API server)

### 5. Create the account
1. Go to <https://render.com> → **Get Started** → **GitHub**. Allow Render to see your `vibespace` repository.
2. No card is needed for the free plan.

### 6. Deploy the Blueprint
1. In Render: **New +** → **Blueprint**.
2. Pick the `vibespace` repo. Render finds `render.yaml` by itself.
3. It asks you to fill in some values. Paste from your notepad:

   | Box | What to paste |
   |---|---|
   | `DATABASE_URL` | the database URL (with your password in it) |
   | `SUPABASE_PROJECT_REF` | the project ref |
   | `SUPABASE_S3_REGION` | the S3 region, e.g. `ap-south-1` |
   | `SUPABASE_S3_ACCESS_KEY_ID` | the access key ID |
   | `SUPABASE_S3_SECRET_ACCESS_KEY` | the secret access key |
   | `SUPABASE_SERVICE_ROLE_KEY` | leave empty |
   | `RESEND_API_KEY` | leave empty for now (see "Email" below) |

   `JWT_ACCESS_SECRET` is made for you automatically.
4. Click **Apply** / **Deploy**. The first build takes about 3–5 minutes.
5. When it says **Live**, copy the address at the top, e.g. `https://vibespace-api.onrender.com`. That's your **Render URL**.

### 7. Check it works
Open these in your browser:
- `https://<your-render-url>/health` → should show `{"ok":true}`
- `https://<your-render-url>/docs` → the API documentation page

If it fails, open the service in Render → **Logs**. Common problems:
- `password authentication failed`: the password in `DATABASE_URL` is wrong. Fix it in **Environment** and redeploy.
- The URL has port `6543`: use the **Session pooler** link (port `5432`) instead.

---

## Part 4: Build the Android app for your server

1. In GitHub, open your repo → **Actions** tab → enable workflows if asked.
2. Click **Android APK** → **Run workflow**.
3. In the box, paste your Render URL **without** `/api/v1` at the end, e.g. `https://vibespace-api.onrender.com`.
4. Click **Run workflow**. It takes about 10–15 minutes.
5. Open the finished run → **Artifacts** → download **VibeSpace-debug-apk** → unzip → install the `.apk` on your phone.

Tip: so you don't have to type it every time, add it once under repo **Settings → Secrets and variables → Actions → Variables → New repository variable**: name `VIBESPACE_API_BASE_URL`, value your Render URL.

Check it: in the app open **Settings**. The bottom of the screen shows the server address.

---

## Part 5: Make yourself admin

1. Sign up in the app with your own username.
2. In Supabase → **SQL Editor** → **New query**, run (put in your username):

   ```sql
   UPDATE users SET role = 'admin' WHERE username = 'your_username';
   ```

(Render free has no shell, so use Supabase's SQL editor for this instead of `npm run set-role`.)

---

## Keep it awake (free)

- **Render free sleeps after 15 minutes with no visitors.** The next visitor then waits about 1 minute.
  To stop that: make a free account on <https://cron-job.org> → **Create cronjob** →
  URL `https://<your-render-url>/health`, every **14 minutes** → Save.
  That uses about 744 hours a month, inside Render's 750 free hours. Do this for **one** service only.
- **Supabase free pauses the project after 1 week with no activity.** If that happens, open the Supabase dashboard and click **Restore**. The 14-minute ping also touches the database, which should count as activity (not confirmed), but log in to Supabase once a week to be safe.

## Email (password reset)

Without an email key, password-reset links are **only written to the Render logs** (Render → Logs, search `reset`). To send real emails:
1. Make a free account at <https://resend.com> → **API Keys** → create one.
2. Render → your service → **Environment** → set `RESEND_API_KEY` → Save (it redeploys).
3. Until you own a domain, Resend only sends to your own email address. Later, add your domain in Resend and change `MAIL_FROM`.

(Render free blocks normal SMTP email ports, so email only works through an HTTP service like Resend.)

## If photo upload fails

The app shows "Upload failed (403)" or similar. Try the backup upload mode:
1. Supabase → **Project Settings** → **API Keys** → copy the **service_role** key (secret!).
2. Render → **Environment**: set `SUPABASE_SERVICE_ROLE_KEY` to it and `SUPABASE_UPLOAD_MODE` to `signed-url` → Save.
No app rebuild is needed.

## Free limits to remember
- Supabase: 500 MB database, 1 GB file storage, max **50 MB per file**.
- Render: 512 MB RAM, sleeps when idle (see above).
- Updating: push new code to GitHub (`git add . && git commit -m "update" && git push`) and Render redeploys by itself. Database changes run automatically at start.
