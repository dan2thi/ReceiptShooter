# ReceiptShooter local app

ReceiptShooter runs the Spring Boot API and the compiled offline React PWA in
one Java 21 container. It uses the existing, dedicated ReceiptShooter MariaDB
database and exposes HTTP to a trusted reverse proxy on host port 8090.

Build the complete local-app folder on Windows with `homeassistant/build.ps1`
from the project repository. Copy the resulting `receiptshooter` folder into
Home Assistant's `local_apps` directory. The image is built by Supervisor on
the Home Assistant host; the build context already contains the integrated JAR.

See [DOCS.md](DOCS.md) for configuration, installation, updates, backups,
cutover and troubleshooting. Use the existing trusted HTTPS hostname for the
PWA; the HTTP port is only for the proxy and administrative health checks.
