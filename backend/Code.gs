/**
 * Dink Bingo backend — Google Apps Script web app bound to the board spreadsheet.
 *
 * The spreadsheet is the single source of truth for which team owns which tile.
 * Every mutating path runs inside a script lock so two simultaneous drops of the
 * same item cannot both be recorded.
 *
 * Endpoints (deploy as a web app, execute as *me*, access *anyone*):
 *
 *   GET  ?action=ping
 *   POST {action: "board", token, rsn}
 *   POST {action: "unclaim", admin_token, team, item_id}
 *   POST {token, rsn, itemId, itemName, quantity, source, claimId}
 *
 * Run `setupSheet()` once from the editor to create the tabs.
 */

var SHEET_ITEMS = 'Items';
var SHEET_TEAMS = 'Teams';
var SHEET_CLAIMS = 'Claims';
var SHEET_AUDIT = 'Audit';
var SHEET_CONFIG = 'Config';
var SHEET_LEADERBOARD = 'Leaderboard';

// Stay below the RuneLite HTTP client's read timeout so callers can receive retryable=true.
var LOCK_TIMEOUT_MS = 5000;

// ---------------------------------------------------------------------------
// Entry points
// ---------------------------------------------------------------------------

function doGet(e) {
  try {
    var params = (e && e.parameter) || {};
    var action = params.action || 'board';

    if (action === 'ping') {
      return json({ status: 'ok', version: 1 });
    }
    if (action === 'board' || action === 'unclaim') {
      return json({ status: 'error', error: 'post_required' });
    }
    return json({ status: 'error', error: 'unknown_action' });
  } catch (err) {
    return json({ status: 'error', error: String(err) });
  }
}

function doPost(e) {
  var body = {};
  try {
    body = JSON.parse((e && e.postData && e.postData.contents) || '{}');
  } catch (err) {
    return json({ status: 'error', error: 'bad_json' });
  }

  try {
    if (body.action === 'board') {
      return handleBoard(body);
    }
    if (body.action === 'unclaim') {
      return handleUnclaim(body);
    }
    return handleClaim(body);
  } catch (err) {
    audit(body.rsn, body.itemId, 'error', body, String(err));
    return json({ status: 'error', error: String(err) });
  }
}

// ---------------------------------------------------------------------------
// board
// ---------------------------------------------------------------------------

function handleBoard(params) {
  var cfg = readConfig();
  if (!tokenValid(cfg, params.token)) {
    return json({ status: 'error', error: 'bad_token' });
  }

  var rsn = normalizeRsn(params.rsn);
  var team = resolveTeam(rsn);
  var items = readItems();
  var claims = readClaims();

  var out = [];
  var remaining = 0;
  for (var i = 0; i < items.length; i++) {
    var item = items[i];
    var claim = team ? claims[claimKey(team, item.id)] : null;
    if (!claim) remaining++;
    out.push({
      id: item.id,
      name: item.name,
      points: item.points,
      claimed: !!claim,
      claimedBy: claim ? claim.rsn : null,
      claimedAt: claim ? claim.claimedAt : null
    });
  }

  return json({
    status: 'ok',
    team: team,
    remaining: team ? remaining : items.length,
    total: items.length,
    eventOpen: eventOpen(cfg),
    items: out
  });
}

// ---------------------------------------------------------------------------
// claim
// ---------------------------------------------------------------------------

