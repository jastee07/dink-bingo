const assert = require("assert");
const fs = require("fs");
const vm = require("vm");

const sheets = {
  Items: [
    ["tile_id", "tile_name", "item_id", "item_name", "points", "required_count", "notes"],
    ["rare-drop", "Any rare drop", 4151, "Abyssal whip", 3, 1, ""],
    ["rare-drop", "Any rare drop", 21034, "Dexterous prayer scroll", 3, 1, ""],
    ["11832", "Bandos chestplate", 11832, "Bandos chestplate", 1, 1, ""]
  ],
  Teams: [
    ["rsn", "team"],
    ["jake", "Team One"]
  ],
  Claims: [
    ["team", "tile_id", "tile_name", "item_id", "item_name", "rsn", "claimed_at",
      "claim_id", "source", "progress_after", "completed_tile"]
  ],
  Audit: [
    ["ts", "rsn", "item_id", "result", "notes", "raw_payload", "tile_id"]
  ],
  Attempts: [
    ["claim_id", "rsn", "team", "item_id", "status", "tile_id", "tile_name", "item_name",
      "complete", "recorded_at"]
  ],
  Config: [
    ["key", "value"],
    ["token", "participant-secret"],
    ["admin_token", "organizer-secret"]
  ]
};

// Tracks which tabs are read while the script lock is held. Every getDataRange() call is a
// slow round trip, so anything recorded here lengthens the hold time for every other claim.
let lockDepth = 0;
const sheetReadsUnderLock = [];

// Rejected-auth diagnostics live here instead of the Audit tab; see noteRejectedAuth.
const scriptCache = {};

function fakeSheet(name) {
  return {
    getDataRange() {
      if (lockDepth > 0) sheetReadsUnderLock.push(name);
      return { getValues: () => sheets[name].map(row => row.slice()) };
    },
    appendRow(row) {
      sheets[name].push(row.slice());
    },
    deleteRow(rowNumber) {
      sheets[name].splice(rowNumber - 1, 1);
    }
  };
}

const context = {
  console,
  ContentService: {
    MimeType: { JSON: "application/json" },
    createTextOutput(text) {
      return {
        text,
        setMimeType() {
          return this;
        }
      };
    }
  },
  SpreadsheetApp: {
    getActiveSpreadsheet() {
      return {
        getSheetByName: name => sheets[name] ? fakeSheet(name) : null,
        getSpreadsheetTimeZone: () => "America/New_York"
      };
    },
    flush() {}
  },
  LockService: {
    getScriptLock() {
      let held = false;
      return {
        tryLock() {
          if (!held) {
            held = true;
            lockDepth++;
          }
          return true;
        },
        hasLock() {
          return held;
        },
        releaseLock() {
          if (held) {
            held = false;
            lockDepth--;
          }
        }
      };
    }
  },
  UrlFetchApp: {
    fetch(url) {
      throw new Error(
        "the backend must not make outbound requests; see the announcement boundary in AGENTS.md"
      );
    }
  },
  CacheService: {
    getScriptCache() {
      return {
        get: key => (key in scriptCache ? scriptCache[key] : null),
        put(key, value) {
          scriptCache[key] = String(value);
        }
      };
    }
  },
  Utilities: {
    getUuid: () => "generated-uuid",
    parseDate(text, timeZone, format) {
      assert.strictEqual(timeZone, "America/New_York");
      assert.strictEqual(format, "yyyy-MM-dd HH:mm");
      const match = /^(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2})$/.exec(text);
      if (!match) throw new Error("invalid test date");
      // These tests use winter dates, when America/New_York is UTC-05:00.
      return new Date(`${match[1]}-${match[2]}-${match[3]}T${match[4]}:${match[5]}:00-05:00`);
    }
  }
};
vm.createContext(context);
const code = fs.readFileSync("backend/Code.gs", "utf8");
vm.runInContext(code, context);

function output(result) {
  return JSON.parse(result.text);
}

function generatedFormula(cell) {
  const pattern = new RegExp(
    `sh\\.getRange\\('${cell}'\\)\\.setFormula\\(\\n([\\s\\S]*?)\\n  \\);`
  );
  const match = code.match(pattern);
  assert(match, `missing generated formula for ${cell}`);
  return vm.runInNewContext(match[1].trim());
}

function assertBalancedFormula(cell) {
  const formula = generatedFormula(cell);
  let depth = 0;
  for (const character of formula) {
    if (character === "(") depth++;
    if (character === ")") depth--;
    assert(depth >= 0, `${cell} closes a parenthesis before it opens`);
  }
  assert.strictEqual(depth, 0, `${cell} has unbalanced parentheses: ${formula}`);
}

// quantity is no longer part of the claim contract; an older client still sending it must not
// have it written into the Audit tab's raw_payload blob.
const sanitized = context.sanitizeAuditPayload({
  action: "unclaim",
  quantity: 20,
  token: "participant-secret",
  admin_token: "organizer-secret",
  discord_webhook: "https://discord.invalid/webhook",
  accountHash: 123456789,
  rsn: "Jake",
  itemId: 4151,
  claimId: "claim-123",
  team: "Team One"
});

assert.deepStrictEqual(JSON.parse(JSON.stringify(sanitized)), {
  action: "unclaim",
  rsn: "Jake",
  itemId: 4151,
  claimId: "claim-123",
  team: "Team One"
});
assert.strictEqual(context.tokenValid({}, "anything"), false);
assert.strictEqual(context.tokenValid({token: "expected"}, "expected"), true);
assert.strictEqual(context.tokenValid({token: "expected"}, "wrong"), false);

