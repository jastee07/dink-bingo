# Dink Bingo backend (Google Sheet + Apps Script)

The spreadsheet is the single source of truth for the board. The plugin never decides
whether a tile is claimed — it asks, and only announces when the answer is `claimed`.

## Setup

1. Create a new Google Sheet.
2. **Extensions → Apps Script**, delete the placeholder, paste [`Code.gs`](Code.gs), save.
3. Run `setupSheet` once from the editor (approve the permission prompt). This creates the
   `Items`, `Teams`, `Claims`, `Audit`, `Config`, and `Leaderboard` tabs and generates a
   `token` and `admin_token`.
4. Fill in `Items`. Each accepted item is a row. A single-item tile uses its item id as
   `tile_id`; alternatives share a `tile_id`, `tile_name`, and points:

   | tile_id | tile_name | item_id | item_name | points |
   | --- | --- | ---: | --- | ---: |
   | raids-unique | Any raids unique | 21034 | Dexterous prayer scroll | 3 |
   | raids-unique | Any raids unique | 21079 | Dragon sword | 3 |

   `item_id` must be the canonical OSRS id from the wiki or
   `https://prices.runescape.wiki/api/v1/osrs/mapping`. An item id may appear only once;
   ambiguous rows or inconsistent names/points make board and claim requests fail visibly.
   Fill in `Teams` as `rsn` → `team`; team names are exact identifiers.
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
shows claimed tiles, earned points, remaining tiles, and remaining points; the matrix below
shows every logical tile against every distinct team, including the winning item. A tile
claimed by one team remains open for all
other teams. Correct source data in `Items`, `Teams`, or `Claims` rather than typing over the
spilled formulas.

After replacing `Code.gs` on a sheet using the original schema, run `upgradeGroupedTiles`
once. It adds `tile_id` and `tile_name`, backfills existing rows as single-item tiles, and
preserves Claims. The old plugin cannot read the grouped board response and the grouped plugin
cannot read the old response, so perform this between events or in a maintenance window and
update all players as part of the same cutover. For later formula-only changes, run
`refreshLeaderboard`.

Re-deploy (**Deploy → Manage deployments → Edit → Version: New**) after any script edit;
the `/exec` URL stays the same.

## Endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `?action=ping` | Liveness check, no auth |
| `POST` | body `{action:"board", token, rsn}` | The caller's team, remaining count, and every tile with its claim state |
| `POST` | body `{token, rsn, itemId, itemName, quantity, source, claimId}` | Attempt a claim |
| `POST` | body `{action:"unclaim", admin_token, team, tile_id}` | Admin undo for one logical tile |

Claim responses: `claimed`, `duplicate`, `not_on_board`, `not_on_team`, `event_closed`,
or `error`. Only `claimed` triggers an announcement.

### Concurrency and retries

Every mutating path holds `LockService.getScriptLock()`, so two players hitting the same tile
— even through different accepted items — produce exactly one `Claims` row.

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

Genuine duplicate — new `claimId`, another item from the same tile and team, expect
`"status":"duplicate"` and the original winning item:

```bash
curl -sL -X POST "$URL" -H 'Content-Type: application/json' -d '{"token":"'"$TOKEN"'","rsn":"Jake","itemId":21079,"itemName":"Dragon sword","quantity":1,"source":"Chambers of Xeric","claimId":"test-claim-002"}'
```

Concurrency — alternate ten requests across two items in one tile, expect exactly one
`claimed`, nine `duplicate`, and exactly one row in `Claims`:

```bash
for i in $(seq 1 10); do
  curl -sL -X POST "$URL" -H 'Content-Type: application/json' \
    -d '{"token":"'"$TOKEN"'","rsn":"Jake","itemId":'"$((i % 2 == 0 ? 21034 : 21079))"',"claimId":"race-'"$i"'"}' &
done; wait
```

Admin undo, then re-check the board:

```bash
curl -sL -X POST "$URL" -H 'Content-Type: application/json' \
  -d '{"action":"unclaim","admin_token":"PASTE_ADMIN_TOKEN","team":"Team One","tile_id":"raids-unique"}'
```

## Upgrading an existing sheet

For every existing sheet using the original one-item schema:

1. Paste the new `Code.gs` and save.
2. Run `upgradeGroupedTiles` once. It adds and backfills tile columns without deleting rows.
3. Run `scrubLegacySensitiveData` if the sheet received pre-security-hardening claims.
4. Replace both `token` and `admin_token` if that security scrub was needed.
5. Give participants only the current player token.
6. Delete the retired `account_hash` column from `Claims` if you no longer want the empty
   legacy column.
7. Deploy a new web-app version and update players to the grouped-tile plugin build together.
   The `/exec` URL stays the same.

Do not group items that already have separate claims for the same team until the organizer
has reconciled those rows; the backend deliberately rejects multiple Claims for one team/tile.

## Trust model

The player `token` is a shared event credential. Every participant needs it, so it stops
drive-by posts but not a determined participant. The `/exec` URL alone does not authorize a
board lookup or claim. The mitigations are visibility and reversibility:

- `Audit` records operational claim/unclaim fields but allowlists out tokens and webhook URLs.
- `Claims` records the logical tile, winning item, normalized RSN, source, claim id, and
  timestamp; it does not
  receive a RuneLite account hash.
- `admin_token` is organizer-only and enables unclaim.
- `discord_webhook` is read only by Apps Script when backend announcements are enabled and is
  never included in an API response or Audit row.
