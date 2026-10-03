# Tap Chat (Android)

Chat-only Android app for Paypeico. Admins, agents, charging agents and processors
log in with their existing Paypeico email and password. The app reads and writes the
same `chat_messages` table as the website, so web and phone chats stay in sync.

## How it connects
The app connects directly to MariaDB (payp_admindb on 62.171.158.102) as the
restricted `chatapp` user, which can only read login columns of the four user tables
and read/write `chat_messages`. It cannot see card or check-in data.

The database password is **not** in the code. It is injected at build time from the
GitHub secret `DB_PASS`.

## Build
1. Create a **private** GitHub repository and upload this whole folder (including `.github`).
2. Repository → Settings → Secrets and variables → Actions → New repository secret:
   Name `DB_PASS`, value = the chatapp password.
3. Go to Actions → "Build APK" → it runs on every push (or press "Run workflow").
4. Open the finished run, download **PaypeicoChat-apk**, unzip, install `app-debug.apk`.

New builds are signed with the same key (`app/debug.keystore`), so they install as
updates over the old version.

## Settings (app/src/main/java/com/paypeico/chat/data/Config.kt)
- `LARAVEL_TIMEZONE` must match `timezone` in Paypeico's `config/app.php`.
- `MESSAGE_POLL_MS` / `LIST_POLL_MS` control how often the app checks for new messages.