const eventStart = new Date("2026-01-10T15:00:00Z");
const eventEnd = new Date("2026-01-10T17:00:00Z");
assert.strictEqual(context.eventOpen(
  {event_start: eventStart, event_end: eventEnd},
  new Date("2026-01-10T15:00:00Z")
), true, "the start instant is inclusive");
assert.strictEqual(context.eventOpen(
  {event_start: eventStart, event_end: eventEnd},
  new Date("2026-01-10T17:00:00Z")
), true, "the end instant is inclusive");
assert.strictEqual(context.eventOpen(
  {event_start: eventStart, event_end: eventEnd},
  new Date("2026-01-10T17:00:00.001Z")
), false, "claims after the end instant are closed");
assert.strictEqual(context.eventOpen(
  {event_start: "2026-01-10 10:00", event_end: "2026-01-10 12:00"},
  new Date("2026-01-10T16:00:00Z")
), true, "text boundaries use the spreadsheet timezone");
assert.throws(
  () => context.eventOpen({event_start: "01/10/2026 10:00"}, eventStart),
  /must be a Sheet date\/time/,
  "ambiguous text dates fail closed"
);
assert.throws(
  () => context.eventOpen({event_start: eventEnd, event_end: eventStart}, eventStart),
  /event_start must be before event_end/
);

sheets.Config.push(["event_start", new Date(Date.now() + 60 * 60 * 1000)]);
const claimsBeforeClosedAttempt = sheets.Claims.length;
const closed = output(context.handleClaim({
  token: "participant-secret",
  rsn: "Jake",
  itemId: 4151,
  itemName: "Abyssal whip",
  claimId: "before-event"
}));
assert.strictEqual(closed.status, "event_closed");
assert.strictEqual(
  sheets.Claims.length,
  claimsBeforeClosedAttempt,
  "an out-of-window drop must not create a Claims row"
);
sheets.Config.pop();

const first = output(context.handleClaim({
  token: "participant-secret",
  rsn: "Jake",
  itemId: 4151,
  itemName: "Abyssal whip",
  source: "Abyssal demon",
  claimId: "group-claim-1"
}));
assert.strictEqual(first.status, "claimed");
assert.strictEqual(first.tileId, "rare-drop");
assert.strictEqual(first.tileName, "Any rare drop");
assert.strictEqual(first.itemId, 4151);
assert.strictEqual(first.remaining, 1);
assert.strictEqual(first.total, 2);
assert.strictEqual(sheets.Claims.length, 2);

const alternative = output(context.handleClaim({
  token: "participant-secret",
  rsn: "Jake",
  itemId: 21034,
  itemName: "Dexterous prayer scroll",
  claimId: "group-claim-2"
}));
assert.strictEqual(alternative.status, "duplicate");
assert.strictEqual(alternative.tileId, "rare-drop");
assert.strictEqual(alternative.itemId, 4151, "duplicate response identifies the winning item");
assert.strictEqual(sheets.Claims.length, 2, "alternatives create only one logical claim");

const replay = output(context.handleClaim({
  token: "participant-secret",
  rsn: "Jake",
  itemId: 4151,
  claimId: "group-claim-1"
}));
assert.strictEqual(replay.status, "claimed");
assert.strictEqual(replay.replay, true);
assert.strictEqual(replay.itemId, 4151);
assert.strictEqual(sheets.Claims.length, 2);

// Only the mutable Claims tab may be read while the lock is held. Items and Teams are static
// for the duration of an event, so reading them under the lock would inflate the hold time
// for every concurrent claim without buying any consistency.
sheetReadsUnderLock.length = 0;
const lockScoped = output(context.handleClaim({
  token: "participant-secret",
  rsn: "Jake",
  itemId: 11832,
  itemName: "Bandos chestplate",
  claimId: "lock-scope-1"
}));
assert.strictEqual(lockScoped.status, "claimed");
assert.deepStrictEqual(
  Array.from(new Set(sheetReadsUnderLock)).sort(),
  ["Attempts", "Claims"],
  "only mutable state may be read while the script lock is held"
);
assert.strictEqual(lockDepth, 0, "handleClaim must release the lock it took");

const undoLockScoped = output(context.handleUnclaim({
  admin_token: "organizer-secret",
  team: "Team One",
  tile_id: "11832"
}));
assert.strictEqual(undoLockScoped.status, "unclaimed");

sheetReadsUnderLock.length = 0;
const strangerClaim = output(context.handleClaim({
  token: "participant-secret",
  rsn: "Not On Any Team",
  itemId: 4151,
  itemName: "Abyssal whip",
  claimId: "stranger-1"
}));
assert.strictEqual(strangerClaim.status, "not_on_team");
assert.deepStrictEqual(
  Array.from(new Set(sheetReadsUnderLock)).sort(),
  ["Attempts", "Claims"],
  "a not_on_team rejection must not read Items or Teams under the lock"
);

sheetReadsUnderLock.length = 0;
const offBoardClaim = output(context.handleClaim({
  token: "participant-secret",
  rsn: "Jake",
  itemId: 995,
  itemName: "Coins",
  claimId: "off-board-1"
}));
assert.strictEqual(offBoardClaim.status, "not_on_board");
assert.deepStrictEqual(
  Array.from(new Set(sheetReadsUnderLock)).sort(),
  ["Attempts", "Claims"],
  "a not_on_board rejection must not read Items or Teams under the lock"
);
assert.strictEqual(lockDepth, 0, "the rejection paths release the lock");

// Rosters change mid-event when a player is subbed out. A retry of a claim the Sheet already
// committed must still replay: the client treats not_on_team as a resolved outcome, so a
// rejection here would stop the retry and lose the announcement for a real claim. This is why
// the team check stays behind the claimId replay check even though Teams is read earlier.
const teamsBeforeSubOut = sheets.Teams.map(row => row.slice());
sheets.Teams = [["rsn", "team"], ["substitute", "Team One"]];
const replayAfterSubOut = output(context.handleClaim({
  token: "participant-secret",
  rsn: "Jake",
  itemId: 4151,
  itemName: "Abyssal whip",
  claimId: "group-claim-1"
}));
assert.strictEqual(replayAfterSubOut.status, "claimed");
assert.strictEqual(replayAfterSubOut.replay, true);
assert.strictEqual(replayAfterSubOut.team, "Team One");

