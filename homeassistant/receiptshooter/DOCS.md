# ReceiptShooter Home Assistant app

## Before installation

This local app runs Java 21 and the packaged React PWA on Home Assistant OS.
It connects to an **existing dedicated** ReceiptShooter MariaDB database; it
does not install MariaDB, create database users, or use Home Assistant's own
tables. The app folder contains an integrated JAR, not the monorepo or secrets.

1. In the Terminal & SSH app, run `ha info` and confirm `arch: amd64` before
   installing or building the image. An Intel CPU alone does not confirm the OS
   architecture. Only `amd64` is currently configured.
2. Back up and restore-test `receiptshooterdb`, as described below. Confirm the
   account configured for ReceiptShooter can connect from another app container.
   The official MariaDB app documents `core-mariadb` as its internal hostname;
   the app's default JDBC URL uses it. A different database host can be used.
3. Check the current Flyway history in the ReceiptShooter database with a
   database client, without sharing credentials or receipt data:

   ```sql
   SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;
   SHOW TABLES LIKE 'users';
   ```

   If V2 is already applied successfully and an administrator exists, the
   existing accounts, receipt ownership and JDBC sessions stay in the same
   database. No bootstrap credentials are needed. If V2 is not applied, the
   first start performs the Milestone 2C migration and the initial administrator
   claims previously unowned receipts. **Stop the old Windows backend before
   this first start** so it cannot write while the schema changes. Set both
   optional initial-administrator fields. The first login must change that
   temporary password. V2 changes the schema even if startup later fails; keep
   the verified pre-migration backup and do not assume an older backend can
   safely run against the migrated schema. Existing image BLOBs are not deleted.

## Build and transfer from Windows

In PowerShell, with Git, JDK 21, Node and npm on the path and `JAVA_HOME` set
to JDK 21, run from the repository root:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\homeassistant\build.ps1
```

The script copies unignored project source into a fresh temporary workspace,
runs `npm ci`, frontend tests and the production build, then Maven `clean
verify`. It checks that the JAR contains the PWA and prints the absolute path
to a new `homeassistant\build\...\local_apps\receiptshooter` folder. The
working temporary source is removed; the running Windows JAR is not touched.
The execution-policy override applies only to this PowerShell process.
The npm lockfile, Maven wrapper, fixed archive timestamp and image digests pin
the build inputs. With the same source and toolchain, compare the printed
SHA-256 values to check whether two JAR builds match.
MariaDB-specific integration tests are intentionally not enabled by this
packaging script. The staged JAR's SHA-256 is in `receiptshooter.jar.sha256`.

Transfer **only** that printed `receiptshooter` folder into the Home Assistant
`local_apps` folder. The result must be `local_apps/receiptshooter/config.yaml`
beside `Dockerfile`, `run.sh` and `receiptshooter.jar`:

- With the official Samba share app, use File Explorer to open
  `\\10.0.0.175\local_apps` and copy the folder there. Use the hostname or IP
  of your Home Assistant server if it differs. Samba must be installed and the
  `local_apps` share available; it is not configured by ReceiptShooter.
- With a configured Terminal & SSH app that supports SFTP, copy the folder
  with `scp -r "C:\path\to\local_apps\receiptshooter" root@10.0.0.175:/local_apps/`
  (substitute the actual printed path and configured SSH username). Verify that
  `/local_apps/receiptshooter` contains the files, not an extra nested folder.

Do not copy `frontend/certs`, `.env`, IntelliJ settings, database dumps or the
repository's private backups. The staging script uses an explicit app-file
allowlist; the `.dockerignore` limits the image build context to the JAR and
startup script. Home Assistant must have internet access to pull the two pinned
public base images during its first build.

## Install and configure

1. In Home Assistant open **Settings → Apps → Install app**, open the store's
   menu and select **Check for updates**, then choose ReceiptShooter in **Local
   apps**. Select **Install**. Supervisor builds the image on the server from
   that folder; no Docker build can read files outside it.
2. In **Configuration**, enter:
   - `db_url`: for the official MariaDB app,
     `jdbc:mariadb://core-mariadb:3306/receiptshooterdb`; substitute the host,
     port or dedicated database if it moves. Do not put credentials in the URL.
   - `db_username` and `db_password`: the existing dedicated database account
     and password. The password field is masked.
   - `java_heap_mb`: `320` by default; allowed 256–1024 MB. The whole process
     can use more than the heap. Start small and monitor memory and uploads.
   - `session_timeout_seconds`: default `2592000` (30 days idle), allowed
     60–7776000. Session cookies remain Secure, HttpOnly and SameSite=Lax.
   - `admin_username` and `admin_initial_password`: leave **both unset** if an
     administrator already exists. For a first 2C bootstrap, enter both as a
     temporary credential; remove them after the account is created.
3. Save. In **Network**, leave container port `8080/tcp` mapped to host `8090`,
   or choose another host port and later update the proxy target accordingly.
   Turn on **Start on boot** and **Watchdog**, then start the app. The app's
   logs are available on its Log tab. The watchdog checks `/api/health` and
   can restart an unhealthy app when enabled.
4. Confirm a healthy response from the LAN, for example in Windows PowerShell:

   ```powershell
   curl.exe -fsS http://10.0.0.175:8090/api/health
   ```

   Expect `{"status":"UP"}`. A plain HTTP health check does not verify login,
   secure cookies, camera access or HTTPS. Keep the HTTP listener accessible
   only to the trusted proxy and administrative hosts; it accepts forwarded
   headers and must not be exposed to the internet. Use the HTTPS hostname for
   the UI and authenticated API.

