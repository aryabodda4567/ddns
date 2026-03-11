# Technology Stack

## Build System

- Maven (Java 17)
- Entry point: `org.ddns.Main`
- Build output: `target/`

## Core Dependencies

- **Spark Java 2.9.4** - HTTP server and static web content
- **Gson 2.11.0** - JSON serialization
- **SQLite 3.43.0.0** - Local persistence
- **Bouncy Castle 1.70** - Cryptography (bcprov-jdk15on, bcpkix-jdk15on)
- **dnsjava 3.6.0** - DNS protocol support
- **SLF4J 1.7.36** - Logging (slf4j-api, slf4j-simple)
- **Jetty 9.4.48** - Servlet container

## Common Commands

### Build
```bash
mvn clean package
```

### Run Application
```bash
mvn exec:java
```

### Clean Build Artifacts
```bash
mvn clean
```

## Runtime Requirements

- Java 17 or higher
- Bouncy Castle provider must be registered at startup (`Security.addProvider(new BouncyCastleProvider())`)
- Web UI accessible at `http://localhost:8080/`

## Build Configuration

- Source/target: Java 17
- Encoding: UTF-8
- Main class: `org.ddns.Main` (configured in exec-maven-plugin)
