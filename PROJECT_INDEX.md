# Project Index

## Top-Level
- `.git/` - Git metadata
- `.idea/` - IDE project settings
- `docs/` - project documentation
- `snapshots/` - block snapshot database files
- `src/` - application source code
- `target/` - Maven build output
- `pom.xml` - Maven build configuration
- `README.md` - main project guide

## Java Source (`src/main/java/org/ddns`)
- `Main.java` - application bootstrap
- `bc/` (7 files) - blockchain primitives and signing
- `bootstrap/` (1 file) - bootstrap node behavior
- `chain/` (1 file) - wallet/key helper
- `consensus/` (7 files) - queue, scheduler, consensus core
- `constants/` (5 files) - shared enums/constants
- `db/` (5 files) - persistence layers
- `dns/` (10 files) - DNS model/server/service/cache/resolver
- `governance/` (3 files) - election, nomination, vote
- `net/` (4 files) - network messaging contracts + manager
- `node/` (2 files) - node management + sync/apply
- `tests/` (4 files) - in-repo test harness classes
- `util/` (4 files) - shared utility helpers
- `web/` (19 files) - web server, handlers, auth/session

## Static Web (`src/main/resources/public`)
- Pages:
  - `join.html`, `login.html`, `home.html`, `index.html`
  - `create_election.html`, `vote.html`, `join_result.html`
  - `create.html`, `update.html`, `delete.html`, `lookup.html`, `status.html`
- Assets:
  - `css/common.css`
  - `js/auth-guard.js`

## Docs
- `docs/ARCHITECTURE.md`
- `docs/API_REFERENCE.md`
- `docs/WEB_FLOW.md`
- `docs/STORAGE_AND_STATE.md`
