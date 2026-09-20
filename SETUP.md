# Running an event

Two roles: the **organizer** builds the board once, each **player** pastes two values into
config. Total player-side effort is about a minute.

---

## Organizer

### 1. Build the sheet

1. Create a new Google Sheet.
2. **Extensions → Apps Script**, delete the placeholder, paste [`backend/Code.gs`](backend/Code.gs), save.
3. Run `setupSheet` once from the editor and approve the permission prompt. It creates the
   `Items`, `Teams`, `Claims`, `Attempts`, `Audit`, `Config`, and `Leaderboard` tabs and generates a
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
   lives. RSNs are matched case-insensitively with `_` treated as a space and runs of
   separators collapsed, so `Zezima`, `zez ima`, and `Zez__Ima` are all the same player.

   The tab is validated on every board and claim request, and ambiguous configuration fails
   visibly instead of silently picking a row:

   - Two rows for the same player — including spelling variants like `Jake_Steele` and
     `jake steele` — are rejected, naming both row numbers. Previously the first row won.
   - A row with an `rsn` but no `team`, or a `team` but no `rsn`, is rejected and names the
     row. A half-filled row used to look exactly like a player who was never added.
   - Fully blank rows are ignored, so trailing spreadsheet padding is fine.

   Use the exact same spelling and capitalization for every member of a team; each distinct
   team name gets its own claim state for every logical tile. Whatever spelling you use for a
   player's `rsn` is the one shown in the sidebar and on the `Leaderboard` when they
   contribute, so write it the way you want it to read.
6. **Event time zone** — in **File → Settings**, set the spreadsheet **Time zone** to the
   organizer's intended event timezone. This single setting is authoritative for every player.
7. **`Config` tab** — optionally set `event_start` / `event_end` as real Sheet date/time cells
   (recommended), or `yyyy-MM-dd HH:mm` text interpreted in the spreadsheet timezone. Start and
   end are inclusive; claims outside the window are rejected with `event_closed`. Invalid or
   reversed boundaries fail closed. The backend never posts to Discord; every announcement
   comes from a player's own Dink install, which is what attaches the screenshot.
8. **`Leaderboard` tab** — read-only event view. It shows K-of-N progress, completed tiles,
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

1. Install **Dink** and **Bingo with Dink Notifications** from the Plugin Hub.
2. In **Dink** → *External Plugin Requests* → enable **Enable External Plugin Notifications**,
   and make sure a Discord webhook is set.
3. In **Bingo with Dink Notifications**, paste the **Backend URL** and **Event Token**.
4. Confirm the **Loot Tracker** plugin is enabled (it is by default). See below — this matters
   more than it looks.
5. Press **System check** at the bottom of the sidebar. It checks every link in the chain at
   once — backend, token, your name on the `Teams` tab, whether the event is open, and whether
   Dink and Loot Tracker are running — and names a fix for anything that is wrong. See
   *Checking the whole setup* below.
6. Optionally press **Test Dink** at the bottom of the sidebar and confirm the prompt. This is
   the only way to check Dink delivery before a real drop — see *Verifying Dink before the
   event* below.
7. Open the bingo icon in the sidebar. If you see your team name and the tile list, you're done.
   Long tile lists scroll below the fixed team summary and Refresh button. "Not on a team"
   means your RSN isn't on the organizer's `Teams` tab. Set **Board View** to **Possible Items**
   to expand unfinished tiles into every item option your team can still contribute. Completed
   tiles, and items that already counted, stay visible struck through in both views until you
   enable **Hide Completed Tiles**.
8. On a large board, open the **Filters** strip under the team summary to search by tile or
   item name, show only open, in-progress, or completed tiles, sort by name, points, progress,
   or completion, or leave only what can still be claimed. **Clear filters** puts it all back.
   These controls change the rows on screen and nothing else: a tile filtered out of the list
   is still claimed normally when it drops. They stay put across refreshes, and reset when the
   organizer moves you to a different backend or token.

Nothing else is needed. You don't pick your team, you don't enter item ids, and you don't have
to remember to do anything when a drop lands.

### Checking the whole setup

Start here when something is not working. A bingo setup is spread across RuneLite, this plugin,
Dink, the Apps Script deployment, and the organizer's sheet, and a board that loads only proves
one link in that chain.

**System check** at the bottom of the sidebar replaces the board with a readiness report:

| Row | Ready means | Common failure |
| --- | --- | --- |
| Backend URL | Set, parses, and uses HTTPS | Blank, mistyped, or an `http://` link |
| Backend | The round trip works | No response at all — this one really is network or URL |
| Event token | The backend accepted it | The organizer rotated `token` on the `Config` tab |
| Backend version | The deployment understands this client | The Apps Script is on an old copy of `Code.gs` and needs re-deploying |
| RuneScape name | The name the backend is asked about | Not logged in yet |
| Team | Resolved from the `Teams` tab | Your name is missing from it, or spelled differently |
| Event | Open | Closed, so no drop will be claimed |
| Board | Loaded and live | On screen but no longer refreshing |
| Claim detection | Drops are being submitted | Suspended after the backend refused a refresh |
| Dink | Installed and switched on | Missing or off — tiles still count, but nothing is announced |
| Loot Tracker | Installed and switched on | Off, so chest and casket drops are not seen at all |

Every warning and problem carries a one-line fix, and the headline sits on the button itself, so
`System check — 1 problem` is visible without opening it. A check that could not run reads as
*not checked*, never as one that passed.

It reads your setup and nothing else: no tile is claimed, no `Claims` or `Audit` row is written,
and the only request it makes is the same board fetch **Refresh** makes. The configured Backend
URL and Event Token are never shown, so the report is safe to screenshot into a clan chat.