function handleClaim(body) {
  var cfg = readConfig();

  if (!tokenValid(cfg, body.token)) {
    audit(body.rsn, body.itemId, 'bad_token', body, '');
    return json({ status: 'error', error: 'bad_token' });
  }

  var rsn = normalizeRsn(body.rsn);
  var itemId = parseInt(body.itemId, 10);
  if (!rsn || isNaN(itemId)) {
    audit(rsn, body.itemId, 'bad_request', body, '');
    return json({ status: 'error', error: 'bad_request' });
  }

  var lock = LockService.getScriptLock();
  if (!lock.tryLock(LOCK_TIMEOUT_MS)) {
    // Caller retries with the same claimId, so refusing here is safe.
    return json({ status: 'error', error: 'lock_timeout', retryable: true });
  }

  try {
    var claims = readClaims();

    // Idempotency first: a retried POST must return the original outcome rather
    // than being treated as a fresh attempt.
    if (body.claimId) {
      var prior = findClaimById(claims, body.claimId);
      if (prior) {
        var replayItem = findItem(prior.itemId);
        return json({
          status: 'claimed',
          replay: true,
          team: prior.team,
          itemId: prior.itemId,
          itemName: prior.itemName,
          points: replayItem ? replayItem.points : 0,
          total: readItems().length,
          remaining: countRemaining(prior.team, claims)
        });
      }
    }

    if (!eventOpen(cfg)) {
      auditLocked(rsn, itemId, 'event_closed', body, '');
      return json({ status: 'event_closed' });
    }

    var team = resolveTeam(rsn);
    if (!team) {
      auditLocked(rsn, itemId, 'not_on_team', body, '');
      return json({ status: 'not_on_team' });
    }

    var item = findItem(itemId);
    if (!item) {
      auditLocked(rsn, itemId, 'not_on_board', body, '');
      return json({ status: 'not_on_board' });
    }

    var existing = claims[claimKey(team, itemId)];
    if (existing) {
      auditLocked(rsn, itemId, 'duplicate', body, 'held by ' + existing.rsn);
      return json({
        status: 'duplicate',
        team: team,
        itemId: itemId,
        itemName: item.name,
        claimedBy: existing.rsn,
        claimedAt: existing.claimedAt,
        remaining: countRemaining(team, claims)
      });
    }

    var now = new Date();
    sheet(SHEET_CLAIMS).appendRow([
      team,
      itemId,
      item.name,
      rsn,
      now,
      body.claimId || Utilities.getUuid(),
      body.source || ''
    ]);
    SpreadsheetApp.flush();

    auditLocked(rsn, itemId, 'claimed', body, 'team ' + team);

    // Recount with the new row included.
    claims[claimKey(team, itemId)] = { rsn: rsn, claimedAt: now };
    var remaining = countRemaining(team, claims);

    if (truthy(cfg.announce_from_backend) && cfg.discord_webhook) {
      postDiscord(cfg.discord_webhook, rsn + ' claimed **' + item.name + '** for ' + team +
        ' — ' + remaining + ' tiles left');
    }

    return json({
      status: 'claimed',
      team: team,
      itemId: itemId,
      itemName: item.name,
      points: item.points,
      remaining: remaining,
      total: readItems().length
    });
  } finally {
    lock.releaseLock();
  }
}

// ---------------------------------------------------------------------------
// admin unclaim
// ---------------------------------------------------------------------------

function handleUnclaim(params) {
  var cfg = readConfig();
  if (!cfg.admin_token || params.admin_token !== cfg.admin_token) {
    return json({ status: 'error', error: 'bad_admin_token' });
  }

  var team = String(params.team || '').trim();
  var itemId = parseInt(params.item_id, 10);
  if (!team || isNaN(itemId)) {
    return json({ status: 'error', error: 'bad_request' });
  }

  var lock = LockService.getScriptLock();
  if (!lock.tryLock(LOCK_TIMEOUT_MS)) {
    return json({ status: 'error', error: 'lock_timeout', retryable: true });
  }

  try {
    var sh = sheet(SHEET_CLAIMS);
    var values = sh.getDataRange().getValues();
    for (var r = values.length - 1; r >= 1; r--) {
      if (String(values[r][0]).trim() === team && parseInt(values[r][1], 10) === itemId) {
        sh.deleteRow(r + 1);
        SpreadsheetApp.flush();
        auditLocked('ADMIN', itemId, 'unclaimed', params, 'team ' + team);
        return json({ status: 'unclaimed', team: team, itemId: itemId });
      }
    }
    return json({ status: 'not_claimed', team: team, itemId: itemId });
  } finally {
    lock.releaseLock();
  }
}