Home Assistant stores the app options in its private data and passes them in
`/data/options.json`; password fields are masked in the UI but are not a secret
vault. Protect Home Assistant administrator access and app backups. The startup
script reads options without logging them, uses a fresh container-local `/tmp`
directory and replaces itself with Java so shutdown signals reach the JVM.
The Supervisor's default init reaps children. No permanent photos are stored in
the container. Flyway and the JDBC session store use the configured database.

## Manual cutover and verification

Do not change DNS or the existing certificate. If V2 migration is needed, stop
the Windows backend before the first Home Assistant app start, as above. The
Windows backend may remain running during initial checks only if V2 is already
applied and that backend is compatible with the 2C ownership schema. If its
version or compatibility is uncertain, stop it before starting the new app.
After the app starts and its logs and health are satisfactory:

1. Stop the old Spring Boot backend on Windows, without removing its files.
2. Confirm `http://10.0.0.175:8090/api/health` still returns UP. From the
   Raspberry Pi, run `curl -fsS http://10.0.0.175:8090/api/health`.
3. In Nginx Proxy Manager, manually change the existing `receipts.home` proxy
   host to scheme `http`, forward IP `10.0.0.175`, port `8090`; retain the
   existing TLS certificate and DNS entry. No separate `/api` location is needed.
4. Visit `https://receipts.home/api/health`, then open the existing installed
   iPhone PWA. Sign in if necessary and test a manual single-photo upload and a
   multi-photo upload. Check the receipts and photo order, then restart the app
   and confirm the login persists. Confirm the session cookie is Secure and
   HttpOnly and that the PWA can capture offline.
5. Switch off the Windows computer and repeat the health and PWA checks.

The origin, manifest scope, service worker and IndexedDB schema have not been
changed. Existing offline receipts remain on the device; do not reinstall or
clear PWA storage. A first 2C migration from the old bearer-token backend
requires a username/password login; existing browser sessions cannot be reused.

## Updates and recovery

Back up the database first. Build a new bundle and review it, then increment
the version in `homeassistant/receiptshooter/config.yaml` for a normal update.
Copy the new files over the existing local-app folder, check for updates in the
app store, and use **Rebuild** on the app page (or
`ha apps rebuild --force local_receiptshooter` in Terminal & SSH). Restart and
check logs and health. Supervisor retains app options across a rebuild; keep a
protected copy of the configuration and backup because uninstalling the app can
remove its data/options. The image and local-app folder do not contain the DB.

If deployment fails, keep the proxy pointed at the previous working backend.
Check the app Log tab for missing options, database authentication, Flyway or
MariaDB packet-limit failures. Make sure the database account is allowed to
connect from the app container and `max_allowed_packet` accommodates a maximum
photo plus headroom. A `401` on protected `/api` requests is expected without a
session; an HTTP-only browser cannot use Secure session cookies. Confirm the
proxy forwards HTTPS headers and that the URL remains `https://receipts.home`.
If the database has been migrated, do not simply start an incompatible old
backend: use a compatible release or restore the verified pre-migration backup
to a separate database and explicitly point the old backend at that copy.

## Database backups and resource usage

An app backup does **not** by itself guarantee a complete backup of the
separate MariaDB app or its ReceiptShooter database. Back up the dedicated
database, containing photos, users, receipt metadata and sessions, separately.
For example, from a trusted machine with the MariaDB client and a backup account
with the needed read privileges, run this while no schema migration is in
progress (the password is prompted, not placed on the command line):

```sh
mariadb-dump --single-transaction --quick --hex-blob --routines --triggers --events \
  -h DB_HOST -u BACKUP_USER -p receiptshooterdb > receiptshooterdb-backup.sql
```

Protect and encrypt the resulting file and store a copy away from the Home
Assistant host. A dump with BLOBs can be large. Restore-test into a separately
created **empty** database using a permitted account:

```sh
mariadb -h DB_HOST -u RESTORE_USER -p receiptshooterdb_restore < receiptshooterdb-backup.sql
```

Verify photos, accounts and login against the isolated copy before relying on
the backup. Restoring over an existing database is destructive: stop all
ReceiptShooter backends and follow a deliberate recovery plan instead. Never
restore over Home Assistant's own database. Keep database credentials and
backups out of Git and the local-app build context.

Monitor host memory and disk in Home Assistant's system and hardware views and
the app logs. In a suitable MariaDB client, table and BLOB growth can be tracked
without printing receipt data:

```sql
SELECT ROUND(SUM(data_length + index_length) / 1024 / 1024, 1) AS size_mb
FROM information_schema.tables WHERE table_schema = 'receiptshooterdb';
```

Also monitor MariaDB binary logs, backups and container images; the shared SSD
can fill even when the ReceiptShooter database itself is modest. The 320 MB
heap is not a hard cap on native memory, photo buffers, MariaDB or the OS.

Home Assistant references: [local app testing](https://contributors.home-assistant.io/apps/guides/testing/),
[app options](https://contributors.home-assistant.io/apps/guides/options/),
and [the official MariaDB app](https://github.com/home-assistant/apps/blob/master/mariadb/DOCS.md).
