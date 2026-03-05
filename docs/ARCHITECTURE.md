# Architecture

## Runtime Components

1. `Main`
- Initializes crypto provider.
- Builds core runtime services.
- Starts network listeners and web server.

2. Networking Layer (`org.ddns.net`)
- `NetworkManager` handles direct/broadcast/multicast messages and file transfer.
- `Message` + `MessageType` define protocol contracts.
- `MessageHandler` is implemented by domain managers.

3. Node Management (`org.ddns.node.NodesManager`)
- Registers with network manager.
- Handles node add/delete/promote/fetch/sync/queue updates.
- Coordinates startup of consensus and DNS services for equal-role mode.
- Applies blockchain blocks into DNS state.

4. Governance (`org.ddns.governance`)
- `Election` controls nomination lifecycle, voting, and result calculation.
- Uses DB-backed vote and nomination persistence.
- On acceptance, sets `IS_ACCEPTED` config flag.

5. Consensus (`org.ddns.consensus`)
- Transaction publication and block production/failover mechanics.
- Queue/scheduler abstractions for leader rotation.

6. DNS Domain (`org.ddns.dns`)
- `DNSModel` as record payload model.
- DNS server/resolver/cache/service classes.
- CRUD operations are represented as blockchain transactions.

7. Persistence (`org.ddns.db`)
- `DBUtil`: node utility/config database (SQLite key-value + nodes tables).
- `BlockDb`, `TransactionDb`, `DNSDb`, `BootstrapDB`: domain stores.

8. Web Layer (`org.ddns.web`)
- Spark Java routes and static content.
- Handlers for join/bootstrap, election, DNS APIs, auth/session.
- Access filter enforces login and acceptance policy.

## Web Security Gating

Implemented in `WebServer.before(...)`:
- Public routes: login/auth endpoints.
- Bootstrap routes: allowed only before user configuration.
- Protected routes: require valid session.
- Accepted-only routes: DNS CRUD/vote APIs and pages.

Frontend mirror:
- `public/js/auth-guard.js` calls `/auth/session`.
- Hides accepted-only controls when not accepted.
- Redirects to login or join as needed.

## Key Design Notes

- Web UI does not execute business logic; it calls JSON APIs.
- Node acceptance and login are separate states.
- Sessions are short-lived and server-validated (`HttpOnly` cookie + DB token/expiry).
- DNS changes go through consensus transactions, not direct DB writes from UI.