// ---------------------------------------------------------------------------
// sheet access
// ---------------------------------------------------------------------------

function sheet(name) {
  var sh = SpreadsheetApp.getActiveSpreadsheet().getSheetByName(name);
  if (!sh) throw new Error('missing sheet tab: ' + name);
  return sh;
}

function readConfig() {
  var values = sheet(SHEET_CONFIG).getDataRange().getValues();
  var cfg = {};
  for (var r = 1; r < values.length; r++) {
    var key = String(values[r][0]).trim();
    if (key) cfg[key] = values[r][1];
  }
  return cfg;
}

function readItems() {
  var values = sheet(SHEET_ITEMS).getDataRange().getValues();
  var items = [];
  for (var r = 1; r < values.length; r++) {
    var id = parseInt(values[r][0], 10);
    if (isNaN(id)) continue;
    items.push({
      id: id,
      name: String(values[r][1] || '').trim(),
      points: values[r][2] === '' || values[r][2] == null ? 1 : Number(values[r][2])
    });
  }
  return items;
}

function findItem(itemId) {
  var items = readItems();
  for (var i = 0; i < items.length; i++) {
    if (items[i].id === itemId) return items[i];
  }
  return null;
}

/** @return {Object} keyed by `team||itemId` */
function readClaims() {
  var values = sheet(SHEET_CLAIMS).getDataRange().getValues();
  var claims = {};
  for (var r = 1; r < values.length; r++) {
    var team = String(values[r][0]).trim();
    var itemId = parseInt(values[r][1], 10);
    if (!team || isNaN(itemId)) continue;
    claims[claimKey(team, itemId)] = {
      team: team,
      itemId: itemId,
      itemName: String(values[r][2] || ''),
      rsn: String(values[r][3] || ''),
      claimedAt: values[r][4] ? new Date(values[r][4]).toISOString() : null,
      claimId: String(values[r][5] || '')
    };
  }
  return claims;
}

function findClaimById(claims, claimId) {
  for (var key in claims) {
    if (claims[key].claimId === claimId) return claims[key];
  }
  return null;
}

function claimKey(team, itemId) {
  return team + '||' + itemId;
}

function countRemaining(team, claims) {
  var items = readItems();
  var n = 0;
  for (var i = 0; i < items.length; i++) {
    if (!claims[claimKey(team, items[i].id)]) n++;
  }
  return n;
}

function resolveTeam(rsn) {
  if (!rsn) return null;
  var values = sheet(SHEET_TEAMS).getDataRange().getValues();
  for (var r = 1; r < values.length; r++) {
    if (normalizeRsn(values[r][0]) === rsn) {
      var team = String(values[r][1] || '').trim();
      return team || null;
    }
  }
  return null;
}

function audit(rsn, itemId, result, payload, notes) {
  var lock = LockService.getScriptLock();
  try {
    if (!lock.tryLock(LOCK_TIMEOUT_MS)) {
      console.error('audit skipped: lock timeout');
      return;
    }
    auditLocked(rsn, itemId, result, payload, notes);
  } catch (err) {
    // Auditing must never break a claim.
    console.error('audit failed: ' + err);
  } finally {
    if (lock.hasLock()) lock.releaseLock();
  }
}

/** Append an audit row while the caller already holds the script lock. */
function auditLocked(rsn, itemId, result, payload, notes) {
  try {
    sheet(SHEET_AUDIT).appendRow([
      new Date(),
      rsn || '',
      itemId == null ? '' : itemId,
      result,
      notes || '',
      JSON.stringify(sanitizeAuditPayload(payload)).slice(0, 4000)
    ]);
  } catch (err) {
    // Auditing must never break a claim.
    console.error('audit failed: ' + err);
  }
}

/**
 * Audit only the operational fields needed to investigate a claim. Authentication values
 * and webhook URLs are deliberately omitted even if a caller includes them.
 */
