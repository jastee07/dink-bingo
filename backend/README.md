# Dink Bingo backend (Google Sheet + Apps Script)

The spreadsheet is the single source of truth for the board. The plugin never decides
whether a tile is complete — it asks, and announces each accepted `progress` contribution
and the final `claimed` contribution.

## Setup

1. Create a new Google Sheet.
2. **Extensions → Apps Script**, delete the placeholder, paste [`Code.gs`](Code.gs), save.
3. Run `setupSheet` once from the editor (approve the permission prompt). This creates the
   `Items`, `Teams`, `Claims`, `Audit`, `Config`, and `Leaderboard` tabs and generates a
   `token` and `admin_token`.
4. Fill in `Items` using
   `tile_id`, `tile_name`, `item_id`, `item_name`, `points`, `required_count`, `notes`.
   Each accepted item is a row. Alternatives share a tile id/name, points, and required count.
   Here is a 2-of-3 tile:

   | tile_id | tile_name | item_id | item_name | points | required_count | notes |
   | --- | --- | ---: | --- | ---: | ---: | --- |
   | 26807 | Abyssal dye | 26807 | Abyssal green dye | 3 | 2 | Any two colors |
   | 26807 | Abyssal dye | 26809 | Abyssal blue dye | 3 | 2 | Any two colors |
   | 26807 | Abyssal dye | 26811 | Abyssal red dye | 3 | 2 | Any two colors |

   For 3-of-5, create five option rows under one tile id and repeat `3` in
   `required_count` on all five.

   `item_id` must be the canonical OSRS id from the wiki or
   `https://prices.runescape.wiki/api/v1/osrs/mapping`. An item id may appear only once;
   ambiguous rows or inconsistent names/points/required counts make board and claim requests
   fail visibly. `required_count` is a whole number between 1 and the number of distinct tile
   options. Use `1` for 1-of-N and `3` for 3-of-5. Counts are based on distinct item ids, not
   stack quantity. Fill in `Teams` as `rsn` → `team`; team names are exact identifiers.
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
shows completed tiles, earned points, remaining tiles, and remaining points; the matrix below
shows each team's progress such as `1/2` or `✓ 2/2 player — item`. Points count only after the
threshold is reached. A tile completed by one team remains open or partial for other teams.
Correct source data in `Items`, `Teams`, or `Claims` rather than typing over the formulas.

After replacing `Code.gs` on a sheet using the original schema, run `upgradeGroupedTiles`
once. It adds tile and threshold columns, backfills existing Items with `required_count=1`,
and marks existing Claims as completed 1-of-1 contributions. Perform this between events or in
a maintenance window and update all players as part of the same coordinated cutover. For later
formula-only changes, run `refreshLeaderboard`.

Re-deploy (**Deploy → Manage deployments → Edit → Version: New**) after any script edit;
the `/exec` URL stays the same.

## Endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `?action=ping` | Liveness check, no auth |
| `POST` | body `{action:"board", token, rsn}` | The caller's team, remaining count, and every tile with its claim state |
| `POST` | body `{token, rsn, itemId, itemName, quantity, source, claimId}` | Attempt a claim |
| `POST` | body `{action:"unclaim", admin_token, team, tile_id, item_id?}` | Admin undo for one contributed item, or the whole tile when `item_id` is omitted |

Claim responses: `progress`, `claimed`, `duplicate`, `not_on_board`, `not_on_team`,
`event_closed`, or `error`. `progress` and `claimed` trigger announcements; duplicates and
failures do not.

### Concurrency and retries

Every mutating path holds `LockService.getScriptLock()`. A team/tile/item combination can
produce only one contribution row. Different accepted item ids can each advance a threshold
tile until it completes; later contributions are duplicates.

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

For the 2-of-3 Abyssal dye example above, first contribution (expect
`"status":"progress","progress":1,"required":2`):

```bash
curl -sL -X POST "$URL" -H 'Content-Type: application/json' -d '{"token":"'"$TOKEN"'","rsn":"Jake","itemId":26809,"itemName":"Abyssal blue dye","quantity":1,"source":"Guardians of the Rift","claimId":"test-claim-001"}'
```

Idempotent replay — same `claimId`, expect `"status":"progress","replay":true` and **no new
`Claims` row**:

```bash
curl -sL -X POST "$URL" -H 'Content-Type: application/json' -d '{"token":"'"$TOKEN"'","rsn":"Jake","itemId":26809,"itemName":"Abyssal blue dye","quantity":1,"source":"Guardians of the Rift","claimId":"test-claim-001"}'
```

Same-item duplicate — new `claimId`, expect `"status":"duplicate"` and progress still 1/2:

```bash
curl -sL -X POST "$URL" -H 'Content-Type: application/json' -d '{"token":"'"$TOKEN"'","rsn":"Jake","itemId":26809,"itemName":"Abyssal blue dye","quantity":1,"source":"Guardians of the Rift","claimId":"test-claim-002"}'
```

Second distinct contribution (expect `"status":"claimed","progress":2,"required":2` and points
now awarded):

```bash
curl -sL -X POST "$URL" -H 'Content-Type: application/json' -d '{"token":"'"$TOKEN"'","rsn":"Jake","itemId":26811,"itemName":"Abyssal red dye","quantity":1,"source":"Guardians of the Rift","claimId":"test-claim-003"}'
```

After completion, green dye is a tile-complete duplicate. The `Claims` tab should contain
exactly two rows for this team/tile.

Admin undo just blue dye (expect progress 1/2), then re-check the board:

```bash
curl -sL -X POST "$URL" -H 'Content-Type: application/json' \
  -d '{"action":"unclaim","admin_token":"PASTE_ADMIN_TOKEN","team":"Team One","tile_id":"26807","item_id":26809}'
```

Admin undo every remaining contribution for the tile:

```bash
curl -sL -X POST "$URL" -H 'Content-Type: application/json' \
  -d '{"action":"unclaim","admin_token":"PASTE_ADMIN_TOKEN","team":"Team One","tile_id":"26807"}'
```

## Upgrading an existing sheet

For every existing sheet using the original one-item schema:

1. Paste the new `Code.gs` and save.
2. Run `upgradeGroupedTiles` once. It adds and backfills tile/threshold columns without
   deleting rows. Existing items use `required_count=1`; existing claims are marked complete.
3. Run `scrubLegacySensitiveData` if the sheet received pre-security-hardening claims.
4. Replace both `token` and `admin_token` if that security scrub was needed.
5. Give participants only the current player token.
6. Delete the retired `account_hash` column from `Claims` if you no longer want the empty
   legacy column.
7. Deploy a new web-app version and update players to the threshold-capable plugin build
   together.
   The `/exec` URL stays the same.

Before raising `required_count` on a previously used tile, verify the existing contribution
rows represent distinct item ids and reconcile any legacy conflicts. Always keep the same
required count on every option row.

## Trust model

The player `token` is a shared event credential. Every participant needs it, so it stops
drive-by posts but not a determined participant. The `/exec` URL alone does not authorize a
board lookup or claim. The mitigations are visibility and reversibility:

- `Audit` records operational claim/unclaim fields but allowlists out tokens and webhook URLs.
- `Claims` records each distinct contribution's logical tile, actual item, normalized RSN,
  source, claim id, progress-after value, completion flag, and timestamp; it does not receive
  a RuneLite account hash.
- `admin_token` is organizer-only and enables item-level or whole-tile unclaim.
- `discord_webhook` is read only by Apps Script when backend announcements are enabled and is
  never included in an API response or Audit row.
