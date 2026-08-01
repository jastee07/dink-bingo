# AGENTS.md

## Project purpose

Bingo with Dink Notifications is a Java 11 RuneLite Plugin Hub plugin. It detects bingo-board loot from
RuneLite events, asks a deployed Google Apps Script to atomically claim the tile, and posts
a `PluginMessage("dink", "notify", ...)` only when the backend returns `claimed`. Dink owns
the Discord webhook POST and screenshot capture.

Do not turn this into a Dink fork or post directly to Discord from the plugin. Keeping the
sheet authoritative prevents duplicate claims; keeping screenshot delivery in Dink preserves
the normal Dink configuration and capture behavior.

## Repository map

- `src/main/java/dinkbingo/BingoPlugin.java`: RuneLite subscriptions and lifecycle wiring.
- `BingoDetector.java`: canonicalization, board matching, and in-flight/resolved dedupe.
- `BingoClient.java`: Apps Script board/claim HTTP client and retry behavior.
- `BingoAnnouncer.java`: Dink external-plugin payload; this is the screenshot/Discord boundary.
- `BingoPanel.java`: sidebar board and refresh control.
- `BingoConfig.java`: user-facing connection, detection, and announcement settings.
- `BingoResponses.java`, `BingoBoard.java`, `BingoItem.java`: wire and view models.
- `src/test/java/dinkbingo/`: focused JUnit/Mockito tests plus the side-loaded client main.
- `backend/Code.gs`: Apps Script backend and spreadsheet schema.
- `backend/README.md`: organizer deployment and destructive claim smoke tests.
- `SETUP.md`: event/player runbook and coverage limits.
- `run-dev.sh`: builds and launches a side-loaded RuneLite client.
- `jagex-dev.sh`: launches the dev client after RuneLite's official saved-credential setup.

`/Users/jake/src/DinkPlugin` is a useful adjacent checkout for verifying the current external
message contract, but it is a separate project. Do not edit it unless the task explicitly
requires a Dink change.

## Safe workflow

Run from the repository root:

```bash
./gradlew test
```

The build intentionally excludes `mavenLocal()`; do not add it. A stale local RuneLite module
can shadow Maven Central and break native dependency resolution.

For manual client testing:

```bash
./run-dev.sh
```

For a Jagex Account, follow the credential setup printed by:

```bash
./jagex-dev.sh
```

Never ask for a legacy RuneScape username/password. Never print, inspect, copy, or commit
`~/.runelite/credentials.properties`; remove it when development is finished.

## Test and debug boundaries

Unit tests must use mocks or `MockWebServer`; they must not call a deployed Apps Script or a
real Discord webhook. Add regression coverage for changes to claim status handling, replay
suppression, retry identity, URL types, and the Dink payload.

Use a reversible test tile and team when manually verifying screenshot/webhook integration.
Before making the test claim:

1. Use a throwaway Discord channel/webhook when possible.
2. Enable Dink's **External Plugin Requests > Enable External Plugin Notifications**.
3. Leave Dink's **External Plugin Requests > Send Image** at `Requested` or `Always`.
4. Configure either this plugin's override, Dink's external override, or Dink's primary URL.
5. Be logged in if screenshot capture is being verified.

The curl examples in `backend/README.md` are not read-only except `ping` and `board`.
Claim, replay, concurrency, and unclaim requests mutate the deployed sheet and may trigger a
backend Discord post when `announce_from_backend=true`. Do not run them against a live event
without explicit authorization and a reversible test tile/team.

## Invariants to preserve

- Announce only a `claimed` response; never announce `duplicate` or failures. A replay returned
  to the original in-flight client operation is announced because the earlier HTTP response was
  lost and therefore never reached Dink.
- Reuse the same `claimId` across retries.
- Keep Apps Script mutations under `LockService.getScriptLock()`.
- Never store event tokens, admin tokens, webhook URLs, or account hashes in Claims or Audit.
- Canonicalize item IDs before matching.
- Preserve the raw RuneLite loot-event paths and per-item dedupe; Dink's own loot thresholds
  must not control bingo detection.
- Keep secrets out of logs, tests, documentation examples, and commits.
- Update `README.md`, `SETUP.md`, backend docs, and tests when behavior or operator steps change.

## Verification expectations

For ordinary Java changes, run `./gradlew test`. For integration changes, also side-load the
client and claim a reversible test tile with a test webhook. A successful unit test proves
the payload is posted to RuneLite's event bus; only the manual claim proves Dink accepted it,
captured an image, and Discord received the multipart webhook.
