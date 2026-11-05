# ReceiptShooter project specification

## Purpose and constraints

ReceiptShooter is a private household receipt collection application. Household
members use iPhones. The deployment target is a modest home server, potentially
running Home Assistant OS, so keep resource use and operational complexity low.
The precise deployment and storage arrangement is a later decision; do not
assume this milestone deploys the backend.

## Architecture

- `frontend/`: React, TypeScript and Vite; an installable PWA that uses IndexedDB
  to collect receipts offline.
- `backend/`: Java 21 and Spring Boot with Spring Data JPA, Hibernate, Flyway
  and Spring Security. Receipt metadata and original photo BLOBs are stored in
  MariaDB. Database location and credentials come from environment variables.
  Java packages separate controllers, DTOs, entities, repositories, services,
  configuration, security and exception handling.
- The applications remain independently buildable within one Git repository.
  For integrated releases, build `frontend/dist` first and run a clean Maven
  package; Maven includes the PWA as Spring Boot classpath static resources.
- The PWA and API share an HTTPS origin, such as `https://receipts.example`, with a private
  reverse proxy terminating TLS and forwarding to Spring Boot. API requests use
  relative `/api/...` URLs. Spring accepts forwarded headers from the proxy;
  restrict direct access to the HTTP listener to trusted hosts.
- The installed PWA must be served over trusted HTTPS. Its application shell must
  work offline after installation and initial loading.
- Spring Security authenticates individual users with BCrypt password hashes.
  Spring Session stores sessions in MariaDB and issues persistent Secure,
  HttpOnly, SameSite=Lax cookies. Cookie-authenticated mutations require CSRF
  tokens. The PWA does not persist passwords or authentication tokens.

## Receipt lifecycle

1. A person starts a receipt by deliberately capturing its first photograph with
   the iPhone camera. A receipt represents one transaction and has a stable UUID,
   the first capture's UTC timestamp, a pending status and a photo count. Each
   photograph is an IndexedDB Blob with its own UUID, parent receipt UUID, UTC
   capture timestamp and sequence number. Save the first photo and receipt
   together; subsequent photos are saved as they are added.
2. Receipt images stay on the device until the person chooses Upload. Uploads
   must target only the reachable private home server. Never upload automatically
   or use background synchronization.
3. An explicitly initiated upload sends the receipt and all of its
   ordered photos as one logical transaction, using the single stable receipt UUID
   for idempotency. Local images may be removed only after the backend positively
   confirms durable storage of the entire receipt and all photos. Preserve every
   image on failure or an ambiguous response, and retain minimal receipt metadata
   after successful upload. A pending receipt is locked before the upload starts
   so an interrupted or ambiguous request can be retried identically. To edit a
   locked receipt, the user can create a separate copy with new receipt and photo
   UUIDs; uploading both could create duplicates.
4. A person may confirm deletion of an accidental photo or an entire local
   receipt. A saved receipt must always contain at least one photo; removing its
   last photo requires deleting the whole receipt. This explicit deletion is
   separate from upload cleanup.

The phone must not perform OCR, vendor identification or item categorization.
Future features include server-side OCR, categorization, tax allocation,
transaction matching and Monarch Money integration. None are part of milestone 1.

## Milestone 1: offline frontend collection

Provide an iPhone-friendly New Receipt flow with repeated Add Another Photo and
Done controls. List one entry per receipt, newest first, with capture date/time,
photo count and upload status. Allow viewing photos in sequence, adding photos to
a pending receipt, reordering them with touch-friendly controls and confirmed
deletion. In milestone 1 the Upload button was disabled until backend integration.
Handle database unavailability and storage quota failures without claiming a
photo was saved when it was not; retain an unsaved capture in memory for retry
or deliberate discard while the app remains open. Do not implement APIs,
authentication, automatic uploads or the future features in this milestone.

## Milestone 2A: independent backend API

The bearer-token details in this historical milestone are superseded by 2C.

- Flyway creates `receipts`, `receipt_photos` and `receipt_photo_data` in
  the configured MariaDB database using InnoDB. Receipt UUIDs are unique per
  uploader; photo UUIDs are globally unique and sequence numbers are unique per
  receipt. Photo metadata is separate from the `LONGBLOB` table so list and
  detail queries do not load image data.
- `POST /api/receipts` accepts JSON metadata and named multipart photos.
  JPEG and PNG content is decoded and validated; HEIC/HEIF is currently
  rejected. The original bytes are stored unchanged. Configurable photo,
  aggregate, pixel, count and concurrent-upload limits bound resource use.
  A startup check requires MariaDB's packet limit to accommodate the configured
  photo limit or the largest existing photo, plus headroom.
- Receipt metadata, photo metadata and BLOBs are written in one transaction.
  The server acknowledges only after commit. Identical retries return the same
  acknowledgement; changes to capture metadata, photo UUIDs, order, filenames,
  types, sizes or SHA-256 hashes produce a conflict. A database uniqueness
  constraint resolves simultaneous submissions safely.
- Authenticated endpoints list receipt metadata, return ordered details, and
  return a single photo. Ownership is determined from the authenticated
  principal. `GET /api/health` is public and contains no configuration details.
  Development uses a single environment-configured bearer token and stable
  uploader identifier; it is not yet an individual household account system.
  The backend defaults to localhost HTTP; network use requires trusted HTTPS.
- No PWA upload integration, OCR, categorization, tax allocation, matching or
  Monarch Money features are implemented in this milestone.

