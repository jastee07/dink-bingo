# Running an event

Two roles: the **organizer** builds the board once, each **player** pastes two values into
config. Total player-side effort is about a minute.

---

## Organizer

### 1. Build the sheet

1. Create a new Google Sheet.
2. **Extensions → Apps Script**, delete the placeholder, paste [`backend/Code.gs`](backend/Code.gs), save.
3. Run `setupSheet` once from the editor and approve the permission prompt. It creates the
   `Items`, `Teams`, `Claims`, `Audit`, `Config`, and `Leaderboard` tabs and generates a
   `token` and `admin_token`.
4. **`Items` tab** — one row per accepted item, with columns
   `tile_id`, `tile_name`, `item_id`, `item_name`, `points`, `required_count`, `notes`.
   `item_id` is the canonical OSRS item id; get it from the wiki URL or
   `https://prices.runescape.wiki/api/v1/osrs/mapping`. Repeat the same `required_count` on
   every row in a tile. Use `1` for a normal or “one of” tile, `2` for a 2-of-N tile, and so
   on. For example, this is a 2-of-3 Abyssal dye tile:

   | tile_id | tile_name | item_id | item_name | points | required_count | notes |
   | --- | --- | ---: | --- | ---: | ---: | --- |
   | 26807 | Abyssal dye | 26807 | Abyssal green dye | 3 | 2 | Any two colors |
   | 26807 | Abyssal dye | 26809 | Abyssal blue dye | 3 | 2 | Any two colors |
   | 26807 | Abyssal dye | 26811 | Abyssal red dye | 3 | 2 | Any two colors |

   A 3-of-5 tile uses the same pattern: create five rows with one shared tile ID and put `3`
   in `required_count` on all five rows.

   An item id may appear only once in the tab. Repeated item ids, blank fields, or inconsistent
   names, points, or required counts within a tile make the backend reject the board until the
   organizer fixes it. `required_count` must be a whole number from 1 through the number of
   distinct options. Quantities do not count: one drop of two blue dyes is still one distinct
   option.
5. **`Teams` tab** — one row per player: `rsn`, `team`. This is the only place team membership
   lives. RSNs are matched case-insensitively with `_` treated as a space, so `Zezima` and
   `zez ima` behave as you'd expect. Use the exact same spelling and capitalization for every
   member of a team; each distinct team name gets its own claim state for every logical tile.
6. **`Config` tab** — optionally set `event_start` / `event_end` (claims outside the window are
   rejected with `event_closed`) and leave `announce_from_backend` as `false` if your players
   run Dink.
7. **`Leaderboard` tab** — read-only event view. It shows K-of-N progress, completed tiles,
   earned points, remaining tiles, and remaining points for every team. Points are awarded only
   when progress reaches `required_count`. Make corrections in `Items`, `Teams`, or `Claims`;
   do not type over the leaderboard formulas.

When updating a sheet from the original one-item schema, run `upgradeGroupedTiles` once before
deploying. It adds and backfills tile and threshold columns without deleting claims. Existing
rows become 1-of-1 tiles and existing claim rows become completed contributions. The threshold
API is a coordinated cutover: do this between events or during a maintenance window, then deploy
the script and update every player to the threshold-capable plugin build.
Use `refreshLeaderboard` for later formula-only updates.

Keep the editable spreadsheet organizer-only. The `Config` tab contains the participant token,
organizer-only admin token, and optional backend webhook. Hiding the tab is cosmetic and does
not make those values safe from people who can view or edit the Sheet.

### 2. Deploy

**Deploy → New deployment → Web app**, *Execute as* **Me**, *Who has access* **Anyone**.
Copy the `/exec` URL.

> *Who has access: Anyone* is required. With "Anyone with a Google account", Apps Script
> returns an HTML login page instead of JSON and every claim silently fails. The plugin logs
> a specific warning when it sees this, but it's much easier to catch with the smoke tests.