// A brand new claim from the subbed-out player is still rejected.
const freshAfterSubOut = output(context.handleClaim({
  token: "participant-secret",
  rsn: "Jake",
  itemId: 21034,
  itemName: "Dexterous prayer scroll",
  claimId: "after-sub-out"
}));
assert.strictEqual(freshAfterSubOut.status, "not_on_team");
sheets.Teams = teamsBeforeSubOut;
assert.strictEqual(sheets.Claims.length, 2, "the sub-out probes must not write a claim");

const board = output(context.handleBoard({
  token: "participant-secret",
  rsn: "Jake"
}));
assert.strictEqual(board.tiles.length, 2);
assert.strictEqual(board.remaining, 1);
assert.strictEqual(board.tiles[0].options.length, 2);
assert.strictEqual(board.tiles[0].claimedItem.id, 4151);

const unclaimed = output(context.handleUnclaim({
  admin_token: "organizer-secret",
  team: "Team One",
  tile_id: "rare-drop"
}));
assert.strictEqual(unclaimed.status, "unclaimed");
assert.strictEqual(sheets.Claims.length, 1);

// Real organizer example: a numeric-looking tile_id with three accepted dye items.
sheets.Items.push(
  [26807, "Abyssal dye", 26807, "Abyssal green dye", "", 2, ""],
  [26807, "Abyssal dye", 26809, "Abyssal blue dye", "", 2, ""],
  [26807, "Abyssal dye", 26811, "Abyssal red dye", "", 2, ""]
);

const dyeBoard = output(context.handleBoard({
  token: "participant-secret",
  rsn: "Jake"
}));
const dyeTile = dyeBoard.tiles.find(tile => tile.id === "26807");
assert(dyeTile, "numeric-looking tile ids are normalized to strings");
assert.strictEqual(dyeTile.name, "Abyssal dye");
assert.strictEqual(dyeTile.points, 1, "blank points default to one point");
assert.strictEqual(dyeTile.required, 2);
assert.deepStrictEqual(
  dyeTile.options.map(option => option.id),
  [26807, 26809, 26811]
);

const blueDye = output(context.handleClaim({
  token: "participant-secret",
  rsn: "Jake",
  itemId: 26809,
  itemName: "Abyssal blue dye",
  claimId: "dye-claim-1"
}));
assert.strictEqual(blueDye.status, "progress");
assert.strictEqual(blueDye.tileId, "26807");
assert.strictEqual(blueDye.tileName, "Abyssal dye");
assert.strictEqual(blueDye.itemId, 26809);
assert.strictEqual(blueDye.itemName, "Abyssal blue dye");
assert.strictEqual(blueDye.progress, 1);
assert.strictEqual(blueDye.required, 2);
assert.strictEqual(blueDye.complete, false);
assert.strictEqual(blueDye.points, 0, "partial progress does not award tile points");

const blueDyeReplay = output(context.handleClaim({
  token: "participant-secret",
  rsn: "Jake",
  itemId: 26809,
  itemName: "Abyssal blue dye",
  claimId: "dye-claim-1"
}));
assert.strictEqual(blueDyeReplay.status, "progress");
assert.strictEqual(blueDyeReplay.replay, true);
assert.strictEqual(blueDyeReplay.progress, 1, "replay returns the original progress");

const redDye = output(context.handleClaim({
  token: "participant-secret",
  rsn: "Jake",
  itemId: 26811,
  itemName: "Abyssal red dye",
  claimId: "dye-claim-2"
}));
assert.strictEqual(redDye.status, "claimed");
assert.strictEqual(redDye.tileId, "26807");
assert.strictEqual(redDye.itemId, 26811);
assert.strictEqual(redDye.progress, 2);
assert.strictEqual(redDye.complete, true);
assert.strictEqual(redDye.points, 1, "points are awarded by the completing contribution");
assert.strictEqual(sheets.Claims.length, 3, "two distinct dyes produce two contribution rows");

const blueReplayAfterCompletion = output(context.handleClaim({
  token: "participant-secret",
  rsn: "Jake",
  itemId: 26809,
  itemName: "Abyssal blue dye",
  claimId: "dye-claim-1"
}));
assert.strictEqual(blueReplayAfterCompletion.status, "progress");
assert.strictEqual(blueReplayAfterCompletion.progress, 1);
assert.strictEqual(
  blueReplayAfterCompletion.complete,
  false,
  "a replay preserves its original partial outcome even if the tile later completed"
);
assert.strictEqual(blueReplayAfterCompletion.points, 0);
assert.strictEqual(blueReplayAfterCompletion.claimedBy, null);

const greenAfterCompletion = output(context.handleClaim({
  token: "participant-secret",
  rsn: "Jake",
  itemId: 26807,
  itemName: "Abyssal green dye",
  claimId: "dye-claim-3"
}));
assert.strictEqual(greenAfterCompletion.status, "duplicate");
assert.strictEqual(greenAfterCompletion.duplicateReason, "tile_complete");
assert.strictEqual(greenAfterCompletion.itemId, 26811, "duplicate reports the completing dye");
assert.strictEqual(greenAfterCompletion.points, 0, "duplicates never award points");
assert.strictEqual(sheets.Claims.length, 3);

const removeBlue = output(context.handleUnclaim({
  admin_token: "organizer-secret",
  team: "Team One",
  tile_id: "26807",
  item_id: 26809
}));
assert.strictEqual(removeBlue.status, "unclaimed");
assert.strictEqual(removeBlue.removed, 1);
assert.strictEqual(removeBlue.progress, 1);
assert.strictEqual(removeBlue.complete, false);