function sanitizeAuditPayload(payload) {
  var input = payload || {};
  var allowed = ['action', 'rsn', 'itemId', 'itemName', 'quantity', 'source', 'claimId', 'team', 'item_id'];
  var safe = {};
  for (var i = 0; i < allowed.length; i++) {
    var key = allowed[i];
    if (input[key] != null) safe[key] = input[key];
  }
  return safe;
}

// ---------------------------------------------------------------------------
// helpers
// ---------------------------------------------------------------------------

/** RuneScape names treat underscore and space as equivalent and are case-insensitive. */
function normalizeRsn(rsn) {
  if (rsn == null) return null;
  var s = String(rsn).trim().replace(/[ _]/g, ' ').toLowerCase();
  return s || null;
}

function tokenValid(cfg, token) {
  if (!cfg.token) return false; // fail closed if Config was damaged or incompletely created
  return String(token || '') === String(cfg.token);
}

function eventOpen(cfg) {
  var now = new Date();
  if (cfg.event_start && now < new Date(cfg.event_start)) return false;
  if (cfg.event_end && now > new Date(cfg.event_end)) return false;
  return true;
}

function truthy(v) {
  return String(v).trim().toLowerCase() === 'true';
}

function json(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}

function postDiscord(webhook, content) {
  try {
    UrlFetchApp.fetch(webhook, {
      method: 'post',
      contentType: 'application/json',
      payload: JSON.stringify({ content: content }),
      muteHttpExceptions: true
    });
  } catch (err) {
    // Fetch failures can include the requested URL; never copy a webhook into logs.
    console.error('discord post failed');
  }
}

// ---------------------------------------------------------------------------
// one-time setup
// ---------------------------------------------------------------------------

function setupSheet() {
  var ss = SpreadsheetApp.getActiveSpreadsheet();

  var tabs = {};
  tabs[SHEET_ITEMS] = ['item_id', 'item_name', 'points', 'notes'];
  tabs[SHEET_TEAMS] = ['rsn', 'team'];
  tabs[SHEET_CLAIMS] = ['team', 'item_id', 'item_name', 'rsn', 'claimed_at', 'claim_id', 'source'];
  tabs[SHEET_AUDIT] = ['ts', 'rsn', 'item_id', 'result', 'notes', 'raw_payload'];
  tabs[SHEET_CONFIG] = ['key', 'value'];

  for (var name in tabs) {
    var sh = ss.getSheetByName(name);
    if (!sh) sh = ss.insertSheet(name);
    if (sh.getLastRow() === 0) {
      sh.appendRow(tabs[name]);
      sh.setFrozenRows(1);
      sh.getRange(1, 1, 1, tabs[name].length).setFontWeight('bold');
    }
  }

  var cfg = ss.getSheetByName(SHEET_CONFIG);
  if (cfg.getLastRow() <= 1) {
    cfg.appendRow(['token', Utilities.getUuid()]);
    cfg.appendRow(['admin_token', Utilities.getUuid()]);
    cfg.appendRow(['discord_webhook', '']);
    cfg.appendRow(['event_start', '']);
    cfg.appendRow(['event_end', '']);
    cfg.appendRow(['announce_from_backend', 'false']);
  }

  var items = ss.getSheetByName(SHEET_ITEMS);
  if (items.getLastRow() <= 1) {
    items.appendRow([11832, 'Bandos chestplate', 1, '']);
    items.appendRow([21034, 'Dexterous prayer scroll', 1, '']);
    items.appendRow([4151, 'Abyssal whip', 1, '']);
  }

  var leaderboard = ss.getSheetByName(SHEET_LEADERBOARD);
  if (!leaderboard) leaderboard = ss.insertSheet(SHEET_LEADERBOARD);
  if (leaderboard.getLastRow() === 0) {
    setupLeaderboard(leaderboard);
  }

  SpreadsheetApp.getUi().alert('Dink Bingo tabs and leaderboard are ready. Fill in Items and Teams, then Deploy > New deployment > Web app.');
}

/**
 * Re-applies the derived leaderboard formulas and formatting without changing any source tab.
 * Run this after updating Code.gs for an existing event sheet.
 */
