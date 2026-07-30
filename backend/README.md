# Dink Bingo backend (Google Sheet + Apps Script)

The spreadsheet is the single source of truth for the board. The plugin never decides
whether a tile is claimed — it asks, and only announces when the answer is `claimed`.

## Setup

1. Create a new Google Sheet.
2. **Extensions → Apps Script**, delete the placeholder, paste [`Code.gs`](Code.gs), save.
3. Run `setupSheet` once from the editor (approve the permission prompt). This creates the
   `Items`, `Teams`, `Claims`, `Audit`, `Config`, and `Leaderboard` tabs and generates a
   `token` and `admin_token`.
4. Fill in `Items` (`item_id` is the canonical OSRS item id — get it from the wiki or
   `https://prices.runescape.wiki/api/v1/osrs/mapping`) and `Teams` (`rsn` → `team`). Team
   names are exact identifiers, so use identical spelling and capitalization for teammates.
5. Optionally set `discord_webhook`, `event_start`, `event_end`, and
   `announce_from_backend` in `Config`. Leave `announce_from_backend` as `false` if your
   players run Dink — Dink's own announcement includes a screenshot, the backend's does not.
6. **Deploy → New deployment → Web app**, *Execute as* **Me**, *Who has access* **Anyone**.
   Copy the `/exec` URL.

`Leaderboard` is a formula-driven, read-only view of the authoritative tabs. Its team summary
shows claimed tiles, points, and remaining tiles; the matrix below shows every item against
every distinct team. A tile claimed by one team remains open for all other teams. Correct
source data in `Items`, `Teams`, or `Claims` rather than typing over the spilled formulas.

Re-deploy (**Deploy → Manage deployments → Edit → Version: New**) after any script edit;
the `/exec` URL stays the same.

## Endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `?action=board&token=…&rsn=…` | The caller's team, remaining count, and every tile with its claim state |
| `GET` | `?action=ping` | Liveness check, no auth |
| `POST` | body `{token, rsn, itemId, itemName, quantity, source, claimId, accountHash}` | Attempt a claim |
| `GET` | `?action=unclaim&admin_token=…&team=…&item_id=…` | Admin undo |

Claim responses: `claimed`, `duplicate`, `not_on_board`, `not_on_team`, `event_closed`,
or `error`. Only `claimed` triggers an announcement.

### Concurrency and retries

Every mutating path holds `LockService.getScriptLock()`, so two players hitting the same
item at the same moment produce exactly one `Claims` row.

The plugin generates a `claimId` (UUID) per detected drop and reuses it across retries.
The backend checks `claimId` against existing claims *before* anything else, so a retried
POST returns the original result with `"replay": true` rather than double-claiming or
falsely reporting a duplicate.

## Smoke tests

Do these before touching the plugin — they confirm the deployment, the token, and the
redirect behavior (Apps Script answers a POST with a 302 to `script.googleusercontent.com`,
so `-L` is required).

```bash
URL="https://script.google.com/macros/s/PASTE_ID/exec"
TOKEN="paste-token-from-Config"
```

Liveness:

```bash
curl -sL "$URL?action=ping"
```

Board (expect your team and every tile):

```bash
curl -sL "$URL?action=board&token=$TOKEN&rsn=Jake"
```

First claim (expect `"status":"claimed"`):

```bash
curl -sL -X POST "$URL" -H 'Content-Type: application/json' -d '{"token":"'"$TOKEN"'","rsn":"Jake","itemId":21034,"itemName":"Dexterous prayer scroll","quantity":1,"source":"Chambers of Xeric","claimId":"test-claim-001"}'
```

Idempotent replay — same `claimId`, expect `"status":"claimed","replay":true` and **no new
`Claims` row**:

```bash
curl -sL -X POST "$URL" -H 'Content-Type: application/json' -d '{"token":"'"$TOKEN"'","rsn":"Jake","itemId":21034,"itemName":"Dexterous prayer scroll","quantity":1,"source":"Chambers of Xeric","claimId":"test-claim-001"}'
```

Genuine duplicate — new `claimId`, same item and team, expect `"status":"duplicate"`:

```bash
curl -sL -X POST "$URL" -H 'Content-Type: application/json' -d '{"token":"'"$TOKEN"'","rsn":"Jake","itemId":21034,"itemName":"Dexterous prayer scroll","quantity":1,"source":"Chambers of Xeric","claimId":"test-claim-002"}'
```

Concurrency — fire ten claims for one item at once, expect exactly one `claimed` and nine
`duplicate`, and exactly one row in `Claims`:

```bash
for i in $(seq 1 10); do
  curl -sL -X POST "$URL" -H 'Content-Type: application/json' \
    -d '{"token":"'"$TOKEN"'","rsn":"Jake","itemId":4151,"itemName":"Abyssal whip","claimId":"race-'"$i"'"}' &
done; wait
```

Admin undo, then re-check the board:

```bash
curl -sL "$URL?action=unclaim&admin_token=PASTE_ADMIN_TOKEN&team=Team%20One&item_id=21034"
```

## Trust model

The player `token` is shared with every participant, so it stops drive-by posts but not a
determined participant. The mitigations are visibility and reversibility, not secrecy:

- `Audit` records **every** request including rejections, with the raw payload.
- `Claims` records `account_hash` (a stable per-account id from RuneLite) next to the RSN,
  so an impostor RSN or a mid-event name change is visible after the fact.
- `admin_token` is separate and enables unclaim.