const resetDyes = output(context.handleUnclaim({
  admin_token: "organizer-secret",
  team: "Team One",
  tile_id: "26807"
}));
assert.strictEqual(resetDyes.status, "unclaimed");
assert.strictEqual(resetDyes.removed, 1);
assert.strictEqual(resetDyes.progress, 0);
assert.strictEqual(sheets.Claims.length, 1);

// The same generic threshold logic also handles 3-of-5 tiles.
sheets.Items.push(
  ["five-way", "Three of five", 30001, "Option one", 5, 3, ""],
  ["five-way", "Three of five", 30002, "Option two", 5, 3, ""],
  ["five-way", "Three of five", 30003, "Option three", 5, 3, ""],
  ["five-way", "Three of five", 30004, "Option four", 5, 3, ""],
  ["five-way", "Three of five", 30005, "Option five", 5, 3, ""]
);
const threeOfFiveStatuses = [30001, 30002, 30003].map((itemId, index) =>
  output(context.handleClaim({
    token: "participant-secret",
    rsn: "Jake",
    itemId,
    itemName: `Option ${index + 1}`,
    claimId: `five-way-${index + 1}`
  }))
);
assert.deepStrictEqual(
  threeOfFiveStatuses.map(result => result.status),
  ["progress", "progress", "claimed"]
);
assert.deepStrictEqual(
  threeOfFiveStatuses.map(result => result.progress),
  [1, 2, 3]
);
assert.strictEqual(threeOfFiveStatuses[2].required, 3);
assert.strictEqual(threeOfFiveStatuses[2].complete, true);
assert.strictEqual(threeOfFiveStatuses[2].points, 5);
const fourthOfCompletedTile = output(context.handleClaim({
  token: "participant-secret",
  rsn: "Jake",
  itemId: 30004,
  itemName: "Option four",
  claimId: "five-way-4"
}));
assert.strictEqual(fourthOfCompletedTile.status, "duplicate");
assert.strictEqual(fourthOfCompletedTile.duplicateReason, "tile_complete");

assert.throws(() => context.buildTileCatalog([
  {tileId: "one", tileName: "First", itemId: 4151, itemName: "Whip", points: 1, required: 1},
  {tileId: "two", tileName: "Second", itemId: 4151, itemName: "Whip", points: 1, required: 1}
]), /appears more than once/);
assert.throws(() => context.buildTileCatalog([
  {tileId: "one", tileName: "First", itemId: 4151, itemName: "Whip", points: 1, required: 1},
  {tileId: "one", tileName: "Different", itemId: 21034, itemName: "Scroll", points: 1, required: 1}
]), /inconsistent/);
assert.throws(() => context.buildTileCatalog([
  {tileId: "too-many", tileName: "Too many", itemId: 4151, itemName: "Whip", points: 1, required: 2}
]), /requires more items/);
assert.notStrictEqual(
  context.claimKey("Team||One", "tile"),
  context.claimKey("Team", "One||tile"),
  "team and tile ids must not collide in the in-memory claim index"
);
["A5", "B5", "C5", "D5", "E5", "A26", "E25", "E26"].forEach(assertBalancedFormula);
const claimCountFormulaCells = ["B5", "C5", "D5", "E5", "E26"];
claimCountFormulaCells.forEach(cell => {
  const formula = generatedFormula(cell);
  assert(
    formula.includes("COUNTUNIQUEIFS(Claims!D2:D") &&
      formula.includes('Claims!D2:D,"<>"'),
    cell + " must count distinct nonblank contributed item ids without treating no matches as one"
  );
});
const remainingTilesFormula = generatedFormula("D5");
assert(
  remainingTilesFormula.includes("Items!F2:F") && remainingTilesFormula.includes("<needed"),
  "remaining tiles must compare progress with required_count"
);
const earnedPointsFormula = generatedFormula("C5");
assert(
  earnedPointsFormula.includes(">=needed") && earnedPointsFormula.includes("IF(points=\"\",1,points),0"),
  "points must be awarded only when the threshold is complete"
);
assert(
  generatedFormula("E26").includes('progress&\"/\"&needed'),
  "the team matrix must display partial K-of-N progress"
);

// ---------------------------------------------------------------------------
// Rejected authentication must not write to the authoritative spreadsheet (#35).
//
// The /exec deployment has to be public, so anyone who learns the URL can post invalid-token
// requests forever without knowing the event token. Auditing each attempt would let that
// traffic grow the sheet, burn write quota, and take the script lock away from real claims.
// ---------------------------------------------------------------------------

const auditRowsBeforeBadAuth = sheets.Audit.length;
const claimRowsBeforeBadAuth = sheets.Claims.length;

for (let attempt = 0; attempt < 25; attempt++) {
  const rejected = output(context.handleClaim({
    token: "not-the-event-token",
    rsn: "Jake",
    itemId: 4151,
    itemName: "Abyssal whip",
    claimId: "flood-" + attempt
  }));
  assert.strictEqual(rejected.error, "bad_token");
}

assert.strictEqual(
  sheets.Audit.length,
  auditRowsBeforeBadAuth,
  "repeated invalid-token claims must not append Audit rows"
);
assert.strictEqual(
  sheets.Claims.length,
  claimRowsBeforeBadAuth,
  "an invalid-token claim must not append a Claims row"
);
assert.strictEqual(lockDepth, 0, "a rejected claim must not leave the script lock held");

const badBoard = output(context.handleBoard({token: "not-the-event-token", rsn: "Jake"}));
assert.strictEqual(badBoard.error, "bad_token");

