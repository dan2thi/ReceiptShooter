# Development instructions

Read [docs/PROJECT_SPEC.md](docs/PROJECT_SPEC.md) before changing ReceiptShooter.
Keep the project specification accurate when requirements or architecture change.

- Preserve the single root Git repository and the independent `backend/` Maven
  and `frontend/` npm builds. Avoid unrelated backend changes during frontend work.
- Design for iPhone, intermittent connectivity, accessibility and modest home
  server resources. Serve the installed PWA over trusted HTTPS.
- Store captured photos as IndexedDB Blobs with a stable UUID, timestamp and
  pending status. Treat transaction completion as the success boundary. Handle
  unavailable storage, quota failures and possible browser eviction honestly.
- Never add automatic receipt uploads or background sync. A future upload must
  be explicitly initiated, target the private home server, be idempotent and
  retain the local image unless durable server storage is positively confirmed.
  Explicit user-confirmed deletion of an accidental capture is allowed.
- Do not implement OCR, vendor identification or categorization on the phone.
  Keep future backend integration, authentication, OCR, categorization, tax
  allocation, transaction matching and Monarch Money out of milestone 1.
- Avoid logging private receipt data. Revoke temporary object URLs and do not
  place receipt photos in service-worker caches.
- Add meaningful tests for storage changes. Run `npm test` and `npm run build`
  in `frontend/` for frontend work, and report any checks that cannot be run.
- Uploads are explicitly initiated over the same HTTPS origin. Authentication
  uses Spring Security, CSRF-protected requests and persistent JDBC sessions;
  never put credentials or backend addresses in frontend source or storage.
- Preserve the users, receipt ownership and server image data through Flyway
  migrations. Administrator-created temporary passwords require a change on
  first login. Account disablement and password changes revoke sessions; never
  allow the last active administrator to be disabled.
- Freeze a receipt's photo IDs, bytes and order before its first upload attempt.
  Retain the originals across failures and ambiguous responses; only remove them
  in the same IndexedDB transaction that records a matching STORED acknowledgement.
  Do not cache API responses or authenticated photos in the service worker.
- Build `frontend/dist` before a clean Maven package so Spring Boot includes the
  production PWA. Preserve secure `/api` routes and SPA/static resource routing.
- Store original server-side photos only in MariaDB LONGBLOBs, separate from
  metadata. Use Flyway and InnoDB; receipt metadata and every photo must commit
  together before acknowledgement. Retries must be idempotent per uploader and
  conflicting receipt or photo UUIDs must not overwrite data.
- Keep the database URL, database credentials and initial administrator
  credentials in environment variables. Never commit or log secrets or receipt data. Require
  authorization for every receipt endpoint; use trusted HTTPS before network use.
- Do not change Home Assistant tables or database users. Use a dedicated,
  disposable database for MariaDB integration tests and never clean a household
  database. Run the backend Maven tests and report skipped MariaDB checks.
