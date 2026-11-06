# ReceiptShooter

Two independently buildable applications share this root Git repository:

~~~text
backend/    Java 21 + Spring Boot API, Maven wrapper, Flyway and MariaDB
frontend/   React + TypeScript + Vite offline PWA
~~~

The PWA captures multiple photos per receipt offline and uploads a receipt only
when the user presses Upload. Spring Boot serves the production PWA and its
authenticated API together; original server-side images are stored in MariaDB. See
[the project specification](docs/PROJECT_SPEC.md) for architecture and scope.
Examples use `https://receipts.example` as a placeholder; substitute a hostname
and certificate trusted by your devices.

## Home Assistant OS deployment

The optional local-app package serves the same integrated PWA and API from a
Java 21 container and uses the existing dedicated MariaDB database. On Windows,
run `powershell -NoProfile -ExecutionPolicy Bypass -File .\homeassistant\build.ps1`
in PowerShell to build, test and stage the app
folder without replacing the running backend JAR. Copy the reported folder into
Home Assistant's `local_apps` directory and follow the
[installation, configuration, backup and cutover guide](homeassistant/receiptshooter/DOCS.md).
Confirm the server architecture and back up the database before first startup.
The documented HTTPS proxy remains in place; no deployment or cutover is
performed by the build script.

## Prerequisites

- JDK 21 with JAVA_HOME configured; Maven is downloaded by the included wrapper.
- A dedicated MariaDB database and its existing user. The application runs
  Flyway migrations in the database named by DB_URL; it does not create MariaDB
  users or databases and must not use a Home Assistant application's database.
- Node.js 22.22.2+, 24.15.0+, or 26+ and npm for frontend work.
- Internet access for initial Maven and npm dependency downloads.

## Backend configuration and local startup

Backend Java code is grouped under `com.dan.receiptshooter` by responsibility:
`controller`, `dto`, `domain`, `entity`, `repository`, `service`, `config`,
`security`, and `exception`. The account and receipt service interfaces define
the application operations; their implementations and internal transaction and
validation helpers live in `service.impl`. Shared service result types live in
`service.model`. Spring Data repository interfaces and the photo BLOB storage
contract live in `repository`; the JDBC BLOB implementation is in `repository.jdbc`.

Set these environment variables before starting the backend:

| Variable | Purpose |
| --- | --- |
| DB_URL | JDBC URL, for example `jdbc:mariadb://database-host:3306/receiptshooterdb`. Replace `database-host` with your database host. |
| DB_USERNAME | Existing MariaDB username with access to the dedicated database. |
| DB_PASSWORD | Database password supplied through the process environment or a secret manager. |
| ADMIN_USERNAME | First administrator's lowercase username. Required only while creating the first administrator. |
| ADMIN_INITIAL_PASSWORD | First administrator's temporary password. Required only while creating the first administrator. |
| SESSION_TIMEOUT_SECONDS | Session idle timeout and persistent cookie Max-Age; defaults to 2592000 (30 days), allowed range 60–7776000. |
| SERVER_ADDRESS | Bind address; defaults to `127.0.0.1`. Use `0.0.0.0` for the existing private reverse proxy. |

Usernames contain 1–64 lowercase letters, digits, dots, underscores, `@` or
hyphens, starting with a letter or digit. Passwords need at least 12 characters
and at most 72 UTF-8 bytes. The bootstrap password is temporary and must be
changed after first sign-in. Once any administrator exists, startup ignores the
bootstrap values and never resets that account. Remove the initial password from
local run settings after successful creation; it is not needed for restarts.

Before the first 2C startup, back up the dedicated database and test the backup.
Flyway V2 preserves the receipt rows and photo BLOBs, creates users and session
tables, and adds a receipt owner foreign key. The first administrator claims all
previously unassigned receipts from the former shared uploader in the same
transaction as account creation. New receipts are owned by the signed-in user.
No database reset or PWA reinstall is required. If legacy receipts contain the
same receipt UUID under multiple old uploader identifiers, bootstrap fails rather
than deleting data; investigate those conflicts before retrying. MariaDB DDL may
not roll back a failed migration, so inspect the dedicated schema and backup
before manually repairing Flyway history or tables.