const badAdmin = output(context.handleUnclaim({
  admin_token: "not-the-admin-token",
  team: "Team One",
  tile_id: "rare-drop"
}));
assert.strictEqual(badAdmin.error, "bad_admin_token");
assert.strictEqual(
  sheets.Audit.length,
  auditRowsBeforeBadAuth,
  "invalid board and admin credentials must not append Audit rows either"
);
assert.strictEqual(
  sheets.Claims.length,
  claimRowsBeforeBadAuth,
  "a rejected unclaim must not delete or add Claims rows"
);

// Visibility is retained out-of-band, bounded by a coarse time bucket rather than one record
// per attempt, and never records the attempted credential.
const rejectionKeys = Object.keys(scriptCache);
assert.strictEqual(
  rejectionKeys.length,
  2,
  "rejected-auth counters are bucketed, not one entry per attempt: " + rejectionKeys.join(", ")
);
const participantKey = rejectionKeys.find(key => key.includes("participant"));
assert.strictEqual(
  scriptCache[participantKey],
  "26",
  "every rejected participant-token attempt is counted in its bucket"
);
const cachedValues = rejectionKeys.join(" ") + " " + Object.values(scriptCache).join(" ");
assert(
  !cachedValues.includes("not-the-event-token") && !cachedValues.includes("not-the-admin-token"),
  "the attempted credential must never be stored"
);

// A valid token with an unusable payload is authenticated traffic and stays auditable.
const badRequest = output(context.handleClaim({
  token: "participant-secret",
  rsn: "",
  itemId: "not-a-number"
}));
assert.strictEqual(badRequest.error, "bad_request");
assert.strictEqual(
  sheets.Audit.length,
  auditRowsBeforeBadAuth + 1,
  "authenticated rejections remain auditable"
);

// ---------------------------------------------------------------------------
// The backend never announces (#32).
//
// Announcing from Apps Script meant calling UrlFetchApp while the script lock was held, so a
// slow or rate-limited Discord endpoint could extend the hold and turn concurrent drops into
// lock_timeout retries. The announcement is the client's job regardless: only the plugin can
// screenshot the drop. The UrlFetchApp stub above throws, so any claim path that tried to
// reach the network would fail this suite rather than silently regress.
// ---------------------------------------------------------------------------

assert.strictEqual(
  typeof context.postDiscord,
  "undefined",
  "postDiscord must not exist; the backend has no announcement path"
);

// Even with the retired Config keys still present on an organizer's sheet, a claim must
// neither read them nor act on them.
sheets.Config.push(["announce_from_backend", "true"]);
sheets.Config.push(["discord_webhook", "https://discord.invalid/webhook"]);
const claimWithLegacyAnnounceConfig = output(context.handleClaim({
  token: "participant-secret",
  rsn: "Jake",
  itemId: 11832,
  itemName: "Bandos chestplate",
  claimId: "no-backend-announce"
}));
assert.strictEqual(
  claimWithLegacyAnnounceConfig.status,
  "claimed",
  "a claim still succeeds when retired announcement keys linger in Config"
);
assert.strictEqual(lockDepth, 0, "the claim released the script lock");
sheets.Config.pop();
sheets.Config.pop();

// setupSheet must not reintroduce a place to paste a webhook into the sheet.
const setupSource = code.slice(code.indexOf("function setupSheet"));
assert(
  !setupSource.includes("discord_webhook") && !setupSource.includes("announce_from_backend"),
  "setupSheet must not seed retired announcement Config keys"
);

// ---------------------------------------------------------------------------
// Teams validation and display casing (#38).
//
// resolveTeam used to return the first row whose normalized name matched, so two rows for the
// same player silently resolved to whichever came first, and a half-filled row looked exactly
// like an unlisted player. Teams is as load-bearing as Items and now fails just as visibly.
// ---------------------------------------------------------------------------

const teamsBeforeRosterTests = sheets.Teams.map(row => row.slice());
const claimsBeforeRosterTests = sheets.Claims.map(row => row.slice());

// RuneScape names ignore case, treat _ and space as equivalent, and never contain runs of
// separators. All of these are one player.
["Jake Steele", "jake_steele", "JAKE__STEELE", "  Jake   Steele  ", "jake_ steele"]
  .forEach(variant => {
    assert.strictEqual(
      context.normalizeRsn(variant),
      "jake steele",
      variant + " must normalize to a single lookup key"
    );
  });
assert.strictEqual(context.normalizeRsn("  "), null, "a blank name has no lookup key");
assert.strictEqual(context.normalizeRsn(null), null);

// Two spellings of one name on different teams is ambiguous configuration, not a silent
// first-row win. The error names both rows so the organizer can find them.
sheets.Teams = [
  ["rsn", "team"],
  ["Jake_Steele", "Team One"],
  ["jake steele", "Team Two"]
];
assert.throws(
  () => context.handleBoard({token: "participant-secret", rsn: "Jake_Steele"}),
  /Teams rows 2 and 3 are the same RuneScape name/,
  "duplicate normalized RSNs must fail with both row numbers"
);

// A name with no team reads as an unlisted player unless it is called out explicitly.
sheets.Teams = [["rsn", "team"], ["Jake_Steele", ""]];
assert.throws(
  () => context.handleBoard({token: "participant-secret", rsn: "Jake_Steele"}),
  /Teams row 2 has rsn "Jake_Steele" with no team/,
  "a nonblank rsn with a blank team must identify its row"
);

sheets.Teams = [["rsn", "team"], ["", "Team One"]];
assert.throws(
  () => context.handleBoard({token: "participant-secret", rsn: "Jake"}),
  /Teams row 2 assigns a team with no rsn/
);