function refreshLeaderboard() {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var leaderboard = ss.getSheetByName(SHEET_LEADERBOARD);
  if (!leaderboard) leaderboard = ss.insertSheet(SHEET_LEADERBOARD);
  setupLeaderboard(leaderboard);
  SpreadsheetApp.getUi().alert(
    'Leaderboard formulas and formatting refreshed. Items, Teams, Claims, Audit, and Config were not changed.'
  );
}

/**
 * One-time upgrade helper for sheets used before the security hardening release.
 * Redacts legacy raw audit payloads and clears the retired account_hash column.
 * Rotate token/admin_token manually after this completes.
 */
function scrubLegacySensitiveData() {
  var lock = LockService.getScriptLock();
  if (!lock.tryLock(LOCK_TIMEOUT_MS)) {
    throw new Error('Could not acquire the script lock');
  }
  try {
    var auditSheet = sheet(SHEET_AUDIT);
    var auditValues = auditSheet.getDataRange().getValues();
    if (auditValues.length > 1) {
      var payloadColumn = auditValues[0].indexOf('raw_payload');
      if (payloadColumn >= 0) {
        var sanitized = [];
        for (var r = 1; r < auditValues.length; r++) {
          var payload = {};
          try {
            payload = JSON.parse(String(auditValues[r][payloadColumn] || '{}'));
          } catch (err) {
            // A malformed legacy payload is not useful enough to retain at the cost of secrets.
          }
          sanitized.push([JSON.stringify(sanitizeAuditPayload(payload)).slice(0, 4000)]);
        }
        auditSheet.getRange(2, payloadColumn + 1, sanitized.length, 1).setValues(sanitized);
      }
    }

    var claimsSheet = sheet(SHEET_CLAIMS);
    var claimValues = claimsSheet.getDataRange().getValues();
    if (claimValues.length > 1) {
      var accountHashColumn = claimValues[0].indexOf('account_hash');
      if (accountHashColumn >= 0) {
        claimsSheet.getRange(2, accountHashColumn + 1, claimValues.length - 1, 1).clearContent();
      }
    }
    SpreadsheetApp.flush();
  } finally {
    lock.releaseLock();
  }

  SpreadsheetApp.getUi().alert(
    'Legacy audit payloads were redacted and account hashes cleared. ' +
    'Now rotate token and admin_token in Config, then give participants the new event token.'
  );
}

/**
 * Creates a derived, read-only event view. Claims remains authoritative; these formulas
 * only summarize it and recalculate automatically when Items, Teams, or Claims changes.
 */