For example, from `backend/` in Windows PowerShell after building the JAR:

~~~powershell
$env:DB_URL = 'jdbc:mariadb://database-host:3306/receiptshooterdb'
$env:DB_USERNAME = Read-Host 'Database username'
$env:DB_PASSWORD = [System.Net.NetworkCredential]::new('', (Read-Host 'Database password' -AsSecureString)).Password
$env:ADMIN_USERNAME = Read-Host 'Initial administrator username'
$env:ADMIN_INITIAL_PASSWORD = [System.Net.NetworkCredential]::new('', (Read-Host 'Initial administrator temporary password' -AsSecureString)).Password
$env:SERVER_ADDRESS = '0.0.0.0'
java -jar .\target\receiptshooter-0.0.1-SNAPSHOT.jar
~~~

The initial administrator signs in at your HTTPS hostname, changes the
temporary password, then signs in again. Use **Settings → Manage Users** to create
additional ADMIN or USER accounts, reset their temporary passwords and enable or
disable them. The last active administrator cannot be disabled. Users can change
their own passwords. Password changes and disablement revoke affected sessions.
There is no public registration or unauthenticated password-reset endpoint.

To recover an existing administrator locally, configure the DB variables in a
private PowerShell session, then run the following from `backend/`:

~~~powershell
$env:ACCOUNT_RECOVERY = 'true'
$env:RECOVERY_USERNAME = Read-Host 'Existing administrator username'
$env:RECOVERY_TEMP_PASSWORD = [System.Net.NetworkCredential]::new('', (Read-Host 'Recovery temporary password' -AsSecureString)).Password
java -jar .\target\receiptshooter-0.0.1-SNAPSHOT.jar --server.address=127.0.0.1 --server.port=0
Remove-Item Env:ACCOUNT_RECOVERY, Env:RECOVERY_USERNAME, Env:RECOVERY_TEMP_PASSWORD
~~~

The recovery process binds to loopback on an ephemeral port, resets and enables
only an existing administrator, revokes their sessions, and exits. Sign in with
the temporary password and change it. The server refuses recovery mode on any
other bind address or port. Keep credentials and recovery values out of source,
committed files, shell history, screenshots and logs.

The PWA uses a Secure, HttpOnly, SameSite=Lax persistent session cookie; Spring
Session keeps its server-side state in MariaDB. Session expiry, logout, a password
change or account disablement requires another sign-in. The server honors HTTPS
forwarded headers from Nginx Proxy Manager. Restrict direct HTTP access to trusted
private hosts, preserve the proxy's forwarded headers and never log cookies or
request bodies. The database example uses the Connector/J default without
connection TLS; keep it on a private network or configure MariaDB TLS separately.
### Limits and MariaDB packets

| Variable | Default | Meaning |
| --- | ---: | --- |
| MAX_PHOTO_BYTES | 12582912 | 12 MiB per original photo. |
| MAX_RECEIPT_BYTES | 50331648 | 48 MiB total photo bytes per receipt. |
| MAX_REQUEST_BYTES | 52428800 | 50 MiB multipart request including metadata and boundaries. |
| MAX_PHOTOS | 20 | Maximum photos per receipt. |
| MAX_IMAGE_PIXELS | 80000000 | Maximum decoded dimensions, in pixels, per photo. |
| MAX_CONCURRENT_UPLOADS | 2 | Simultaneous uploads accepted; excess requests receive 503. |