// Trailing blank rows are ordinary spreadsheet padding and must not fail the event.
sheets.Teams = [["rsn", "team"], ["Jake_Steele", "Team One"], ["", ""], ["", ""]];
const paddedBoard = output(context.handleBoard({
  token: "participant-secret",
  rsn: "jake steele"
}));
assert.strictEqual(paddedBoard.status, "ok");
assert.strictEqual(
  paddedBoard.team,
  "Team One",
  "blank padding rows are skipped and lookup stays separator-insensitive"
);

// Claims record the organizer's spelling, not the normalized lookup key, so the sidebar and
// Leaderboard show the name a human recognises.
sheets.Claims = [claimsBeforeRosterTests[0].slice()];
const casedClaim = output(context.handleClaim({
  token: "participant-secret",
  rsn: "JAKE__STEELE",
  itemId: 11832,
  itemName: "Bandos chestplate",
  claimId: "display-casing-1"
}));
assert.strictEqual(casedClaim.status, "claimed");
assert.strictEqual(
  casedClaim.claimedBy,
  "Jake_Steele",
  "the claim response reports the canonical Teams spelling"
);
const rsnColumn = claimsBeforeRosterTests[0].indexOf("rsn");
assert.strictEqual(
  sheets.Claims[1][rsnColumn],
  "Jake_Steele",
  "the Claims row stores the canonical Teams spelling"
);
const casedBoard = output(context.handleBoard({
  token: "participant-secret",
  rsn: "jake_steele"
}));
const casedTile = casedBoard.tiles.find(tile => tile.id === "11832");
assert.strictEqual(casedTile.claimedBy, "Jake_Steele", "the board shows the canonical spelling");

// A Teams tab that goes ambiguous mid-event must not strand a claim the Sheet already
// committed. The client treats a rejection as resolved, so a retry that failed here would
// lose the announcement for a real claim -- the same reason not_on_team sits behind the
// replay check.
sheets.Teams = [
  ["rsn", "team"],
  ["Jake_Steele", "Team One"],
  ["jake steele", "Team Two"]
];
const replayThroughBadRoster = output(context.handleClaim({
  token: "participant-secret",
  rsn: "JAKE__STEELE",
  itemId: 11832,
  claimId: "display-casing-1"
}));
assert.strictEqual(replayThroughBadRoster.status, "claimed");
assert.strictEqual(replayThroughBadRoster.replay, true);
assert.strictEqual(
  replayThroughBadRoster.claimedBy,
  "Jake_Steele",
  "the replay reports the spelling recorded at claim time"
);
assert.strictEqual(lockDepth, 0, "the replay released the script lock");

// A brand new claim against the same ambiguous roster still fails visibly.
assert.throws(
  () => context.handleClaim({
    token: "participant-secret",
    rsn: "Jake_Steele",
    itemId: 4151,
    itemName: "Abyssal whip",
    claimId: "ambiguous-roster-1"
  }),
  /Teams rows 2 and 3 are the same RuneScape name/,
  "a fresh claim must not pick a team out of an ambiguous roster"
);
assert.strictEqual(lockDepth, 0, "the failed claim released the script lock");

sheets.Teams = teamsBeforeRosterTests;
sheets.Claims = claimsBeforeRosterTests;

// ---------------------------------------------------------------------------
// Claims rows are validated against the Items catalog (#34).
//
// Tile completion counts contributions, so a mistyped manual Claims row naming an unrelated
// item used to advance or complete a tile and award points. Claims is the documented
// correction surface, so a bad edit has to fail visibly instead of changing the result.
// ---------------------------------------------------------------------------

const claimsBeforeIntegrity = sheets.Claims.map(row => row.slice());
const claimRow = (team, tileId, tileName, itemId, itemName, claimId) =>
  [team, tileId, tileName, itemId, itemName, "Jake", new Date(), claimId, "", 1, true];

// A tile that no longer exists in Items must not silently count or silently vanish.
sheets.Claims = [claimsBeforeIntegrity[0].slice(),
  claimRow("Team One", "ghost-tile", "Deleted tile", 4151, "Abyssal whip", "orphan-1")];
assert.throws(
  () => context.handleBoard({token: "participant-secret", rsn: "Jake"}),
  /Claims row 2 credits tile_id "ghost-tile", which is not in Items/,
  "an unknown tile_id must fail with its Claims row"
);

// The whip is an option of rare-drop, not of tile 11832. Counting it toward 11832 would
// complete a tile nobody earned.
sheets.Claims = [claimsBeforeIntegrity[0].slice(),
  claimRow("Team One", "11832", "Bandos chestplate", 4151, "Abyssal whip", "wrong-tile-1")];
assert.throws(
  () => context.handleBoard({token: "participant-secret", rsn: "Jake"}),
  /Claims row 2 credits item_id 4151 to tile_id "11832", which does not list that item/,
  "an item credited to the wrong tile must fail with its Claims row"
);

// New claims fail closed while Claims integrity is invalid rather than counting the bad row.
const claimsLengthWhileInvalid = sheets.Claims.length;
assert.throws(
  () => context.handleClaim({
    token: "participant-secret",
    rsn: "Jake",
    itemId: 21034,
    itemName: "Dexterous prayer scroll",
    claimId: "while-invalid-1"
  }),
  /Claims row 2 credits item_id 4151/,
  "a new claim must not be evaluated against invalid Claims state"
);
assert.strictEqual(
  sheets.Claims.length,
  claimsLengthWhileInvalid,
  "the failed claim wrote nothing"
);
assert.strictEqual(lockDepth, 0, "the failed claim released the script lock");

// Admin unclaim is how the organizer repairs this, so it must keep working while the rows it
// is being asked to remove are exactly the ones failing validation.
const repaired = output(context.handleUnclaim({
  admin_token: "organizer-secret",
  team: "Team One",
  tile_id: "11832"
}));
assert.strictEqual(repaired.status, "unclaimed");
assert.strictEqual(repaired.removed, 1);
assert.strictEqual(
  sheets.Claims.length,
  1,
  "admin unclaim removed the invalid contribution"
);
const boardAfterRepair = output(context.handleBoard({token: "participant-secret", rsn: "Jake"}));
assert.strictEqual(
  boardAfterRepair.status,
  "ok",
  "the board recovers once the invalid row is removed"
);