function setupLeaderboard(sh) {
  // Leave generous room for team columns so four-team events (and much larger ones) spill
  // without organizers needing to resize the sheet.
  if (sh.getMaxColumns() < 100) {
    sh.insertColumnsAfter(sh.getMaxColumns(), 100 - sh.getMaxColumns());
  }

  sh.getRange('A1').setValue('Dink Bingo Leaderboard');
  sh.getRange('A2').setValue(
    'Read-only view derived from Items, Teams, and Claims. Make event changes on those tabs.'
  );

  sh.getRange('A4:E4').setValues([
    ['Team', 'Claimed', 'Points', 'Remaining Items', 'Remaining Points']
  ]);
  sh.getRange('A5').setFormula(
    '=IFERROR(SORT(UNIQUE(FILTER(Teams!B2:B,Teams!B2:B<>""))),"")'
  );
  sh.getRange('B5').setFormula(
    '=IFERROR(LET(teams,SORT(UNIQUE(FILTER(Teams!B2:B,Teams!B2:B<>""))),' +
    'itemIds,FILTER(Items!A2:A,Items!A2:A<>""),' +
    'MAP(teams,LAMBDA(team,SUM(MAP(itemIds,LAMBDA(itemId,' +
    'IF(COUNTIFS(Claims!A2:A,team,Claims!B2:B,itemId)>0,1,0)))))),"")'
  );
  sh.getRange('C5').setFormula(
    '=IFERROR(LET(teams,SORT(UNIQUE(FILTER(Teams!B2:B,Teams!B2:B<>""))),' +
    'itemIds,FILTER(Items!A2:A,Items!A2:A<>""),' +
    'itemPoints,FILTER(Items!C2:C,Items!A2:A<>""),' +
    'MAP(teams,LAMBDA(team,SUM(MAP(itemIds,itemPoints,LAMBDA(itemId,points,' +
    'IF(COUNTIFS(Claims!A2:A,team,Claims!B2:B,itemId)>0,' +
    'IF(points="",1,points),0))))))),"")'
  );
  sh.getRange('D5').setFormula(
    '=IFERROR(LET(teams,SORT(UNIQUE(FILTER(Teams!B2:B,Teams!B2:B<>""))),' +
    'itemIds,FILTER(Items!A2:A,Items!A2:A<>""),' +
    'MAP(teams,LAMBDA(team,SUM(MAP(itemIds,LAMBDA(itemId,' +
    'IF(COUNTIFS(Claims!A2:A,team,Claims!B2:B,itemId)=0,1,0)))))),"")'
  );
  sh.getRange('E5').setFormula(
    '=IFERROR(LET(teams,SORT(UNIQUE(FILTER(Teams!B2:B,Teams!B2:B<>""))),' +
    'itemIds,FILTER(Items!A2:A,Items!A2:A<>""),' +
    'itemPoints,FILTER(Items!C2:C,Items!A2:A<>""),' +
    'MAP(teams,LAMBDA(team,SUM(MAP(itemIds,itemPoints,LAMBDA(itemId,points,' +
    'IF(COUNTIFS(Claims!A2:A,team,Claims!B2:B,itemId)=0,' +
    'IF(points="",1,points),0))))))),"")'
  );

  sh.getRange('A25:C25').setValues([['Item ID', 'Item', 'Points']]);
  sh.getRange('A26').setFormula(
    '=IFERROR(FILTER(Items!A2:C,Items!A2:A<>""),"")'
  );
  sh.getRange('D25').setFormula(
    '=IFERROR(TRANSPOSE(SORT(UNIQUE(FILTER(Teams!B2:B,Teams!B2:B<>"")))),"")'
  );
  sh.getRange('D26').setFormula(
    '=IFERROR(LET(itemIds,FILTER(Items!A2:A,Items!A2:A<>""),' +
    'teams,SORT(UNIQUE(FILTER(Teams!B2:B,Teams!B2:B<>""))),' +
    'MAKEARRAY(ROWS(itemIds),ROWS(teams),LAMBDA(rowIndex,columnIndex,' +
    'LET(team,INDEX(teams,columnIndex,1),itemId,INDEX(itemIds,rowIndex,1),' +
    'claimedBy,IFNA(INDEX(FILTER(Claims!D2:D,Claims!A2:A=team,' +
    'Claims!B2:B=itemId),1),""),IF(claimedBy="","—","✓ "&claimedBy))))),"")'
  );

  sh.getRange('A1').setFontSize(16).setFontWeight('bold');
  sh.getRange('A2').setFontColor('#5f6368');
  sh.getRange('A4:E4').setBackground('#f1f3f4').setFontWeight('bold');
  sh.getRange('A25:CV25').setBackground('#f1f3f4').setFontWeight('bold');
  sh.getRange('B5:E24').setNumberFormat('0');
  sh.setColumnWidth(1, 90);
  sh.setColumnWidth(2, 220);
  sh.setColumnWidth(3, 70);
  sh.setColumnWidths(4, 2, 130);
  sh.setFrozenColumns(3);
  sh.setTabColor('#34a853');

  var claimedRule = SpreadsheetApp.newConditionalFormatRule()
    .whenTextStartsWith('✓')
    .setBackground('#d9ead3')
    .setFontColor('#274e13')
    .setRanges([sh.getRange('D26:CV1000')])
    .build();
  sh.setConditionalFormatRules([claimedRule]);
}
