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
  Config: [
    ["key", "value"],
    ["token", "participant-secret"],
    ["admin_token", "organizer-secret"],
    ["announce_from_backend", "false"]
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
  ["Claims"],
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
  ["Claims"],
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
  ["Claims"],
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

console.log("Apps Script grouped-tile and security tests passed");