// An orphaned row whose tile is gone from Items is removable the same way.
sheets.Claims = [claimsBeforeIntegrity[0].slice(),
  claimRow("Team One", "ghost-tile", "Deleted tile", 4151, "Abyssal whip", "orphan-2")];
const orphanRemoved = output(context.handleUnclaim({
  admin_token: "organizer-secret",
  team: "Team One",
  tile_id: "ghost-tile"
}));
assert.strictEqual(orphanRemoved.status, "unclaimed");
assert.strictEqual(orphanRemoved.removed, 1);
assert.strictEqual(sheets.Claims.length, 1, "the orphaned row is gone");

// Ids are authoritative; display text is historical. Renaming a tile or item in Items must
// not invalidate the claims already recorded under the old names.
sheets.Claims = [claimsBeforeIntegrity[0].slice(),
  claimRow("Team One", "rare-drop", "Any rare drop (old name)", 4151,
    "Abyssal whip (old name)", "renamed-1")];
const renamedBoard = output(context.handleBoard({token: "participant-secret", rsn: "Jake"}));
assert.strictEqual(renamedBoard.status, "ok", "renamed display text is not an integrity error");
const renamedTile = renamedBoard.tiles.find(tile => tile.id === "rare-drop");
assert.strictEqual(renamedTile.claimed, true);
assert.strictEqual(
  renamedTile.claimedItem.name,
  "Abyssal whip (old name)",
  "the historical name recorded at claim time is preserved"
);

// Structural duplicates still fail, and now say which row.
sheets.Claims = [claimsBeforeIntegrity[0].slice(),
  claimRow("Team One", "rare-drop", "Any rare drop", 4151, "Abyssal whip", "dupe-a"),
  claimRow("Team One", "rare-drop", "Any rare drop", 4151, "Abyssal whip", "dupe-b")];
assert.throws(
  () => context.handleBoard({token: "participant-secret", rsn: "Jake"}),
  /multiple Claims rows for team Team One, tile_id rare-drop, item_id 4151 \(Claims row 3\)/,
  "a duplicate contribution names the offending row"
);

sheets.Claims = [claimsBeforeIntegrity[0].slice(),
  claimRow("Team One", "rare-drop", "Any rare drop", 4151, "Abyssal whip", "same-id"),
  claimRow("Team One", "11832", "Bandos chestplate", 11832, "Bandos chestplate", "same-id")];
assert.throws(
  () => context.handleBoard({token: "participant-secret", rsn: "Jake"}),
  /claim_id appears more than once: same-id \(Claims row 3\)/,
  "a duplicate claim_id names the offending row"
);

sheets.Claims = claimsBeforeIntegrity;

// The organizer looks at the Leaderboard, not at an HTTP response, so the same check is
// surfaced there.
const integrityFormula = generatedFormula("G5");
assert(
  integrityFormula.includes("COUNTIFS(Items!A2:A,Claims!B2:B,Items!C2:C,Claims!D2:D)=0"),
  "the leaderboard must flag Claims rows whose tile/item pair is absent from Items"
);
assert(
  integrityFormula.includes("Claims!A2:A<>"),
  "blank Claims padding rows must not be reported as invalid"
);

// ---------------------------------------------------------------------------
// Terminal rejections are idempotent across a lost response (#36).
//
// Accepted contributions already replay from their Claims row. Rejections went only to Audit,
// which is never consulted, so a retry of the same claimId after a lost HTTP response was
// re-evaluated against whatever the state had become by then.
// ---------------------------------------------------------------------------

const claimsBeforeLedger = sheets.Claims.map(row => row.slice());
const itemsBeforeLedger = sheets.Items.map(row => row.slice());
const teamsBeforeLedger = sheets.Teams.map(row => row.slice());
sheets.Claims = [claimsBeforeLedger[0].slice()];

// A drop rejected just before event_start used to be accepted by its own retry a moment
// after the event opened, silently extending the event for one player.
sheets.Config.push(["event_start", new Date(Date.now() + 60 * 60 * 1000)]);
const rejectedBeforeStart = output(context.handleClaim({
  token: "participant-secret",
  rsn: "Jake",
  itemId: 4151,
  itemName: "Abyssal whip",
  claimId: "lost-response-start"
}));
assert.strictEqual(rejectedBeforeStart.status, "event_closed");
sheets.Config.pop();

const retryAfterEventOpened = output(context.handleClaim({
  token: "participant-secret",
  rsn: "Jake",
  itemId: 4151,
  itemName: "Abyssal whip",
  claimId: "lost-response-start"
}));
assert.strictEqual(
  retryAfterEventOpened.status,
  "event_closed",
  "a retry must return the original outcome even though the event has since opened"
);
assert.strictEqual(retryAfterEventOpened.replay, true);
assert.strictEqual(
  sheets.Claims.length,
  1,
  "the replayed rejection must not create a Claims row"
);

// A fresh drop of the same item is a different operation and is accepted normally.
const freshAfterOpen = output(context.handleClaim({
  token: "participant-secret",
  rsn: "Jake",
  itemId: 4151,
  itemName: "Abyssal whip",
  claimId: "fresh-after-open"
}));
assert.strictEqual(
  freshAfterOpen.status,
  "claimed",
  "a new claim id is evaluated against current state, not the ledger"
);

