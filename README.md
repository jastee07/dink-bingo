# Bingo with Dink Notifications

[![CI](https://github.com/jastee07/dink-bingo/actions/workflows/ci.yml/badge.svg)](https://github.com/jastee07/dink-bingo/actions/workflows/ci.yml)

A RuneLite plugin for clan bingo events. When you get a drop that's on your team's board, it
atomically claims the tile on a shared Google Sheet—even if two teammates get it at the same
moment—and asks Dink to announce it to Discord with a screenshot.

It is a **companion** to [Dink](https://github.com/pajlads/DinkPlugin), not a fork. Install
both from the Plugin Hub; Dink keeps updating independently.

```
Bingo with Dink Notifications  ──(1) POST contribution ──▶  Apps Script ──▶  Google Sheet  (source of truth)
            ◀─(2) progress / claimed / duplicate ─┘
            │
            └─(3) accepted progress or completion:
                   PluginMessage("dink","notify") ──▶ Dink ──▶ Discord
                                                       (embed + screenshot)
```

## Why the sheet decides and the client announces

The sheet is the only thing allowed to credit an item or complete a tile, so the same item
cannot count twice no matter how many clients are running. But the *announcement* is fired
from the client, because the backend can't take a screenshot of your game. You get central
deduplication and proof of each accepted contribution.

## Setup

**[SETUP.md](SETUP.md) is the runbook** — organizer steps, player steps, exactly what is and
isn't detected, and a troubleshooting table. The summary below is the short version.

### 1. The board

Follow [`backend/README.md`](backend/README.md): create a Sheet, paste
[`backend/Code.gs`](backend/Code.gs), run `setupSheet`, fill in the `Items` and `Teams` tabs,
and deploy as a web app. Keep the `/exec` URL and the `token`. The generated `Leaderboard`
tab shows each team's completed count, earned points, remaining tiles, remaining points, and a
tile-by-team progress matrix; it is a derived view and never decides whether a contribution
succeeds.

If you configure `event_start` / `event_end`, first set **File → Settings → Time zone** on the
spreadsheet. Use real Sheet date/time cells (recommended), or `yyyy-MM-dd HH:mm` text. That
spreadsheet timezone is authoritative for all players; their local timezones do not affect
claim eligibility. Both boundaries are inclusive, and the backend rejects out-of-window claims
before writing to `Claims`.

Keep the editable Sheet organizer-only. Players receive the `/exec` URL and event token, not
Sheet access. The admin token and optional backend Discord webhook stay in the organizer-owned
`Config` tab.

To allow public read-only access to the board, use **File → Share → Publish to web** and
select only the `Leaderboard` tab. Share the published URL, not the spreadsheet URL. Google
supports publishing individual tabs, and changes update automatically after a short delay.
Depending on your Google account settings, that page may be public to the web, so only use
this if showing RSNs and team results publicly is acceptable.

Verify it with the `curl` smoke tests in that README **before** configuring the plugin — most
setup problems are deployment permissions, and they're far easier to spot from a terminal.
Existing sheets must follow the
[schema and security upgrade steps](backend/README.md#upgrading-an-existing-sheet) before reuse.

### 2. Each player

1. Install **Dink** and **Bingo with Dink Notifications** from the Plugin Hub.
2. In Dink: **External Plugin Requests → Enable External Plugin Notifications** must be on,
   and a Discord webhook must be set (either Dink's *Primary Webhook URLs*, its *External
   Webhook Override*, or this plugin's own *Bingo Webhook Override*).
3. In Bingo with Dink Notifications: paste the **Backend URL** and **Event Token**.

That's it — the player's RSN is matched against the `Teams` tab, so nobody has to pick their
own team in config. The sidebar keeps the team summary and Refresh button visible while long
tile lists scroll underneath them.

## Configuration

| Setting | Default | Notes |
| --- | --- | --- |
| Backend URL | *(blank)* | The HTTPS Apps Script `/exec` URL. **No requests are made until this is set.** |
| Event Token | *(blank)* | The `token` from the sheet's `Config` tab |
| Refresh Interval | 5 min | How often the board is re-fetched so teammates' claims appear |
| Board View | Named Tiles | Swap between logical tile names and every possible item still eligible for unfinished tiles |
| Hide Completed Tiles | off | Hide tiles already completed by your team in the Named Tiles view |
| Include Collection Log | on | Also claim from `New item added to your collection log` |
| Include PK Loot | off | Whether items taken from other players can claim a tile |
| Send Screenshot | on | Asks Dink to attach proof; Dink's own *Send Image* policy can still override |
| Show Verification Overlay | off | Keeps the current local date, time, time zone, and bingo code visible in the game view |
| Bingo Verification Code | *(blank)* | Organizer-provided code displayed in the overlay; update it for each bingo |
| Bingo Webhook Override | *(blank)* | Send claims to a specific Discord webhook instead of Dink's default |
| Progress Message | see config | Used for every distinct item accepted before completion |
| Completion Message | see config | Used when a contribution reaches the tile threshold |
| Message tokens | — | `%USERNAME%`, `%ITEM%`, `%TILE%`, `%TEAM%`, `%PROGRESS%`, `%REQUIRED%`, `%REMAINING%`, `%SOURCE%` |
| Game Chat Confirmation | on | Prints the claim result in game so you know it registered |

### Screenshot verification overlay

Enable **Show Verification Overlay** and enter the organizer-provided **Bingo Verification
Code** before the event. The overlay remains visible in the game view and shows the player's
current local date, time, time zone, and event code, so Dink includes them in screenshots.
Change the code whenever the organizer starts a new bingo. A blank code is shown as
`Not configured` rather than being silently omitted.

## How detection works

Loot is read from RuneLite's own events (`ServerNpcLoot`, `NpcLootReceived`,
`PlayerLootReceived`, `LootReceived`) rather than from Dink's notifications. Dink's broadcasts
are filtered by its minimum-value and rarity settings, so a cheap bingo item would silently
never fire — reading the raw events avoids that trap entirely. **There is no value or rarity
filter anywhere in this plugin.**

`LootReceived` is accepted for every record type except `PLAYER` (which is gated behind
*Include PK Loot* and arrives via `PlayerLootReceived`). That overlaps with the NPC events,
but upstream has moved individual bosses between these events over time, and the per-item
dedupe below makes the overlap free. See [SETUP.md](SETUP.md) for the full coverage table,
including the dependency on the Loot Tracker plugin for non-NPC loot.

Item ids are canonicalized (`ItemManager#canonicalize`) before matching, so noted, placeholder
and equipped variants all resolve to the id on your board.

Two safeguards keep the audit log clean: an in-flight set (RuneLite fires two events for some
NPC kills) and resolved item/tile sets. A partial K-of-N response resolves only the contributed
item, so a different accepted option can still advance the tile; completion resolves the whole
tile. Each board refresh reconciles that local state with the authoritative Sheet, including
organizer unclaims.

## Building

```bash
./gradlew test
```

```bash
./gradlew run
```

`run` launches RuneLite with the plugin side-loaded. Install Dink in that client too, and
point the webhook at a throwaway Discord channel while testing.

### Develop with a Jagex Account

Jagex Accounts do not use the legacy login form. Follow RuneLite's development flow. On
macOS, run the configuration command from the ordinary **Terminal** app, not a sandboxed
Codex task terminal. The helper selects Jagex Launcher's bundled RuneLite copy when present;
configuring `/Applications/RuneLite.app` does not affect newer Jagex Launcher installations
that launch their own copy.

1. Run `./jagex-dev.sh --configure`.
2. Add `--insecure-write-credentials` under **Client arguments** and save.
3. Launch RuneLite once through the Jagex Launcher, then close that client.
4. Run `./jagex-dev.sh`.

RuneLite writes a temporary `~/.runelite/credentials.properties` which the side-loaded client
uses automatically. Treat it like a password: do not print, copy, or commit it. Remove the
client argument and delete that file when development is finished. If it may have leaked,
use **End sessions** in RuneScape account settings.

### End-to-end checklist

1. On a 2-of-3 tile, the first distinct option returns `progress` at 1/2, creates one `Claims`
   row, and sends a progress screenshot post.
2. Repeating that same item returns `duplicate` and sends no post.
3. A second distinct option returns `claimed` at 2/2, creates the second contribution row,
   awards the tile's points, and sends a completion screenshot post.
4. A later unused option returns `duplicate` because the tile is complete.
5. Interrupt a response after the Sheet commits → the same `claimId` replay returns the
   original result and the running client sends one Dink request.
6. Admin unclaim one item → progress decreases; unclaim without `item_id` → all contributions
   are removed and the tile fully reopens after refresh.

### A note on `mavenLocal()`

This build deliberately omits `mavenLocal()`. If a stale copy of a module is cached in
`~/.m2`, Gradle treats it as authoritative and will not fall back to Maven Central for
artifacts that module has since gained — which surfaces as
`Could not find lwjgl-3.3.2-natives-linux-arm64.jar` during `test`. Adding `mavenLocal()`
back will reintroduce that failure on any machine with an old cache.

## Privacy and trust model

The event token is a shared participant credential. Every player needs it; the `/exec` URL
alone does not authorize board access or claims. It stops drive-by posts but not a determined
participant. The mitigations are visibility and reversibility:

- Board lookup sends the player's RSN and event token to the organizer's Apps Script.
- Contributions additionally send item id/name, quantity, loot source, and a random claim id.
- No RuneLite account hash is collected.
- `Audit` stores only allowlisted operational fields; tokens and webhook URLs are redacted.
- `admin_token` is organizer-only and enables item-level or whole-tile unclaim.
- A backend `discord_webhook` remains in the organizer-owned Sheet and is never returned by
  the API. A player's Dink webhook remains in their secret RuneLite configuration.

For a friendly clan event this is the right trade-off. If you need more, move the backend off
Apps Script and issue per-player tokens.

See [SECURITY.md](SECURITY.md) for private vulnerability reporting and credential handling.

## License

[BSD-2-Clause](LICENSE)