If this Sheet received claims from a pre-release version, follow
[`backend/README.md` → Upgrading an existing sheet](backend/README.md#upgrading-an-existing-sheet)
before deploying it again. That scrubs legacy Audit payloads and retired account hashes; rotate
both tokens afterward.

### 3. Smoke-test before telling anyone

Run the `curl` checks in [`backend/README.md`](backend/README.md) — at minimum `ping`, `board`,
one claim, and the same claim replayed with the same `claimId`. Five minutes here saves an
event's worth of confusion.

### 4. Hand out

Send participants exactly two things: the **`/exec` URL** and the **`token`** from `Config`.
Keep `admin_token`, the Sheet URL, and any backend webhook to yourself.

### During the event

- `Claims` is the live board state. One row = one accepted distinct item contribution. A 3-of-5
  tile can therefore have up to three active rows for one team; quantities never create extra
  progress.
- `Leaderboard` is the organizer/spectator view. The same tile can show claimed for one team
  and partial or open for another; its summary and progress matrix update automatically from
  `Claims`.
- `Audit` logs claim and unclaim attempts including rejects, using an allowlist that excludes
  event tokens, admin tokens, and webhook URLs. This is where you look when someone says
  "it didn't count".
- To remove one credited item and reduce progress:
  ```bash
  curl -sL -X POST "$URL" -H 'Content-Type: application/json' \
    -d '{"action":"unclaim","admin_token":"'"$ADMIN_TOKEN"'","team":"Team One","tile_id":"26807","item_id":26809}'
  ```
- To remove every contribution for a team/tile:
  ```bash
  curl -sL -X POST "$URL" -H 'Content-Type: application/json' \
    -d '{"action":"unclaim","admin_token":"'"$ADMIN_TOKEN"'","team":"Team One","tile_id":"26807"}'
  ```
  Players see the corrected progress on their next refresh (default 5 min, or the panel's
  Refresh button). Removing one item from a completed tile also removes its points until the
  threshold is reached again.

---

## Player

1. Install **Dink** and **Dink Bingo** from the Plugin Hub.
2. In **Dink** → *External Plugin Requests* → enable **Enable External Plugin Notifications**,
   and make sure a Discord webhook is set.
3. In **Dink Bingo**, paste the **Backend URL** and **Event Token**.
4. Confirm the **Loot Tracker** plugin is enabled (it is by default). See below — this matters
   more than it looks.
5. Open the bingo icon in the sidebar. If you see your team name and the tile list, you're done.
   Long tile lists scroll below the fixed team summary and Refresh button. "Not on a team"
   means your RSN isn't on the organizer's `Teams` tab. Set **Board View** to **Possible Items**
   to expand unfinished tiles into every item option your team can still contribute, or enable
   **Hide Completed Tiles** to trim completed rows from the default **Named Tiles** view.

Nothing else is needed. You don't pick your team, you don't enter item ids, and you don't have
to remember to do anything when a drop lands.

---

## What actually gets detected

**Dink Bingo does not read Dink's notifications.** It subscribes to RuneLite's own loot events
directly, and it has **no minimum value filter of any kind** — no gp threshold, no rarity
threshold, nothing. A 1 gp tile claims exactly as reliably as a 1 billion gp tile.

So the "set loot notifications to 1 gp" instruction is **not required** for bingo. It only
affects Dink's own loot posts, which are a separate feature. Set Dink's loot notifier to
whatever you like — including off — and bingo is unaffected.

### Covered

| Source | Path |
| --- | --- |
| Any NPC kill | `ServerNpcLoot` / `NpcLootReceived` |
| Raids, Barrows, chests, clue caskets, Wintertodt/Tempoross/GOTR rewards, implings, bird nests, shade chests, Unsired, BA gambles | `LootReceived` |
| Pickpocketing | `LootReceived` |
| Loot taken from other players | `PlayerLootReceived` — **off by default**, enable *Include PK Loot* |
| New collection log entries | the `New item added to your collection log` chat message |

Noted, placeholder and equipped item variants are canonicalized before matching, so a tile set
to id 4151 still fires if the drop arrives as a noted whip.

### Requires the Loot Tracker plugin

NPC kills come from a core RuneLite service and work regardless. **Everything else in that
table — raids, chests, clue caskets, minigame rewards, pickpocketing — is emitted by the
Loot Tracker plugin.** If a participant has Loot Tracker disabled, those drops are invisible to
Dink Bingo (and to Dink). It's enabled by default, so this only bites people who turned it off,
but it's worth a one-line check in your event announcement.

### Not covered

- **Items you didn't loot**: GE and shop purchases, trades, crafted or made items, quest
  rewards, skilling outputs that aren't loot-tracked.
- **Pets.** Pet drops are not loot events. A pet is only caught through the collection log
  path, which means it won't fire for a player who already has that pet logged. Put pets on
  the board only if you're willing to claim them by hand.
- **Drops that land while the plugin can't reach the backend.** Claims retry with backoff, but
  a client that closes mid-retry loses that attempt. The tile stays open — re-drop it or
  claim it manually.
- **Guaranteed Discord delivery after a client crash.** The Sheet claim is atomic and
  authoritative. If RuneLite exits after the Sheet commits but before Dink receives the
  request, the claim remains recorded but the screenshot announcement can be missing.

### On "some players will already have the log"

Right, and that's exactly why detection doesn't rely on the collection log. The collection log
message only fires the *first* time an account obtains an item, and only when the in-game
collection log chat notification is turned on. It's a bonus path that catches things like a
pet or an item obtained outside a normal loot event — the loot events are what carry the event.

If you want to prove this to yourself before the event, put a common drop on the board (a
Grimy guam, say), kill something that drops it, and watch the tile close.

---

## Troubleshooting

| Symptom | Cause |
| --- | --- |
| Panel says "Not configured" | Backend URL is blank. No network calls are made until it's set. |
| Panel says "Not on a team" | RSN missing from the `Teams` tab. |
| Nothing happens on a drop, no chat line | Backend unreachable, or the item id on the board doesn't match the real drop. Check `Audit`. |
| Chat says progress/claimed, nothing in Discord | Dink's *Enable External Plugin Notifications* is off, or no webhook is set. |
| Every claim fails silently | Deployment is not *Who has access: Anyone*. The client log names this explicitly. |
| Contribution credited to the wrong team | Use item-level or whole-tile admin unclaim above, then fix the `Teams` tab. |

### Screenshot verification overlay

Before the event, optionally enable Dink Bingo's **Show Verification Overlay** and enter the
organizer-provided **Bingo Verification Code**. The overlay stays visible until disabled and
shows the player's current local date, time, time zone, and event code. Update the code for
each bingo; a blank code is visibly labeled `Not configured`.
