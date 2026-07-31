const assert = require("assert");
const fs = require("fs");
const vm = require("vm");

const context = {};
vm.createContext(context);
vm.runInContext(fs.readFileSync("backend/Code.gs", "utf8"), context);

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

console.log("Apps Script security helper tests passed");