Use integer byte counts. MAX_REQUEST_BYTES must exceed MAX_RECEIPT_BYTES by at
least 1 MiB; MAX_RECEIPT_BYTES must be at least MAX_PHOTO_BYTES. Check
`SELECT @@max_allowed_packet;` on MariaDB: the server's value must be at least
the larger of MAX_PHOTO_BYTES or the largest already-stored photo, plus 1 MiB,
or the application refuses to start. Increase the
server setting only through the database administrator, or lower the photo
limit. See the [MariaDB packet-size documentation](https://mariadb.com/docs/server/server-management/variables-and-modes/server-system-variables#max_allowed_packet).
The application uses MariaDB binary prepared statements and allows the driver
to retrieve photos up to the maximum supported photo size plus headroom, even
if the current upload limit has been lowered. Do not override those settings
with conflicting JDBC URL parameters. Multipart data is buffered in memory up to the
configured file limit, so raise limits or concurrency only after considering
available RAM.

### Schema and API

Flyway maintains `users`, `receipts`, `receipt_photos`, `receipt_photo_data`,
`account_bootstrap_lock`, `SPRING_SESSION` and `SPRING_SESSION_ATTRIBUTES`.
Each receipt has a user foreign key and a receipt UUID unique for that user.
Photo UUIDs are globally unique; original bytes remain in MariaDB LONGBLOBs.
The `uploader_id` column retains the historical identifier for old receipts.
Back up the dedicated database, including Flyway history and session tables.

`GET /api/health` is public and returns `{"status":"UP"}`. The PWA uses
same-origin session cookies. `GET /api/auth/csrf` returns a session-bound JSON
`headerName` and `token`; send that header on every POST, PUT, PATCH or DELETE.
Fetch a fresh CSRF token after login or logout. The PWA does this automatically.
All responses containing account or receipt data are excluded from service-worker
caches. A secure cookie is sent only over trusted HTTPS, including behind the
existing proxy. Failed logins are throttled per client address.

| Method and path | Result |
| --- | --- |
| GET /api/auth/csrf | Obtain a session-bound CSRF header name and value. |
| POST /api/auth/login | Form-encoded `username` and `password`; returns 204 on success. |
| POST /api/auth/logout | Invalidate the current session; returns 204. |
| GET /api/auth/session | Current username, role and temporary-password status. |
| POST /api/auth/password | Change the current user's password; revokes existing sessions. |
| GET /api/admin/users | List users and status; ADMIN only, no password hashes. |
| POST /api/admin/users | Create an ADMIN or USER with a temporary password; ADMIN only. |
| PUT /api/admin/users/{id}/enabled | Enable or disable an account; ADMIN only. |
| POST /api/admin/users/{id}/password | Reset a temporary password; ADMIN only. |
| POST /api/receipts | Atomically store a receipt and all photos; 201 new, 200 identical retry. |
| GET /api/receipts | Receipt summaries owned by the signed-in user. |
| GET /api/receipts/{receiptId} | Owned receipt details and ordered photo metadata. |
| GET /api/receipts/{receiptId}/photos/{photoId} | Original image bytes for an owned photo. |
| GET /api/upload-limits | Current authenticated upload limits. |

An upload is multipart/form-data with one `metadata` part of type
application/json and one file part named `photo-{photo UUID}` per photo:

~~~json
{
  "receiptId": "11111111-1111-4111-8111-111111111111",
  "capturedAt": "2020-01-02T03:04:05Z",
  "photos": [{
    "id": "22222222-2222-4222-8222-222222222222",
    "sequence": 1,
    "capturedAt": "2020-01-02T03:04:05Z",
    "contentType": "image/jpeg",
    "size": 12345
  }]
}
~~~

The backend validates JPEG/PNG contents, timestamps, limits and metadata before
storing unchanged bytes. HEIC/HEIF is not currently supported. A committed upload
returns the receipt ID, upload timestamp, photo count and `STORED` status. The
same user can retry identical data with the same IDs; conflicts return 409.

To test authentication, open your HTTPS hostname, sign in, change the initial
password, and verify a manually uploaded test receipt. Open the same URL after
closing and reopening the PWA; the session should remain. Logout must return to
the sign-in form. A request without a valid session to `/api/receipts` returns
401; state-changing requests without CSRF protection return 403. Test cross-user
access using separate disposable accounts and receipts. Do not paste credentials
or session cookies into URLs, logs, screenshots or shared shell history.
### IntelliJ and verification

In IntelliJ, use **View → Tool Windows → Database**, add a **MariaDB** data
source, set your database host, port `3306`, database `receiptshooterdb`
and the existing username. Enter the password in the IDE's secure credential
storage, install its driver if prompted, and use **Test Connection**. Run
`SELECT DATABASE();` and `SELECT @@max_allowed_packet;` to verify the selected
database and packet limit. This connection check does not create tables or users.

Open `backend/pom.xml` as a Maven project, select JDK 21, and create a Spring
Boot run configuration for ReceiptShooterApplication. Supply the environment
variables above through a secure local environment inherited by the IDE or its
run configuration. IDE run configurations may be saved locally in plaintext;
do not share or commit them. Start the application and check
`http://127.0.0.1:8080/api/health`.

The wrapper uses Maven 3.9.16 because IntelliJ IDEA 2026.1's Maven importer
fails with Maven 3.10.0. In IntelliJ, use the Maven wrapper and reload
`backend/pom.xml` if dependencies do not appear.

From `backend/`, run `./mvnw test` and `./mvnw package`, or use
`.\mvnw.cmd test` and `.\mvnw.cmd package` in PowerShell. Normal tests use
an isolated H2 database and include API, rollback and concurrent retry checks.
MariaDB-specific tests are skipped unless MARIADB_TEST_URL,
MARIADB_TEST_USERNAME and MARIADB_TEST_PASSWORD are set. Point those variables
only to a **separate disposable MariaDB database**, then run:

~~~powershell
.\mvnw.cmd -Dtest=MariaDbIntegrationTest test
~~~

The MariaDB tests run Flyway, create uniquely identified test receipts and
remove only those receipts. They verify LONGBLOB/InnoDB, original bytes,
concurrent retries and rollback. Never point them at the household database.

The migration and session-restart checks can also run against MariaDB. Supply
fresh, empty, separate databases through `MARIADB_MIGRATION_TEST_URL` and
`MARIADB_SESSION_TEST_URL`; their database names must end in `_migration_test`
and `_session_test` respectively. They use the same test username and password.
Run `./mvnw -Dtest=MariaDbMigrationPreservationTest,MariaDbSessionRestartTest test`
and provision fresh databases before repeating those checks.

### Backup, restore and storage growth

The database contains the server-side photo originals. Back up the dedicated
database and Flyway history together, encrypt and protect backup copies, keep
at least one copy off the home server, and regularly test a restore into a
separate database. For example, with an appropriately privileged backup user,
a trusted destination and the MariaDB client, a logical backup can use:

~~~sh
DB_HOST='database-host'
mariadb-dump -h "$DB_HOST" -u backup_user --password \
  --single-transaction --quick --hex-blob \
  --result-file=/secure/receiptshooter-backup.sql receiptshooterdb
~~~

The client prompts for the password. Do not run schema changes during the
consistent dump. Restore only into an explicitly chosen separate test database
first, then verify receipt counts and image hashes; follow MariaDB's
[backup](https://mariadb.com/docs/server/clients-and-utilities/backup-restore-and-import-clients/mariadb-dump)
and [restore](https://mariadb.com/docs/server/mariadb-quickstart-guides/mariadb-restore-guide)
instructions for the installed server and client versions.

Monitor database size, InnoDB overhead, binary logs, backup size and free SSD
space. Hexadecimal logical dumps can be much larger than the original photos;
the home server disk must also accommodate Home Assistant and other data.

A pre-2C local snapshot may be kept under the ignored `private-backups/`
directory as a Windows DPAPI `CurrentUser`-encrypted SQL dump. Only that Windows
account can decrypt it; it is not a substitute for a protected off-device backup.
To restore one without writing a plaintext dump to disk, use the same Windows
account and a MariaDB client connected to an explicitly chosen **empty test
database**. For example, adjust the backup and client paths and the test database
connection for your environment:

~~~powershell
Add-Type -AssemblyName System.Security
$backupFile = 'C:\path\to\receiptshooterdb-pre-2c.sql.dpapi'
$plain = [Security.Cryptography.ProtectedData]::Unprotect([IO.File]::ReadAllBytes($backupFile), $null, [Security.Cryptography.DataProtectionScope]::CurrentUser)
$env:MYSQL_PWD = [System.Net.NetworkCredential]::new('', (Read-Host 'Test database password' -AsSecureString)).Password
$start = New-Object Diagnostics.ProcessStartInfo
$start.FileName = 'C:\path\to\mariadb.exe'
$start.Arguments = '--host=127.0.0.1 --port=3306 --user=test_user receiptshooter_restore_test'
$start.UseShellExecute = $false
$start.RedirectStandardInput = $true
$client = [Diagnostics.Process]::Start($start)
try {
  $client.StandardInput.BaseStream.Write($plain, 0, $plain.Length)
  $client.StandardInput.Close()
  $client.WaitForExit()
  if ($client.ExitCode -ne 0) { throw 'Restore failed.' }
} finally {
  [Array]::Clear($plain, 0, $plain.Length)
  Remove-Item Env:MYSQL_PWD
}
~~~

The dump contains `DROP TABLE` statements for its destination. Never restore
directly over the household database without first verifying the restore and
following your recovery plan.

## Integrated build and startup on Windows

The deployed application uses one Spring Boot process. Build the frontend first;
Maven copies `frontend/dist/` into `BOOT-INF/classes/static/` in the executable
JAR. The generated files and `backend/target/` are ignored by Git. A clean Maven
build prevents obsolete hashed assets from remaining in the JAR.

From the repository root in PowerShell:

~~~powershell
Set-Location frontend
npm ci
npm test
npm run build
Set-Location ..\backend
.\mvnw.cmd clean verify
~~~

This produces `backend/target/receiptshooter-0.0.1-SNAPSHOT.jar`. `verify` runs
the backend tests as well as packaging the JAR. MariaDB integration tests are
skipped unless a separate disposable test database is configured as described
above. To incorporate a frontend change, repeat `npm run build` followed by
`.\mvnw.cmd clean package`, then restart the application. Backend-only work can
still be built separately; a JAR built without `frontend/dist/` has no PWA.

In the same PowerShell session, configure the database and authentication
variables described above, including `SERVER_ADDRESS=0.0.0.0`, and start the JAR
from `backend/`:

~~~powershell
java -jar .\target\receiptshooter-0.0.1-SNAPSHOT.jar
~~~

Use JDK 21. The process must remain running. Spring Boot serves the frontend at
`/` and API at `/api/` on port 8080. Configure your HTTPS reverse proxy to
forward your chosen hostname to the backend host on port 8080. No separate API
location or CORS configuration is needed. The app honors forwarded HTTPS headers.
Keep the listener on the private network and restrict port 8080 to the proxy
host where practical, since forwarded headers must come from a trusted proxy.
If the Windows address changes, the existing proxy target must still resolve to
that computer. No database host or proxy address is compiled into the PWA.

Open your HTTPS hostname and its `/api/health` path; health
should return `{"status":"UP"}`. An unauthenticated request to
the `/api/receipts` path must return 401. If the proxy responds but
the app does not load, verify the backend is running, its bind address and the
Windows firewall. The mkcert certificate and CA must already be trusted by the
phone; do not bypass certificate warnings or expose the HTTP backend publicly.

## Frontend development

For independent Vite development, put a trusted development HTTPS key and
certificate at `frontend/certs/dev-key.pem` and `frontend/certs/dev.pem`, then:

~~~powershell
Set-Location frontend
npm ci
npm run dev
~~~

Vite can be used for UI and offline-storage work. API requests are relative to
the current origin; use the integrated Spring Boot build for real uploads unless
you configure a local development proxy yourself. Service workers are enabled
in production builds, not the Vite development server. Run `npm test` and
`npm run build` for frontend changes. `npm run preview` can preview the PWA
shell locally but does not serve the backend API.

## Manual uploads, retries and privacy

Sign in with a household username and password. A persistent, server-managed
session normally survives closing the PWA and restarting the backend. Passwords
and session credentials are not stored in IndexedDB or localStorage. A valid
session is required for uploads and server photos, but never for offline capture.

Tap **Upload** for one pending receipt. The app retrieves the authenticated
server limits, checks JPEG/PNG headers, file sizes, photo count and dimensions,
then sends one multipart receipt using its existing UUIDs and order. JPEG and PNG
are supported without conversion. HEIC/HEIF remains stored locally but cannot be
uploaded by this backend; on an iPhone, **Settings → Camera → Formats → Most
Compatible** can help create JPEG captures. The server performs full validation.

Uploads never run automatically. During an upload the controls are disabled.
If a request or response is interrupted, the photos stay on the phone and the
receipt is locked so **Retry** sends precisely the same data. A response may be
lost after the server commits; an identical retry returns the original success
acknowledgement without a duplicate. To change a locked receipt, choose **Copy to
edit**; it creates new IDs and retains the original, so uploading both could
produce two server receipts. Use deliberate local deletion only when appropriate.

Only after a matching `STORED` acknowledgement does one IndexedDB transaction
mark the receipt Uploaded and remove its local photo BLOBs. If that transaction
fails, retry; the photos remain. Uploaded list metadata persists, while viewing
its photos requires the server, network and a valid session. Deleting an uploaded
entry from the device does not delete its server copy. Back up MariaDB because it
contains the server originals.

Pending receipts belong to the device until they are uploaded, so people sharing
an installation can see its local pending photos. Upload a pending receipt while
signed in to the account that should own the server copy. If a session expires,
sign in again and retry; authentication failures never remove the local photos.

## Install and verify on iPhone

1. On the Windows computer, build and start the integrated application as above.
   Connect the iPhone to the private network. Open the existing ReceiptShooter
   Home Screen app online and choose **Update app** if offered. Do not remove or
   reinstall it: its pending photos live in its existing IndexedDB storage.
2. On a new device, visit your HTTPS hostname in Safari, confirm the
   certificate is trusted, then use **Share → Add to Home Screen**. Open the
   installed app online and reopen it once. Capture and review inside the
   installed app: Safari and Home Screen storage can be separate.
3. Tap **New Receipt**, capture a test page, use **Add Another Photo**, reorder if
   desired and tap **Done**. Close and reopen the app and verify both photos.
4. Sign in with a household account and tap **Upload**. Check that the receipt
   becomes **Uploaded**, and use **View** to load each original from the server.
   Close and reopen the app to check that sign-in persists. Log out and verify a
   separate pending test receipt stays on the device until another sign-in.
5. Enable Airplane Mode, reopen the installed app and capture another receipt.
   Its photos must remain available after reopening. Restore Wi-Fi: it must stay
   pending until Upload is tapped. With the server unavailable an attempted
   upload must leave all photos on the device for retry.

The production service worker precaches the app shell and excludes `/api` from
navigation fallback. It does not cache API responses or receipt photos. When an
update is available, finish any capture or upload and choose **Update app**; the
new worker can then replace the old one. Do not clear site data or remove the
Home Screen app while pending photos need to be retained. Browser storage may be
cleared or evicted despite a persistent-storage request, so retain paper receipts
until server storage is confirmed and backed up.

The prior development installation used a different origin; browser storage is
origin-specific. This deployment does not migrate that installation's data.
Future Home Assistant hosting can run the same JAR with Java 21, its own
configuration and a trusted same-origin HTTPS proxy; packaging and deployment to
Home Assistant are outside this milestone.
