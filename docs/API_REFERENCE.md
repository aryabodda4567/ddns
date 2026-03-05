# API Reference

Base URL: `http://<host>:8080`

## Auth

### `POST /auth/login`
Request:
```json
{ "username": "string", "password": "string" }
```
Success:
```json
{ "status": "ok", "expiresAt": 0, "sessionSeconds": 900 }
```

### `GET /auth/session`
Response:
```json
{
  "userConfigured": true,
  "requireJoin": false,
  "accepted": false,
  "requireLogin": true,
  "authenticated": true,
  "expiresAt": 0,
  "sessionSeconds": 900
}
```

### `POST /auth/logout`
Response:
```json
{ "status": "ok" }
```

## Join / Bootstrap

### `POST /join`
Request:
```json
{
  "bootstrapIp": "string",
  "privateKey": "string",
  "username": "string",
  "password": "string"
}
```
Response:
```json
{
  "status": "ok",
  "publicKey": "string",
  "localIp": "string",
  "username": "string",
  "expiresAt": 0,
  "sessionSeconds": 900
}
```

### `GET /checkfetchresult`
Response:
```json
{ "election": true, "firstNode": false, "accepted": false }
```

## Election

### `POST /election/create-join`
Request:
```json
{
  "password": "string",
  "nodeName": "string",
  "timeMinutes": 10,
  "description": "string",
  "electionType": 0
}
```
Response:
```json
{ "status": "ok", "message": "Election created" }
```

### `GET /election/nominations`
Response:
```json
{ "nominations": [ { "index": 0, "nodeIp": "..." } ] }
```

### `POST /election/vote`
Request:
```json
{ "nominationIndex": 0 }
```
Response:
```json
{ "status": "ok", "message": "Vote cast successfully" }
```

### `POST /election/result`
Request:
```json
{ "password": "string" }
```
Accepted response:
```json
{ "accepted": true, "expiresAt": 0, "sessionSeconds": 900 }
```
Rejected response:
```json
{ "accepted": false }
```

## DNS

### `POST /dns/create`
Request:
```json
{ "name": "example.com", "type": 1, "ttl": 300, "rdata": "1.2.3.4" }
```
Response:
```json
{ "status": "OK", "message": "DNS record submitted to consensus", "txHash": "..." }
```

### `POST /dns/update`
Request: same shape as create.

### `POST /dns/delete`
Request:
```json
{ "name": "example.com", "type": 1, "rdata": "1.2.3.4" }
```

### `GET /dns/lookup?name=<domain>&type=<optional>`
Response includes `records` array.

### `GET /dns/reverse?value=<ip-or-value>`
Response includes `records` array.

### `GET /dns/status?txHash=<hash>`
Response includes transaction status/details when found.

## Error Shape
Most handlers return:
```json
{ "error": "message" }
```
with appropriate HTTP status (400/401/403/404/500).
