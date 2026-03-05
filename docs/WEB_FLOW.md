# Web Flow

## Pages
- `join.html`
- `create_election.html`
- `vote.html`
- `join_result.html`
- `home.html`
- `index.html`
- DNS pages: `create.html`, `update.html`, `delete.html`, `lookup.html`, `status.html`
- `login.html`

## First-Time Node Onboarding
1. Open `join.html`.
2. Submit bootstrap IP, private key, username, password to `/join`.
3. Click next step (`/checkfetchresult`).
4. If `election=true`: move to election creation path.
5. If `election=false` and accepted: move to DNS control panel.

## Election Lifecycle
1. Create nomination in `create_election.html` (`/election/create-join`).
2. View and cast votes in `vote.html` (`/election/nominations`, `/election/vote`).
3. Check result in `join_result.html` (`/election/result`).
4. Accepted path leads to `home.html`, then DNS control as permitted.

## Login / Session Lifecycle
- Session duration: 15 minutes.
- Login endpoint sets cookie and server-side token/expiry.
- `/auth/session` is the source of truth for UI guard and route access.

## Access Rules

### Not Logged In
- Only login/auth routes are publicly accessible.
- If no user configured yet, join/bootstrap pages are allowed.

### Logged In But Not Accepted
- CRUD and vote pages are blocked/hidden.
- Home/join/election result path is still accessible.

### Logged In And Accepted
- Full UI and APIs available.