A ready Dink row is still not proof of Discord delivery — nothing acknowledges a Dink message.
That is what **Test Dink** is for.

### Verifying Dink before the event

A claim landing on the sheet proves nothing about Discord. The plugin hands the announcement
to Dink and Dink never answers, so a board that loads and tiles that close can sit alongside a
Dink that is not installed, has *Enable External Plugin Notifications* off, has no webhook, or
a **Bingo Webhook Override** that is not a valid HTTPS url. The first real drop is a bad time
to find out.

The **Test Dink** button at the bottom of the sidebar posts a notification that is clearly
labelled a test:

- It uses the same `dink`/`notify` external message, the same webhook selection, and the same
  **Send Screenshot** setting as a real announcement, so a test that arrives with an image
  proves the capture path too.
- It names no item, tile or team, so it cannot be passed off as a drop.
- It never calls the backend, so it creates no `Claims` or `Audit` row and changes no tile. It
  works with no team, a closed event, or no Backend URL at all.
- It asks for confirmation first and allows one test every 30 seconds, because the message
  lands in the event's Discord channel.

The chat line stops at *handed to Dink* on purpose. **Seeing the message in Discord is the
verification** — nothing else confirms delivery. Be logged in if you want to check the
screenshot, and point the override at a throwaway channel if you would rather not post in the
event's own.

---

## What actually gets detected

**Bingo with Dink Notifications does not read Dink's notifications.** It subscribes to RuneLite's own loot events
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
Bingo with Dink Notifications (and to Dink). It's enabled by default, so this only bites people who turned it off,
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

Press **System check** at the bottom of the sidebar first: it names the failing link and the fix
for it, which is faster than matching a symptom below. The table stays as the reference.

| Symptom | Cause |
| --- | --- |
| **System check** shows a problem | Each row carries its own fix. Work down from the top — the first failing row is the one to fix, since the rows below it are checking things that depend on it. |
| Panel says "Not configured" | Backend URL is blank. No network calls are made until it's set. |
| Panel says "Not on a team" | RSN missing from the `Teams` tab. |
| Panel says "Backend error: Teams row N ..." | That `Teams` row has an `rsn` with no `team`, or a `team` with no `rsn`. Fill it in or clear it. |
| Panel says "Backend error: Teams rows N and M ..." | Two rows are the same player once case and `_`/space are normalized. Delete one. |
| Panel says "Event token rejected" | The plugin's **Event Token** does not match `token` on the `Config` tab. |
| Status line shows "Last updated HH:mm — refresh failed" | The board on screen is the last one that loaded; a later refresh could not reach the backend. Drops are still being claimed. Press Refresh, or wait for the next automatic one. |
| Header says "— not live", status says "Not claiming drops" | The backend answered and refused the last refresh, so the rows on screen are the last good board and no drops are being submitted. Fix the named reason and press Refresh; a successful refresh clears it and resumes claiming. |
| Panel says "Backend error: ..." | The backend refused the fetch and named the reason: a missing sheet tab, an `Items` row it cannot read, or a bad `event_start`/`event_end`. The full reason is in the client log. |
| Panel says "Check your connection" | The request never reached the backend. This one really is network or URL. |
| Nothing happens on a drop, no chat line | The item id on the board doesn't match the real drop, or **Chat message on claim** is off. Check `Audit`. A backend that cannot be reached now says so in chat. |
| Chat says progress/claimed, nothing in Discord | Dink's *Enable External Plugin Notifications* is off, or no webhook is set. Press **Test Dink** to confirm the handoff without waiting for another drop. |
| **Test Dink** says it was sent, nothing in Discord | The message reached Dink or was dropped by it, and Dink acknowledges neither. Check Dink is installed and enabled, *Enable External Plugin Notifications* is on, a webhook is set, and any **Bingo Webhook Override** is a valid `https://` url — a non-HTTPS override is ignored. |
| **Test Dink** arrives without a screenshot | **Send Screenshot** is off, Dink's *External Plugin Requests > Send Image* is set to `Never`, or you are not logged in. |
| **Test Dink** button is greyed out | A test was sent in the last 30 seconds. |
| Every claim fails silently | Deployment is not *Who has access: Anyone*. The client log names this explicitly. |
| Contribution credited to the wrong team | Use item-level or whole-tile admin unclaim above, then fix the `Teams` tab. |
| Chat says "your event token was rejected" | The plugin's **Event Token** does not match `token` on the `Config` tab. The drop was not recorded; ask the organizer to reclaim it once the token is fixed. |
| Chat says "the backend stayed busy" | Every retry hit the script lock. Rare outside a heavy drop burst; tell the organizer if it repeats. |
| Chat says "that claim id was already used" | One claim id was reused for a different player or item, whether the original claim succeeded or was rejected. Case and underscore/space differences in the same RSN are allowed. Report repeated conflicts. |
| Chat says "couldn't reach the backend" | No response arrived at all, so nothing was recorded. The same item is submitted again if you get another. |
| Panel says "Backend error: Claims row N ..." | A `Claims` row credits a tile or item that `Items` no longer lists, usually a manual edit or a mid-event rename. The `Leaderboard` tab's **Claims integrity** cell shows the same thing. Restore the tile/option in `Items`, or remove the row with admin unclaim. Board loads and claims both fail until it is fixed. |

### Screenshot verification overlay

Before the event, optionally enable **Show Verification Overlay** in Bingo with Dink Notifications and enter the
organizer-provided **Bingo Verification Code**. The overlay stays visible until disabled and
shows the player's current local date, time, time zone, and event code. Update the code for
each bingo; a blank code is visibly labeled `Not configured`.
