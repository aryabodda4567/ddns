# Project Structure

## Source Organization

Base package: `org.ddns`

### Core Modules

- **`bc/`** - Blockchain primitives
    - Block, Transaction, MerkleTree
    - Signature utilities and key adapters
    - Transaction types

- **`bootstrap/`** - Bootstrap node behavior
    - BootstrapNode implementation

- **`chain/`** - Wallet and key management
    - Wallet utility for key pair operations

- **`consensus/`** - Consensus engine
    - ConsensusEngine, ConsensusScheduler, ConsensusSystem
    - CircularQueue, QueueNode for leader rotation
    - LivenessController for failover

- **`constants/`** - Shared enums and configuration keys
    - ConfigKey, DNSMessage, ElectionType, FileNames, Role

- **`crypto/`** - Cryptographic operations
    - Crypto utilities, SSL configuration

- **`db/`** - Persistence layer (SQLite)
    - DBUtil (node config, key-value store)
    - BlockDb, TransactionDb, DNSDb, BootstrapDB
    - Domain-specific database access

- **`dns/`** - DNS domain logic
    - DNSModel, DNSService, DNSServer, DNSResolver
    - DNSCache, DNSPersistence, DNSExecutor
    - TCP/UDP packet servers
    - RecordType enum

- **`governance/`** - Election and voting
    - Election, Nomination, Vote

- **`net/`** - Network layer
    - NetworkManager (message routing, broadcast, file transfer)
    - Message, MessageType, MessageHandler

- **`node/`** - Node management
    - NodeConfig, NodesManager
    - Handles sync, apply, and node lifecycle

- **`tests/`** - In-project test harnesses
    - BlockChainBuilder, ConsensusFailoverTest
    - SimpleDNSTest, TransactionDbTest

- **`util/`** - Shared utilities
    - ConsolePrinter, ConversionUtil, NetworkUtility, TimeUtil

- **`web/`** - Web server and HTTP handlers
    - WebServer (Spark Java routes)
    - `services/config/` - Join, bootstrap, mode handlers
    - `services/dns/` - DNS web API handlers
    - `services/election/` - Election web handlers
    - `services/wallet/` - Wallet operations

## Static Web Resources

Location: `src/main/resources/public/`

- **HTML pages**: join.html, login.html, home.html, create.html, update.html, delete.html, lookup.html, vote.html,
  status.html
- **CSS**: css/common.css
- **JavaScript**: js/auth-guard.js (frontend session validation)

## Data Files

- **`bootstrap.db`** - Bootstrap node registry
- **`snapshots/`** - Block snapshot databases
- **`keystore.jks`** - SSL keystore
- **Runtime artifacts**: `.db`, `.bin` files may appear in project root

## Documentation

- **`docs/ARCHITECTURE.md`** - System design and component overview
- **`docs/API_REFERENCE.md`** - HTTP API routes
- **`docs/WEB_FLOW.md`** - UI onboarding flows
- **`docs/STORAGE_AND_STATE.md`** - Persistence and state management
- **`PROJECT_INDEX.md`** - File map

## Architectural Patterns

- **Message-driven networking**: Components implement MessageHandler and register with NetworkManager
- **Domain-driven persistence**: Each domain (block, transaction, DNS) has dedicated DB class
- **Web security gating**: Access filters in WebServer enforce authentication and acceptance requirements
- **Blockchain state application**: NodesManager.applyBlock updates DNS state from blocks
- **Session management**: Server-side validation with HttpOnly cookies and DB-backed tokens
