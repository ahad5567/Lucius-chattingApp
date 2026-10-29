# Lucius Desktop (Java)

Two parts, both Java 17+:
- `server/` – Javalin + SQLite + WebSocket chat server. Host this once, publicly.
- `desktop/` – JavaFX app that students install. It talks to your server.

Chat between different people only works if everybody's app points at the same public server.
This server has its own database, separate from the earlier Node.js web version.

## 1. Run the server
Needs JDK 17+ and Maven.
```
cd server
mvn package
java -jar target/lucius-server.jar          # http://localhost:8080
```
Env vars: `PORT` (default 8080), `DB_PATH` (default lucius.db – keep it on persistent disk).

For the public internet, run it on a VPS / Render / Railway / Fly.io behind HTTPS (Caddy or nginx; WebSockets must be allowed).
Your server address becomes e.g. `https://chat.yourdomain.com` (the app switches to wss:// automatically).

## 2. Run the desktop app (development)
```
cd desktop
mvn javafx:run
```
Open `desktop/src/main/java/lucius/App.java` and set `DEFAULT_SERVER` to your public server before building installers.

## 3. Build an installer (run on the OS you are targeting)
```
cd desktop
mvn clean package
jpackage --name Lucius --app-version 1.0 --input target/libs --main-jar lucius-desktop-1.0.jar --main-class lucius.Launcher --add-modules java.base,java.desktop,java.net.http,java.prefs,java.logging,java.xml,jdk.unsupported --type exe --win-menu --win-shortcut
```
Use `--type dmg` on macOS, `--type deb` (or `rpm`) on Linux. Windows `.exe`/`.msi` needs the WiX Toolset installed.
The result is in the current folder; send that file to your users.

## Not included yet
Google/Facebook login (needs a browser-based OAuth flow), email verification, password reset, session expiry,
auto-update, app icon and code signing (unsigned apps show Windows SmartScreen / macOS Gatekeeper warnings).
