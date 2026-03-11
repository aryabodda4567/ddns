# Product Overview

dDNS is a decentralized DNS system that combines blockchain technology with traditional DNS functionality.

## Core Features

- Blockchain-backed DNS record operations (create, update, delete, lookup)
- Distributed node networking with peer synchronization
- Governance system with elections, nominations, and voting
- Web UI for node management, authentication, and DNS control
- Consensus engine for transaction validation and block production

## Key Concepts

- Nodes can join the network through a bootstrap process
- DNS changes are recorded as blockchain transactions
- Governance elections determine network leadership and roles
- Session-based authentication with 15-minute expiry
- Accepted nodes have full CRUD access to DNS records

## Security Model

- Private/public key cryptography for node identity
- Session cookies with server-side validation
- Route-level access control (public, bootstrap-only, accepted-only)
- DNS operations require node acceptance status
