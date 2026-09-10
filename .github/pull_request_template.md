## Summary

<!-- What changed and why. Link the issue this closes, if there is one. -->

## Verification

<!-- Check what applies. Mark a box N/A rather than deleting it, so reviewers
     can see the item was considered. AGENTS.md has the full expectations. -->

- [ ] `./gradlew test` passes
- [ ] `node backend/Code.test.js` passes (backend changes only)
- [ ] Regression coverage added for any change to claim status handling, replay
      suppression, retry identity, URL types, or the Dink payload
- [ ] No event tokens, admin tokens, webhook URLs, or account hashes in the
      diff, the tests, or the documentation examples
- [ ] `README.md`, `SETUP.md`, and `backend/README.md` updated if behavior or
      operator steps changed

## Manual claim

Integration changes need a side-loaded client and a real claim; a passing unit
test only proves the payload reached RuneLite's event bus.

- [ ] Claimed a reversible test tile with a throwaway webhook, and Discord
      received the screenshot
- [ ] N/A, this change cannot affect the Dink handoff

## Invariants

Confirm nothing here regressed. See AGENTS.md for the full list.

- [ ] Announces only a `claimed` response, never `duplicate` or a failure
- [ ] Apps Script mutations stay under `LockService.getScriptLock()`
