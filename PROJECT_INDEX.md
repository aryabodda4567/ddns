# Project Index

## Top-Level
- `.git/` - Git metadata
- `.idea/` - IDE project settings
- `snapshots/` - block snapshot database files
- `src/` - application source code
- `target/` - Maven build output (compiled artifacts)
- `pom.xml` - Maven build configuration
- `bootstrap.db`, `dns.bin`, `block.bin`, `utility.db` - local runtime data stores

## Source Layout (`src/main`)
- `java/org/ddns/Main.java` - application entry point
- `java/org/ddns/bc/` (7 files) - blockchain primitives and signing
- `java/org/ddns/bootstrap/` (1 file) - bootstrap node behavior
- `java/org/ddns/chain/` (1 file) - wallet/chain helper logic
- `java/org/ddns/consensus/` (7 files) - consensus engine and scheduling
- `java/org/ddns/constants/` (5 files) - enums/constants used across modules
- `java/org/ddns/db/` (5 files) - persistence access layers
- `java/org/ddns/dns/` (10 files) - DNS model, cache, resolver, and servers
- `java/org/ddns/governance/` (3 files) - election/nomination/vote models
- `java/org/ddns/net/` (4 files) - network messaging and handlers
- `java/org/ddns/node/` (2 files) - node config and node manager
- `java/org/ddns/tests/` (4 files) - in-project test harness classes
- `java/org/ddns/util/` (4 files) - shared utility functions
- `java/org/ddns/web/` (14 files) - web server and API handlers
- `resources/public/` (12 files) - static HTML/CSS frontend assets

## Static Web Assets (`src/main/resources/public`)
- `index.html`, `home.html`, `status.html`
- `create.html`, `update.html`, `delete.html`, `lookup.html`
- `join.html`, `join_result.html`
- `create_election.html`, `vote.html`
- `css/common.css`

## Notes
- `target/` mirrors compiled classes for the Java packages above.
- `snapshots/` contains timestamped DB snapshots generated at runtime.
