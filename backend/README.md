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

Keep the spreadsheet organizer-only. Give participants the `/exec` URL and player `token`,
not access to the editable Sheet. The `admin_token` and optional `discord_webhook` remain in
the organizer-owned `Config` tab and are never returned by the API. Hiding that tab is cosmetic,
not access control.

`Leaderboard` is a formula-driven, read-only view of the authoritative tabs. Its team summary
shows claimed tiles, earned points, remaining items, and remaining points; the matrix below
shows every item against every distinct team. A tile claimed by one team remains open for all
other teams. Correct source data in `Items`, `Teams`, or `Claims` rather than typing over the
spilled formulas.

After replacing `Code.gs` on an existing sheet, run `refreshLeaderboard` once from the Apps
Script editor. It updates only the derived leaderboard formulas and formatting; it does not
change `Items`, `Teams`, `Claims`, `Audit`, or `Config`.

Re-deploy (**Deploy → Manage deployments → Edit → Version: New**) after any script edit;
the `/exec` URL stays the same.

## Endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `?action=ping` | Liveness check, no auth |
| `POST` | body `{action:"board", token, rsn}` | The caller's team, remaining count, and every tile with its claim state |
| `POST` | body `{token, rsn, itemId, itemName, quantity, source, claimId}` | Attempt a claim |
| `POST` | body `{action:"unclaim", admin_token, team, item_id}` | Admin undo |

Claim responses: `claimed`, `duplicate`, `not_on_board`, `not_on_team`, `event_closed`,
or `error`. Only `claimed` triggers an announcement.

### Concurrency and retries

Every mutating path holds `LockService.getScriptLock()`, so two players hitting the same
item at the same moment produce exactly one `Claims` row.

The plugin generates a `claimId` (UUID) per detected drop and reuses it across retries.
The backend checks `claimId` against existing claims *before* anything else, so a retried
POST returns the original result with `"replay": true` rather than double-claiming or
falsely reporting a duplicate.

When the original HTTP response is lost after the Sheet committed the claim, that replay is
the first successful response the client sees, so the running client sends one Dink request.
If RuneLite exits during that ambiguity window, the Sheet still owns the claim but no system
can prove whether Discord received the announcement; the Sheet remains the authoritative record.

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
curl -sL -X POST "$URL" -H 'Content-Type: application/json' \
  -d '{"action":"board","token":"'"$TOKEN"'","rsn":"Jake"}'
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
curl -sL -X POST "$URL" -H 'Content-Type: application/json' \
  -d '{"action":"unclaim","admin_token":"PASTE_ADMIN_TOKEN","team":"Team One","item_id":21034}'
```

## Upgrading an existing sheet

If the sheet received claims using a version from before the security hardening release:

1. Paste the new `Code.gs` and save.
2. Run `scrubLegacySensitiveData` once from the Apps Script editor.
3. Replace both `token` and `admin_token` in `Config` with newly generated UUIDs.
4. Give participants only the new player token.
5. Delete the retired `account_hash` column from `Claims` if you no longer want the empty
   legacy column.
6. Deploy a new web-app version. The `/exec` URL stays the same.

The helper redacts legacy Audit payloads and clears stored account hashes. Token rotation is
manual so the organizer controls when existing clients stop working.

## Trust model

The player `token` is a shared event credential. Every participant needs it, so it stops
drive-by posts but not a determined participant. The `/exec` URL alone does not authorize a
board lookup or claim. The mitigations are visibility and reversibility:

- `Audit` records operational claim/unclaim fields but allowlists out tokens and webhook URLs.
- `Claims` records the normalized RSN, item, source, claim id, and timestamp; it does not
  receive a RuneLite account hash.
- `admin_token` is organizer-only and enables unclaim.
- `discord_webhook` is read only by Apps Script when backend announcements are enabled and is
  never included in an API response or Audit row.
