# Storage and State

## Primary Persistence

### `DBUtil` (utility SQLite)
Stores generic key-value and node metadata:
- keys: bootstrap IP, role, keys, acceptance flag, session data, web-user data
- table: node list (`nodes`)
- table: nomination-related data (`nominations`)

### `BlockDb`
- blockchain blocks
- export/import snapshot support used by sync

### `TransactionDb`
- transaction rows and status queries (`/dns/status`)

### `DNSDb`
- materialized DNS state
- updated by replay/apply from blocks (`NodesManager.applyBlock`)

### `BootstrapDB`
- bootstrap node registry/config

## Important State Flags
- `IS_ACCEPTED` (global config): controls accepted-node access
- web user keys (`username`, `password`, etc.)
- session keys (`session_token`, `session_expires_at`, `is_logged_in`)

## Session Data
- Cookie name: `ddns_session` (`HttpOnly`)
- Session token and expiry are validated against DB data
- Expired sessions are actively cleared

## Runtime File Notes
Depending on execution, local binary/db artifacts may appear in project root (e.g. `.db`, `.bin`) and under `snapshots/`.
