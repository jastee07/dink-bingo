const assert = require("assert");
const fs = require("fs");
const vm = require("vm");

const sheets = {
  Items: [
    ["tile_id", "tile_name", "item_id", "item_name", "points", "notes"],
    ["rare-drop", "Any rare drop", 4151, "Abyssal whip", 3, ""],
    ["rare-drop", "Any rare drop", 21034, "Dexterous prayer scroll", 3, ""],
    ["11832", "Bandos chestplate", 11832, "Bandos chestplate", 1, ""]
  ],
  Teams: [
    ["rsn", "team"],
    ["jake", "Team One"]
  ],
  Claims: [
    ["team", "tile_id", "tile_name", "item_id", "item_name", "rsn", "claimed_at", "claim_id", "source"]
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

function fakeSheet(name) {
  return {
    getDataRange() {
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
      return { getSheetByName: name => sheets[name] ? fakeSheet(name) : null };
    },
    flush() {}
  },
  LockService: {
    getScriptLock() {
      let held = false;
      return {
        tryLock() {
          held = true;
          return true;
        },
        hasLock() {
          return held;
        },
        releaseLock() {
          held = false;
        }
      };
    }
  },
  Utilities: {
    getUuid: () => "generated-uuid"
  }
};
vm.createContext(context);
vm.runInContext(fs.readFileSync("backend/Code.gs", "utf8"), context);

function output(result) {
  return JSON.parse(result.text);
}

const sanitized = context.sanitizeAuditPayload({
  action: "unclaim",
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

const first = output(context.handleClaim({
  token: "participant-secret",
  rsn: "Jake",
  itemId: 4151,
  itemName: "Abyssal whip",
  quantity: 1,
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

assert.throws(() => context.buildTileCatalog([
  {tileId: "one", tileName: "First", itemId: 4151, itemName: "Whip", points: 1},
  {tileId: "two", tileName: "Second", itemId: 4151, itemName: "Whip", points: 1}
]), /appears more than once/);
assert.throws(() => context.buildTileCatalog([
  {tileId: "one", tileName: "First", itemId: 4151, itemName: "Whip", points: 1},
  {tileId: "one", tileName: "Different", itemId: 21034, itemName: "Scroll", points: 1}
]), /inconsistent/);
assert.notStrictEqual(
  context.claimKey("Team||One", "tile"),
  context.claimKey("Team", "One||tile"),
  "team and tile ids must not collide in the in-memory claim index"
);

console.log("Apps Script grouped-tile and security tests passed");