// A roster edit between attempts used to turn not_on_team into an accepted claim.
sheets.Claims = [claimsBeforeLedger[0].slice()];
sheets.Teams = [["rsn", "team"], ["someone else", "Team One"]];
const rejectedOffRoster = output(context.handleClaim({
  token: "participant-secret",
  rsn: "Jake",
  itemId: 4151,
  itemName: "Abyssal whip",
  claimId: "lost-response-team"
}));
assert.strictEqual(rejectedOffRoster.status, "not_on_team");

sheets.Teams = [["rsn", "team"], ["jake", "Team One"]];
const retryAfterRosterFixed = output(context.handleClaim({
  token: "participant-secret",
  rsn: "Jake",
  itemId: 4151,
  itemName: "Abyssal whip",
  claimId: "lost-response-team"
}));
assert.strictEqual(
  retryAfterRosterFixed.status,
  "not_on_team",
  "a retry must not become an accepted claim because Teams changed"
);
assert.strictEqual(retryAfterRosterFixed.replay, true);
assert.strictEqual(sheets.Claims.length, 1, "the replayed rejection wrote nothing");

// The same for a board edit between attempts.
const rejectedOffBoard = output(context.handleClaim({
  token: "participant-secret",
  rsn: "Jake",
  itemId: 995,
  itemName: "Coins",
  claimId: "lost-response-board"
}));
assert.strictEqual(rejectedOffBoard.status, "not_on_board");

sheets.Items.push(["coins", "Coins", 995, "Coins", 1, 1, ""]);
const retryAfterBoardGrew = output(context.handleClaim({
  token: "participant-secret",
  rsn: "Jake",
  itemId: 995,
  itemName: "Coins",
  claimId: "lost-response-board"
}));
assert.strictEqual(
  retryAfterBoardGrew.status,
  "not_on_board",
  "a retry must not become an accepted claim because Items changed"
);
assert.strictEqual(retryAfterBoardGrew.replay, true);
assert.strictEqual(
  retryAfterBoardGrew.itemName,
  "Coins",
  "the replay carries the fields the client needs to describe the outcome"
);
sheets.Items = itemsBeforeLedger.map(row => row.slice());

// A duplicate is terminal too, and replays with the fields describe() renders.
sheets.Claims = [claimsBeforeLedger[0].slice()];
output(context.handleClaim({
  token: "participant-secret",
  rsn: "Jake",
  itemId: 4151,
  itemName: "Abyssal whip",
  claimId: "ledger-winner"
}));
const duplicateRejection = output(context.handleClaim({
  token: "participant-secret",
  rsn: "Jake",
  itemId: 21034,
  itemName: "Dexterous prayer scroll",
  claimId: "lost-response-duplicate"
}));
assert.strictEqual(duplicateRejection.status, "duplicate");

const retriedDuplicate = output(context.handleClaim({
  token: "participant-secret",
  rsn: "Jake",
  itemId: 21034,
  itemName: "Dexterous prayer scroll",
  claimId: "lost-response-duplicate"
}));
assert.strictEqual(retriedDuplicate.status, "duplicate");
assert.strictEqual(retriedDuplicate.replay, true);
assert.strictEqual(
  retriedDuplicate.complete,
  true,
  "the replayed duplicate still reports that the tile was already complete"
);
assert.strictEqual(retriedDuplicate.tileName, "Any rare drop");

// Claims stays authoritative: an accepted contribution replays from its own row, never from
// the ledger, because a row in Claims means the contribution really happened.
const acceptedReplay = output(context.handleClaim({
  token: "participant-secret",
  rsn: "Jake",
  itemId: 4151,
  claimId: "ledger-winner"
}));
assert.strictEqual(acceptedReplay.status, "claimed");
assert.strictEqual(acceptedReplay.replay, true);

// A claim id names one operation. Reusing it for a different player or item must fail closed
// rather than report somebody else's outcome.
const conflictingRsn = output(context.handleClaim({
  token: "participant-secret",
  rsn: "someone else",
  itemId: 21034,
  itemName: "Dexterous prayer scroll",
  claimId: "lost-response-duplicate"
}));
assert.strictEqual(conflictingRsn.error, "claim_id_conflict");

const conflictingItem = output(context.handleClaim({
  token: "participant-secret",
  rsn: "Jake",
  itemId: 11832,
  itemName: "Bandos chestplate",
  claimId: "lost-response-duplicate"
}));
assert.strictEqual(conflictingItem.error, "claim_id_conflict");
assert.strictEqual(lockDepth, 0, "the conflict path released the script lock");

// The ledger holds operational fields only.
const ledgerText = JSON.stringify(sheets.Attempts);
["participant-secret", "organizer-secret", "discord", "token"].forEach(secret => {
  assert(
    !ledgerText.includes(secret),
    "the Attempts ledger must never store credentials: found " + secret
  );
});

// A deployment that has not re-run setupSheet has no Attempts tab. Claims must keep working
// with the old semantics rather than every claim failing mid-event on a missing tab.
const ledgerRows = sheets.Attempts;
delete sheets.Attempts;
sheets.Claims = [claimsBeforeLedger[0].slice()];
const legacyRejection = output(context.handleClaim({
  token: "participant-secret",
  rsn: "Jake",
  itemId: 995,
  itemName: "Coins",
  claimId: "legacy-backend-1"
}));
assert.strictEqual(
  legacyRejection.status,
  "not_on_board",
  "a sheet without the Attempts tab still decides claims"
);
const legacyAccepted = output(context.handleClaim({
  token: "participant-secret",
  rsn: "Jake",
  itemId: 4151,
  itemName: "Abyssal whip",
  claimId: "legacy-backend-2"
}));
assert.strictEqual(legacyAccepted.status, "claimed");
assert.strictEqual(lockDepth, 0, "the legacy path released the script lock");
sheets.Attempts = ledgerRows;

sheets.Claims = claimsBeforeLedger;
sheets.Teams = teamsBeforeLedger;

console.log("Apps Script grouped-tile and security tests passed");