## Milestone 2B: same-origin PWA and manual uploads

The bearer-token details in this historical milestone are superseded by 2C.

- Maven packages the Vite production output in the backend JAR. The app shell,
  manifest, icons, service worker and extensionless client routes are public;
  `/api/receipts` and `/api/upload-limits` require bearer authentication, and
  `/api/health` remains public. Missing API and static asset paths are never
  rewritten to the SPA. The backend honors proxy-forwarded HTTPS headers.
- An authenticated limits endpoint exposes the current count, photo-byte,
  total-byte and image-dimension limits. Before upload the PWA checks these limits
  and JPEG/PNG headers; the backend remains responsible for full image validation.
  HEIC/HEIF images remain local and cannot yet be uploaded. Images are never
  recompressed or converted.
- A person explicitly presses Upload for one pending receipt. The client sends
  one JSON `metadata` multipart part and `photo-{UUID}` parts with the existing
  IDs and sequences. Only HTTP 200 or 201 with the matching receipt UUID, photo
  count, STORED status and valid server upload time authorizes cleanup. The
  IndexedDB status update and photo deletion commit atomically; failed requests,
  bad acknowledgements and failed cleanup retain the photos for a manual retry.
- Uploaded entries retain lightweight local metadata; viewing an uploaded photo
  requires the token, network and server. Removing an uploaded entry locally does
  not delete the server copy. The PWA never uploads on launch or reconnect.
- The service worker precaches only the application shell, never API responses or
  receipt photos. The user can activate an available update after finishing any
  capture or upload. An installed PWA must first be loaded online on its final
  HTTPS origin before offline use.

## Milestone 2C: household accounts and persistent sessions

- Flyway V2 creates `users`, a bootstrap lock and Spring Session JDBC tables,
  and adds a receipt-to-user foreign key and user-scoped receipt UUID constraint.
  The original uploader identifier is retained for historical metadata. Existing
  receipt and photo rows and original BLOBs remain untouched; on first bootstrap,
  the first administrator transactionally claims receipts with no user owner.
  A nullable owner supports this one-time migration; application writes always
  assign a user. A conflicting legacy UUID aborts bootstrap rather than deleting data.
- The initial administrator is created once from `ADMIN_USERNAME` and
  `ADMIN_INITIAL_PASSWORD`; subsequent restarts do not require those variables
  or reset an existing administrator. Administrators can create accounts, reset
  temporary passwords and enable or disable users. Temporary passwords, including
  the bootstrap password, must be changed at first sign-in. A transactional lock
  prevents disabling the last active administrator.
- Login and logout use Spring Security and session fixation protection. JDBC
  sessions survive backend restarts and expire after the configured idle period;
  the persistent cookie has the same Max-Age. Account disablement and password
  changes revoke sessions, with an account version checked on each API request.
  Failed login attempts are temporarily throttled per client address.
- `GET /api/auth/csrf` supplies the session-bound CSRF header value. The frontend
  obtains a fresh token before every state-changing request and sends same-origin
  cookies. `/api/admin/users` is restricted to administrators; receipt endpoints
  remain restricted to the owning user. No bearer-token authentication remains.
- Local capture and pending photo access do not depend on sign-in or connectivity.
  Session failures never trigger photo cleanup. The service worker excludes API
  responses and account information from its caches. Existing IndexedDB records
  and the installed PWA remain in place.
- An explicit local-only recovery mode can reset an existing administrator to a
  temporary password, revoke their sessions and exit. Recovery binds only to
  loopback on an ephemeral port and is never an unauthenticated API endpoint.

## Data and reliability

Receipt photographs are private. Keep them in IndexedDB rather than localStorage,
service-worker caches, URLs sent over the network or application logs. Minimize
copies in memory and release temporary image URLs. IndexedDB writes and related
metadata changes must complete atomically before reporting success.

IndexedDB version 2 stores receipt metadata and photos in separate object
stores. Upgrading a version-1 database migrates every old image into the photo
store with a new photo UUID and sequence 1, retaining the original receipt UUID,
timestamp, status and image bytes. The old image store is removed only within
the same successful upgrade transaction. A missing or orphaned legacy image,
write failure, or aborted upgrade must leave the version-1 database intact.
An older app window can block the upgrade; closing other ReceiptShooter windows
and retrying allows it to proceed. Changes to photo counts, sequences and
deletions are atomic with their associated photo writes.

Browser storage is subject to quota, user clearing and possible eviction; it is
not a substitute for a backup. Request persistent storage where available, handle
denial gracefully, and explain the limitation. Capture times are recorded in UTC
and displayed in the device's local time. Safari and a Home Screen app may have
separate storage: users should capture and review receipts in the installed app.

The MariaDB database contains the only server-side copies of images and must be
backed up, protected and regularly restore-tested. Monitor database, binary-log
and backup growth against the home server's limited SSD space. Do not log image
data, receipt identifiers, database credentials or bearer tokens.

## Verification

Run the frontend automated tests and production build after frontend changes.
Tests cover multi-photo persistence across database reopenings, ordering,
deletion safeguards, rollback and version-1 migration.
Exercise the production build offline after it has been loaded online, and test
camera capture and persistence on an actual iPhone over trusted HTTPS. A desktop
browser typically offers a file picker instead of a camera capture interface.
Run the backend Maven tests after backend changes. MariaDB-specific integration
tests are opt-in and require a separate disposable database; report when they
cannot be run.
