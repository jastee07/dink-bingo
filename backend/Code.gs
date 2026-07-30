/**
 * Dink Bingo backend — Google Apps Script web app bound to the board spreadsheet.
 *
 * The spreadsheet is the single source of truth for which team owns which tile.
 * Every mutating path runs inside a script lock so two simultaneous drops of the
 * same item cannot both be recorded.
 *
 * Endpoints (deploy as a web app, execute as *me*, access *anyone*):
 *
 *   GET  ?action=board&token=…&rsn=…
 *   GET  ?action=unclaim&admin_token=…&team=…&item_id=…
 *   POST {token, rsn, itemId, itemName, quantity, source, claimId, accountHash}
 *
 * Run `setupSheet()` once from the editor to create the tabs.
 */

var SHEET_ITEMS = 'Items';
var SHEET_TEAMS = 'Teams';
var SHEET_CLAIMS = 'Claims';
var SHEET_AUDIT = 'Audit';
var SHEET_CONFIG = 'Config';
var SHEET_LEADERBOARD = 'Leaderboard';

var LOCK_TIMEOUT_MS = 20000;

// ---------------------------------------------------------------------------
// Entry points
// ---------------------------------------------------------------------------

function doGet(e) {
  try {
    var params = (e && e.parameter) || {};
    var action = params.action || 'board';

    if (action === 'board') {
      return handleBoard(params);
    }
    if (action === 'unclaim') {
      return handleUnclaim(params);
    }
    if (action === 'ping') {
      return json({ status: 'ok', version: 1 });
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
    audit(rsn, itemId, 'lock_timeout', body, '');
    return json({ status: 'error', error: 'lock_timeout', retryable: true });
  }

  try {
    var claims = readClaims();

    // Idempotency first: a retried POST must return the original outcome rather
    // than being treated as a fresh attempt.
    if (body.claimId) {
      var prior = findClaimById(claims, body.claimId);
      if (prior) {
        return json({
          status: 'claimed',
          replay: true,
          team: prior.team,
          itemId: prior.itemId,
          itemName: prior.itemName,
          remaining: countRemaining(prior.team, claims)
        });
      }
    }

    if (!eventOpen(cfg)) {
      audit(rsn, itemId, 'event_closed', body, '');
      return json({ status: 'event_closed' });
    }

    var team = resolveTeam(rsn);
    if (!team) {
      audit(rsn, itemId, 'not_on_team', body, '');
      return json({ status: 'not_on_team' });
    }

    var item = findItem(itemId);
    if (!item) {
      audit(rsn, itemId, 'not_on_board', body, '');
      return json({ status: 'not_on_board' });
    }

    var existing = claims[claimKey(team, itemId)];
    if (existing) {
      audit(rsn, itemId, 'duplicate', body, 'held by ' + existing.rsn);
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
      body.source || '',
      body.accountHash || ''
    ]);
    SpreadsheetApp.flush();

    audit(rsn, itemId, 'claimed', body, 'team ' + team);

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
        audit('ADMIN', itemId, 'unclaimed', params, 'team ' + team);
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
  try {
    sheet(SHEET_AUDIT).appendRow([
      new Date(),
      rsn || '',
      itemId == null ? '' : itemId,
      result,
      notes || '',
      JSON.stringify(payload || {}).slice(0, 4000)
    ]);
  } catch (err) {
    // Auditing must never break a claim.
    console.error('audit failed: ' + err);
  }
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
  if (!cfg.token) return true; // no token configured => open board
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
    console.error('discord post failed: ' + err);
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
  tabs[SHEET_CLAIMS] = ['team', 'item_id', 'item_name', 'rsn', 'claimed_at', 'claim_id', 'source', 'account_hash'];
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

  sh.getRange('A4:D4').setValues([['Team', 'Claimed', 'Points', 'Remaining']]);
  sh.getRange('A5').setFormula(
    '=IFERROR(SORT(UNIQUE(FILTER(Teams!B2:B,Teams!B2:B<>""))),"")'
  );
  sh.getRange('B5').setFormula(
    '=IFERROR(LET(teams,SORT(UNIQUE(FILTER(Teams!B2:B,Teams!B2:B<>""))),' +
    'MAP(teams,LAMBDA(team,COUNTIF(Claims!A2:A,team)))),"")'
  );
  sh.getRange('C5').setFormula(
    '=IFERROR(LET(teams,SORT(UNIQUE(FILTER(Teams!B2:B,Teams!B2:B<>""))),' +
    'MAP(teams,LAMBDA(team,SUM(IFNA(VLOOKUP(FILTER(Claims!B2:B,' +
    'Claims!A2:A=team),Items!A2:C,3,FALSE),0))))),"")'
  );
  sh.getRange('D5').setFormula(
    '=IFERROR(LET(teams,SORT(UNIQUE(FILTER(Teams!B2:B,Teams!B2:B<>""))),' +
    'total,ROWS(FILTER(Items!A2:A,Items!A2:A<>"")),' +
    'MAP(teams,LAMBDA(team,total-COUNTIF(Claims!A2:A,team)))),"")'
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
  sh.getRange('A4:D4').setBackground('#f1f3f4').setFontWeight('bold');
  sh.getRange('A25:CV25').setBackground('#f1f3f4').setFontWeight('bold');
  sh.getRange('B5:D24').setNumberFormat('0');
  sh.setColumnWidth(1, 90);
  sh.setColumnWidth(2, 220);
  sh.setColumnWidth(3, 70);
  sh.setColumnWidths(4, 97, 130);
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
