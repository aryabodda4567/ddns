# dDNS Project

Decentralized DNS system with:
- blockchain-backed DNS record operations,
- node networking and synchronization,
- governance election/voting flow,
- web UI for join, auth, election, and DNS control.

## Tech Stack
- Java 17
- Maven
- Spark Java (HTTP server + static web)
- Gson (JSON)
- SQLite (local storage)
- Bouncy Castle (crypto)
- dnsjava (DNS support)
- SLF4J (logging)

## Entry Points
- App entry: `src/main/java/org/ddns/Main.java`
- Web entry: `src/main/java/org/ddns/web/WebServer.java`
- Static UI: `src/main/resources/public/`

## Module Overview
- `org.ddns.bc`: blockchain primitives (block, tx, signatures)
- `org.ddns.bootstrap`: bootstrap node behavior
- `org.ddns.chain`: wallet/key utility
- `org.ddns.consensus`: consensus engine and scheduling
- `org.ddns.constants`: enums and shared keys
- `org.ddns.db`: SQLite access layers
- `org.ddns.dns`: DNS model/service/server/cache
- `org.ddns.governance`: election, nomination, vote domain
- `org.ddns.net`: message and network transport
- `org.ddns.node`: node manager and sync/apply logic
- `org.ddns.web`: web server, handlers, user auth/session
- `org.ddns.tests`: in-project test harnesses
- `org.ddns.util`: shared utilities

## Quick Start
1. Build:
```bash
mvn clean package
```
2. Run:
```bash
mvn exec:java
```
3. Open web UI:
- `http://localhost:8080/join.html`

## Web Flow (High Level)
1. Join at `join.html` with bootstrap IP, private key, username, password.
2. Server stores node + user credentials and creates session.
3. `checkfetchresult` decides first-node vs election path.
4. Election result acceptance sets accepted state and session.
5. Accepted users can access DNS CRUD + vote panel.

## Security Model (Web)
- Session cookie: 15-minute expiry.
- Route gate in `WebServer` enforces:
  - login for protected pages/APIs,
  - bootstrap-only access when user not yet configured,
  - accepted-node requirement for CRUD/voting APIs and pages.
- Frontend guard (`public/js/auth-guard.js`) mirrors backend constraints in UI.

## Documentation Map
- Architecture: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- API routes: [docs/API_REFERENCE.md](docs/API_REFERENCE.md)
- UI and onboarding flows: [docs/WEB_FLOW.md](docs/WEB_FLOW.md)
- Data/storage/state: [docs/STORAGE_AND_STATE.md](docs/STORAGE_AND_STATE.md)
- File map: [PROJECT_INDEX.md](PROJECT_INDEX.md)
