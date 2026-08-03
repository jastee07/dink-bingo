/**
 * Bingo with Dink Notifications backend — Google Apps Script web app bound to the board spreadsheet.
 *
 * The spreadsheet is the single source of truth for which team owns which tile.
 * Every mutating path runs inside a script lock so two simultaneous drops of the
 * same logical tile cannot both be recorded.
 *
 * Endpoints (deploy as a web app, execute as *me*, access *anyone*):
 *
 *   GET  ?action=ping
 *   POST {action: "board", token, rsn}
 *   POST {action: "unclaim", admin_token, team, tile_id}
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
      return json({ status: 'ok', version: 2 });
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
  var catalog = readTiles();
  var claims = readClaims();

  var out = [];
  var remaining = 0;
  for (var i = 0; i < catalog.tiles.length; i++) {
    var tile = catalog.tiles[i];
    var state = team ? getClaimState(claims, team, tile.id) : emptyClaimState();
    var complete = tileComplete(tile, state);
    var completion = complete ? state.contributions[tile.required - 1] : null;
    if (!complete) remaining++;
    out.push({
      id: tile.id,
      name: tile.name,
      points: tile.points,
      required: tile.required,
      progress: state.contributions.length,
      claimed: complete,
      claimedBy: completion ? completion.rsn : null,
      claimedAt: completion ? completion.claimedAt : null,
      claimedItem: completion ? contributionItem(completion) : null,
      claimedItems: serializeContributions(state.contributions),
      options: tile.options
    });
  }

  return json({
    status: 'ok',
    team: team,
    remaining: team ? remaining : catalog.tiles.length,
    total: catalog.tiles.length,
    eventOpen: eventOpen(cfg),
    tiles: out
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
    var catalog = readTiles();
    var claims = readClaims();

    // Idempotency first: a retried POST must return the original outcome rather
    // than being treated as a fresh attempt.
    if (body.claimId) {
      var prior = claims.byClaimId[body.claimId];
      if (prior) {
        var replayTile = catalog.byTileId[tileMapKey(prior.tileId)];
        var replayState = getClaimState(claims, prior.team, prior.tileId);
        return json(claimResult(
          prior.completedTile ? 'claimed' : 'progress',
          true,
          prior.team,
          replayTile,
          prior,
          replayState,
          claims,
          catalog
        ));
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

    var tile = catalog.byItemId[itemId];
    if (!tile) {
      auditLocked(rsn, itemId, 'not_on_board', body, '');
      return json({ status: 'not_on_board' });
    }
    body.tileId = tile.id;
    var item = findOption(tile, itemId);

    var state = getClaimState(claims, team, tile.id);
    if (tileComplete(tile, state)) {
      var completion = state.contributions[tile.required - 1];
      auditLocked(rsn, itemId, 'duplicate', body, 'tile already complete');
      var completedDuplicate = claimResult(
        'duplicate', false, team, tile, completion, state, claims, catalog
      );
      completedDuplicate.duplicateReason = 'tile_complete';
      return json(completedDuplicate);
    }

    var existing = state.byItemId[itemId];
    if (existing) {
      auditLocked(rsn, itemId, 'duplicate', body, 'item already contributed by ' + existing.rsn);
      var itemDuplicate = claimResult(
        'duplicate', false, team, tile, existing, state, claims, catalog
      );
      itemDuplicate.duplicateReason = 'item_recorded';
      return json(itemDuplicate);
    }

    var now = new Date();
    var progress = state.contributions.length + 1;
    var complete = progress >= tile.required;
    var status = complete ? 'claimed' : 'progress';
    var contribution = {
      team: team,
      tileId: tile.id,
      tileName: tile.name,
      itemId: itemId,
      itemName: item.name,
      rsn: rsn,
      claimedAt: now,
      claimId: body.claimId || Utilities.getUuid(),
      source: body.source || '',
      progressAfter: progress,
      completedTile: complete
    };
    sheet(SHEET_CLAIMS).appendRow([
      contribution.team,
      contribution.tileId,
      contribution.tileName,
      contribution.itemId,
      contribution.itemName,
      contribution.rsn,
      contribution.claimedAt,
      contribution.claimId,
      contribution.source,
      contribution.progressAfter,
      contribution.completedTile
    ]);
    SpreadsheetApp.flush();

    auditLocked(rsn, itemId, status, body,
      'team ' + team + ', tile ' + tile.id + ', progress ' + progress + '/' + tile.required);

    state = addContribution(claims, contribution);
    var remaining = countRemaining(team, claims, catalog.tiles);

    if (truthy(cfg.announce_from_backend) && cfg.discord_webhook) {
      var label = item.name === tile.name ? '**' + item.name + '**' :
        '**' + item.name + '** for **' + tile.name + '**';
      var verb = complete ? 'completed' : 'advanced';
      postDiscord(cfg.discord_webhook, rsn + ' ' + verb + ' ' + label + ' for ' + team +
        ' — ' + progress + '/' + tile.required + ' items');
    }

    return json(claimResult(status, false, team, tile, contribution, state, claims, catalog));
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
  var tileId = String(params.tile_id || '').trim();
  var requestedItemId = params.item_id == null || params.item_id === '' ?
    null : parseInt(params.item_id, 10);
  if (!team || !tileId) {
    return json({ status: 'error', error: 'bad_request' });
  }
  if (requestedItemId != null && isNaN(requestedItemId)) {
    return json({ status: 'error', error: 'bad_request' });
  }

  var lock = LockService.getScriptLock();
  if (!lock.tryLock(LOCK_TIMEOUT_MS)) {
    return json({ status: 'error', error: 'lock_timeout', retryable: true });
  }

  try {
    var sh = sheet(SHEET_CLAIMS);
    var values = sh.getDataRange().getValues();
    var columns = requireColumns(values[0], SHEET_CLAIMS, ['team', 'tile_id', 'item_id']);
    var removed = 0;
    for (var r = values.length - 1; r >= 1; r--) {
      var rowItemId = parseInt(values[r][columns.item_id], 10);
      if (String(values[r][columns.team]).trim() === team &&
          String(values[r][columns.tile_id]).trim() === tileId &&
          (requestedItemId == null || rowItemId === requestedItemId)) {
        sh.deleteRow(r + 1);
        removed++;
      }
    }
    if (!removed) {
      return json({
        status: 'not_claimed',
        team: team,
        tileId: tileId,
        itemId: requestedItemId
      });
    }

    SpreadsheetApp.flush();
    var catalog = readTiles();
    var tile = catalog.byTileId[tileMapKey(tileId)];
    var claims = readClaims();
    var state = getClaimState(claims, team, tileId);
    auditLocked('ADMIN', requestedItemId, 'unclaimed', params,
      'team ' + team + ', tile ' + tileId + ', removed ' + removed);
    return json({
      status: 'unclaimed',
      team: team,
      tileId: tileId,
      itemId: requestedItemId,
      removed: removed,
      progress: state.contributions.length,
      required: tile ? tile.required : 0,
      complete: tile ? tileComplete(tile, state) : false
    });
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

function readTiles() {
  var values = sheet(SHEET_ITEMS).getDataRange().getValues();
  var columns = requireColumns(values[0], SHEET_ITEMS,
    ['tile_id', 'tile_name', 'item_id', 'item_name', 'points', 'required_count']);
  var rows = [];
  for (var r = 1; r < values.length; r++) {
    if (values[r].join('') === '') continue;
    rows.push({
      tileId: String(values[r][columns.tile_id] || '').trim(),
      tileName: String(values[r][columns.tile_name] || '').trim(),
      itemId: parseInt(values[r][columns.item_id], 10),
      itemName: String(values[r][columns.item_name] || '').trim(),
      points: values[r][columns.points] === '' || values[r][columns.points] == null ?
        1 : Number(values[r][columns.points]),
      required: values[r][columns.required_count] === '' ||
        values[r][columns.required_count] == null ? 1 : Number(values[r][columns.required_count])
    });
  }
  return buildTileCatalog(rows);
}

/** Build and validate the logical board while preserving first-seen tile order. */
function buildTileCatalog(rows) {
  var catalog = { tiles: [], byTileId: {}, byItemId: {} };
  for (var i = 0; i < rows.length; i++) {
    var row = rows[i];
    if (!row.tileId || !row.tileName || isNaN(row.itemId) || !row.itemName ||
        !isFinite(row.points) || !isFinite(row.required) ||
        Math.floor(row.required) !== row.required || row.required < 1) {
      throw new Error('invalid Items row ' + (i + 2));
    }
    if (catalog.byItemId[row.itemId]) {
      throw new Error('item_id ' + row.itemId + ' appears more than once');
    }

    var tileKey = tileMapKey(row.tileId);
    var tile = catalog.byTileId[tileKey];
    if (!tile) {
      tile = {
        id: row.tileId,
        name: row.tileName,
        points: row.points,
        required: row.required,
        options: []
      };
      catalog.byTileId[tileKey] = tile;
      catalog.tiles.push(tile);
    } else if (tile.name !== row.tileName || tile.points !== row.points ||
        tile.required !== row.required) {
      throw new Error('tile_id ' + row.tileId + ' has inconsistent name, points, or required_count');
    }

    tile.options.push({ id: row.itemId, name: row.itemName });
    catalog.byItemId[row.itemId] = tile;
  }
  for (var t = 0; t < catalog.tiles.length; t++) {
    if (catalog.tiles[t].required > catalog.tiles[t].options.length) {
      throw new Error('tile_id ' + catalog.tiles[t].id +
        ' requires more items than it has options');
    }
  }
  return catalog;
}

function findOption(tile, itemId) {
  for (var i = 0; i < tile.options.length; i++) {
    if (tile.options[i].id === itemId) return tile.options[i];
  }
  return null;
}

function readClaims() {
  var values = sheet(SHEET_CLAIMS).getDataRange().getValues();
  var columns = requireColumns(values[0], SHEET_CLAIMS,
    ['team', 'tile_id', 'tile_name', 'item_id', 'item_name', 'rsn', 'claimed_at',
      'claim_id', 'source', 'progress_after', 'completed_tile']);
  var claims = { rows: [], byClaimId: {}, byTile: {} };
  for (var r = 1; r < values.length; r++) {
    var team = String(values[r][columns.team] || '').trim();
    var tileId = String(values[r][columns.tile_id] || '').trim();
    var itemId = parseInt(values[r][columns.item_id], 10);
    if (!team || !tileId || isNaN(itemId)) continue;
    var contribution = {
      team: team,
      tileId: tileId,
      tileName: String(values[r][columns.tile_name] || ''),
      itemId: itemId,
      itemName: String(values[r][columns.item_name] || ''),
      rsn: String(values[r][columns.rsn] || ''),
      claimedAt: values[r][columns.claimed_at] ?
        new Date(values[r][columns.claimed_at]).toISOString() : null,
      claimId: String(values[r][columns.claim_id] || ''),
      source: String(values[r][columns.source] || ''),
      progressAfter: parseInt(values[r][columns.progress_after], 10) || 1,
      completedTile: truthy(values[r][columns.completed_tile])
    };
    addContribution(claims, contribution);
  }
  return claims;
}

function addContribution(claims, contribution) {
  var key = claimKey(contribution.team, contribution.tileId);
  var state = claims.byTile[key];
  if (!state) {
    state = emptyClaimState();
    claims.byTile[key] = state;
  }
  if (state.byItemId[contribution.itemId]) {
    throw new Error('multiple Claims rows for team ' + contribution.team +
      ', tile_id ' + contribution.tileId + ', item_id ' + contribution.itemId);
  }
  if (contribution.claimId && claims.byClaimId[contribution.claimId]) {
    throw new Error('claim_id appears more than once: ' + contribution.claimId);
  }
  state.contributions.push(contribution);
  state.byItemId[contribution.itemId] = contribution;
  claims.rows.push(contribution);
  if (contribution.claimId) claims.byClaimId[contribution.claimId] = contribution;
  return state;
}

function emptyClaimState() {
  return { contributions: [], byItemId: {} };
}

function getClaimState(claims, team, tileId) {
  return claims.byTile[claimKey(team, tileId)] || emptyClaimState();
}

function tileComplete(tile, state) {
  return state.contributions.length >= tile.required;
}

function contributionItem(contribution) {
  return { id: contribution.itemId, name: contribution.itemName };
}

function serializeContributions(contributions) {
  var out = [];
  for (var i = 0; i < contributions.length; i++) {
    out.push({
      id: contributions[i].itemId,
      name: contributions[i].itemName,
      claimedBy: contributions[i].rsn,
      claimedAt: contributions[i].claimedAt
    });
  }
  return out;
}

function claimResult(status, replay, team, tile, contribution, state, claims, catalog) {
  var complete = tile ? tileComplete(tile, state) : contribution.completedTile;
  var resultComplete = replay ? contribution.completedTile : complete;
  var completion = tile && complete ? state.contributions[tile.required - 1] : null;
  var resultCompletion = resultComplete ? (replay ? contribution : completion) : null;
  return {
    status: status,
    replay: replay,
    team: team,
    tileId: contribution.tileId,
    tileName: contribution.tileName,
    itemId: contribution.itemId,
    itemName: contribution.itemName,
    points: status === 'claimed' && resultComplete && tile ? tile.points : 0,
    progress: status === 'progress' && replay ?
      contribution.progressAfter : state.contributions.length,
    required: tile ? tile.required : contribution.progressAfter,
    complete: resultComplete,
    claimedBy: resultCompletion ? resultCompletion.rsn : null,
    claimedAt: resultCompletion ? resultCompletion.claimedAt : null,
    claimedItems: serializeContributions(state.contributions),
    remaining: countRemaining(team, claims, catalog.tiles),
    total: catalog.tiles.length
  };
}

function claimKey(team, tileId) {
  return JSON.stringify([String(team), String(tileId)]);
}

function tileMapKey(tileId) {
  return '$' + String(tileId);
}

function countRemaining(team, claims, tiles) {
  var n = 0;
  for (var i = 0; i < tiles.length; i++) {
    if (!tileComplete(tiles[i], getClaimState(claims, team, tiles[i].id))) n++;
  }
  return n;
}

function headerMap(headers) {
  var columns = {};
  for (var i = 0; i < headers.length; i++) {
    columns[String(headers[i] || '').trim()] = i;
  }
  return columns;
}

function requireColumns(headers, sheetName, required) {
  var columns = headerMap(headers || []);
  for (var i = 0; i < required.length; i++) {
    if (columns[required[i]] == null) {
      throw new Error(sheetName + ' schema is outdated; run upgradeGroupedTiles()');
    }
  }
  return columns;
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
      JSON.stringify(sanitizeAuditPayload(payload)).slice(0, 4000),
      payload && (payload.tileId || payload.tile_id) || ''
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
  var allowed = ['action', 'rsn', 'itemId', 'itemName', 'quantity', 'source', 'claimId',
    'team', 'tileId', 'tile_id', 'item_id'];
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

function eventOpen(cfg, now) {
  var current = now || new Date();
  var start = parseEventBoundary(cfg.event_start, 'event_start');
  var end = parseEventBoundary(cfg.event_end, 'event_end');
  if (start && end && start.getTime() > end.getTime()) {
    throw new Error('event_start must be before event_end');
  }
  if (start && current.getTime() < start.getTime()) return false;
  if (end && current.getTime() > end.getTime()) return false;
  return true;
}

/**
 * Sheet date/time cells arrive as Date objects and therefore already represent an absolute
 * instant. Text boundaries are deliberately interpreted in the spreadsheet's timezone so
 * player, browser, and Apps Script timezones cannot change the event window.
 */
function parseEventBoundary(value, key) {
  if (value == null || String(value).trim() === '') return null;

  if (Object.prototype.toString.call(value) === '[object Date]') {
    if (!isNaN(value.getTime())) return value;
    throw new Error(key + ' is not a valid date/time');
  }

  var text = String(value).trim();
  var parsed;
  if (/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d{1,3})?)?(?:Z|[+-]\d{2}:\d{2})$/.test(text)) {
    parsed = new Date(text);
  } else if (/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/.test(text)) {
    var timeZone = SpreadsheetApp.getActiveSpreadsheet().getSpreadsheetTimeZone();
    parsed = Utilities.parseDate(text, timeZone, 'yyyy-MM-dd HH:mm');
  } else {
    throw new Error(key +
      ' must be a Sheet date/time or text in yyyy-MM-dd HH:mm format');
  }

  if (!parsed || isNaN(parsed.getTime())) {
    throw new Error(key + ' is not a valid date/time');
  }
  return parsed;
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
  tabs[SHEET_ITEMS] = [
    'tile_id', 'tile_name', 'item_id', 'item_name', 'points', 'required_count', 'notes'
  ];
  tabs[SHEET_TEAMS] = ['rsn', 'team'];
  tabs[SHEET_CLAIMS] = [
    'team', 'tile_id', 'tile_name', 'item_id', 'item_name', 'rsn', 'claimed_at', 'claim_id',
    'source', 'progress_after', 'completed_tile'
  ];
  tabs[SHEET_AUDIT] = ['ts', 'rsn', 'item_id', 'result', 'notes', 'raw_payload', 'tile_id'];
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
  if (headerMap(items.getDataRange().getValues()[0]).tile_id != null) {
    items.getRange('A:A').setNumberFormat('@');
  }
  if (items.getLastRow() <= 1) {
    items.appendRow(['11832', 'Bandos chestplate', 11832, 'Bandos chestplate', 1, 1, '']);
    items.appendRow(['21034', 'Dexterous prayer scroll', 21034, 'Dexterous prayer scroll', 1, 1, '']);
    items.appendRow(['4151', 'Abyssal whip', 4151, 'Abyssal whip', 1, 1, '']);
  }
  var claims = ss.getSheetByName(SHEET_CLAIMS);
  if (headerMap(claims.getDataRange().getValues()[0]).tile_id != null) {
    claims.getRange('B:B').setNumberFormat('@');
  }

  var leaderboard = ss.getSheetByName(SHEET_LEADERBOARD);
  if (!leaderboard) leaderboard = ss.insertSheet(SHEET_LEADERBOARD);
  if (leaderboard.getLastRow() === 0) {
    setupLeaderboard(leaderboard);
  }

  SpreadsheetApp.getUi().alert('Bingo with Dink Notifications tabs and leaderboard are ready. Fill in Items and Teams, then Deploy > New deployment > Web app.');
}

/**
 * Upgrades the original one-item-per-tile schema without deleting source data.
 * Existing rows remain one-of-one tiles until the organizer assigns shared tile ids and
 * required counts.
 */
function upgradeGroupedTiles() {
  var lock = LockService.getScriptLock();
  if (!lock.tryLock(LOCK_TIMEOUT_MS)) {
    throw new Error('Could not acquire the script lock');
  }
  try {
    var items = sheet(SHEET_ITEMS);
    var itemValues = items.getDataRange().getValues();
    var itemColumns = headerMap(itemValues[0]);
    if (itemColumns.tile_id == null) {
      if (itemColumns.item_id == null || itemColumns.item_name == null) {
        throw new Error('unsupported Items schema: missing item_id or item_name');
      }
      items.insertColumnsBefore(1, 2);
      items.getRange(1, 1, 1, 2).setValues([['tile_id', 'tile_name']]);
      if (itemValues.length > 1) {
        var itemBackfill = [];
        for (var i = 1; i < itemValues.length; i++) {
          itemBackfill.push([
            String(itemValues[i][itemColumns.item_id] || ''),
            String(itemValues[i][itemColumns.item_name] || '')
          ]);
        }
        items.getRange(2, 1, itemBackfill.length, 2).setValues(itemBackfill);
      }
    }
    items.getRange('A:A').setNumberFormat('@');
    itemValues = items.getDataRange().getValues();
    itemColumns = headerMap(itemValues[0]);
    if (itemColumns.required_count == null) {
      var requiredColumn = itemColumns.notes == null ? itemValues[0].length + 1 : itemColumns.notes + 1;
      items.insertColumnBefore(requiredColumn);
      items.getRange(1, requiredColumn).setValue('required_count');
      if (itemValues.length > 1) {
        var requiredBackfill = [];
        for (var q = 1; q < itemValues.length; q++) requiredBackfill.push([1]);
        items.getRange(2, requiredColumn, requiredBackfill.length, 1).setValues(requiredBackfill);
      }
    }

    var claims = sheet(SHEET_CLAIMS);
    var claimValues = claims.getDataRange().getValues();
    var claimColumns = headerMap(claimValues[0]);
    if (claimColumns.tile_id == null) {
      if (claimColumns.item_id == null || claimColumns.item_name == null) {
        throw new Error('unsupported Claims schema: missing item_id or item_name');
      }
      claims.insertColumnsAfter(1, 2);
      claims.getRange(1, 2, 1, 2).setValues([['tile_id', 'tile_name']]);
      if (claimValues.length > 1) {
        var claimBackfill = [];
        for (var c = 1; c < claimValues.length; c++) {
          claimBackfill.push([
            String(claimValues[c][claimColumns.item_id] || ''),
            String(claimValues[c][claimColumns.item_name] || '')
          ]);
        }
        claims.getRange(2, 2, claimBackfill.length, 2).setValues(claimBackfill);
      }
    }
    claims.getRange('B:B').setNumberFormat('@');
    claimValues = claims.getDataRange().getValues();
    claimColumns = headerMap(claimValues[0]);
    if (claimColumns.progress_after == null) {
      var progressColumn = claimValues[0].length + 1;
      claims.getRange(1, progressColumn, 1, 2)
        .setValues([['progress_after', 'completed_tile']]);
      if (claimValues.length > 1) {
        var progressBackfill = [];
        for (var p = 1; p < claimValues.length; p++) progressBackfill.push([1, true]);
        claims.getRange(2, progressColumn, progressBackfill.length, 2).setValues(progressBackfill);
      }
    }

    var auditSheet = sheet(SHEET_AUDIT);
    var auditValues = auditSheet.getDataRange().getValues();
    if (headerMap(auditValues[0]).tile_id == null) {
      auditSheet.getRange(1, auditValues[0].length + 1).setValue('tile_id');
    }

    SpreadsheetApp.flush();
    setupLeaderboard(sheet(SHEET_LEADERBOARD));
  } finally {
    lock.releaseLock();
  }

  SpreadsheetApp.getUi().alert(
    'Grouped-tile threshold columns were added and existing rows remain one-of-one tiles. ' +
    'Assign the same tile_id, tile_name, points, and required_count to alternative Items rows, ' +
    'then deploy a new version.'
  );
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

  sh.getRange('A1').setValue('Bingo with Dink Notifications Leaderboard');
  sh.getRange('A2').setValue(
    'Read-only view derived from Items, Teams, and Claims. Make event changes on those tabs.'
  );

  sh.getRange('A4:E4').setValues([
    ['Team', 'Completed', 'Points', 'Remaining Tiles', 'Remaining Points']
  ]);
  sh.getRange('A5').setFormula(
    '=IFERROR(SORT(UNIQUE(FILTER(Teams!B2:B,Teams!B2:B<>""))),"")'
  );
  sh.getRange('B5').setFormula(
    '=IFERROR(LET(teams,SORT(UNIQUE(FILTER(Teams!B2:B,Teams!B2:B<>""))),' +
    'tileIds,UNIQUE(FILTER(Items!A2:A,Items!A2:A<>"")),' +
    'required,MAP(tileIds,LAMBDA(tileId,LET(value,' +
    'INDEX(FILTER(Items!F2:F,Items!A2:A=tileId),1),IF(value="",1,value)))),' +
    'MAP(teams,LAMBDA(team,SUM(MAP(tileIds,required,LAMBDA(tileId,needed,' +
    'IF(COUNTUNIQUEIFS(Claims!D2:D,Claims!A2:A,team,Claims!B2:B,tileId,' +
    'Claims!D2:D,"<>")>=needed,1,0))))))),"")'
  );
  sh.getRange('C5').setFormula(
    '=IFERROR(LET(teams,SORT(UNIQUE(FILTER(Teams!B2:B,Teams!B2:B<>""))),' +
    'tileIds,UNIQUE(FILTER(Items!A2:A,Items!A2:A<>"")),' +
    'tilePoints,MAP(tileIds,LAMBDA(tileId,INDEX(FILTER(Items!E2:E,Items!A2:A=tileId),1))),' +
    'required,MAP(tileIds,LAMBDA(tileId,LET(value,' +
    'INDEX(FILTER(Items!F2:F,Items!A2:A=tileId),1),IF(value="",1,value)))),' +
    'MAP(teams,LAMBDA(team,SUM(MAP(tileIds,tilePoints,required,LAMBDA(tileId,points,needed,' +
    'IF(COUNTUNIQUEIFS(Claims!D2:D,Claims!A2:A,team,Claims!B2:B,tileId,' +
    'Claims!D2:D,"<>")>=needed,' +
    'IF(points="",1,points),0))))))),"")'
  );
  sh.getRange('D5').setFormula(
    '=IFERROR(LET(teams,SORT(UNIQUE(FILTER(Teams!B2:B,Teams!B2:B<>""))),' +
    'tileIds,UNIQUE(FILTER(Items!A2:A,Items!A2:A<>"")),' +
    'required,MAP(tileIds,LAMBDA(tileId,LET(value,' +
    'INDEX(FILTER(Items!F2:F,Items!A2:A=tileId),1),IF(value="",1,value)))),' +
    'MAP(teams,LAMBDA(team,SUM(MAP(tileIds,required,LAMBDA(tileId,needed,' +
    'IF(COUNTUNIQUEIFS(Claims!D2:D,Claims!A2:A,team,Claims!B2:B,tileId,' +
    'Claims!D2:D,"<>")<needed,1,0))))))),"")'
  );
  sh.getRange('E5').setFormula(
    '=IFERROR(LET(teams,SORT(UNIQUE(FILTER(Teams!B2:B,Teams!B2:B<>""))),' +
    'tileIds,UNIQUE(FILTER(Items!A2:A,Items!A2:A<>"")),' +
    'tilePoints,MAP(tileIds,LAMBDA(tileId,INDEX(FILTER(Items!E2:E,Items!A2:A=tileId),1))),' +
    'required,MAP(tileIds,LAMBDA(tileId,LET(value,' +
    'INDEX(FILTER(Items!F2:F,Items!A2:A=tileId),1),IF(value="",1,value)))),' +
    'MAP(teams,LAMBDA(team,SUM(MAP(tileIds,tilePoints,required,LAMBDA(tileId,points,needed,' +
    'IF(COUNTUNIQUEIFS(Claims!D2:D,Claims!A2:A,team,Claims!B2:B,tileId,' +
    'Claims!D2:D,"<>")<needed,' +
    'IF(points="",1,points),0))))))),"")'
  );

  sh.getRange('A25:CV1000').clearContent();
  sh.getRange('A25:D25').setValues([['Tile ID', 'Tile', 'Points', 'Required']]);
  sh.getRange('A26').setFormula(
    '=IFERROR(UNIQUE(FILTER({Items!A2:A,Items!B2:B,Items!E2:E,Items!F2:F},' +
    'Items!A2:A<>"")),"")'
  );
  sh.getRange('E25').setFormula(
    '=IFERROR(TRANSPOSE(SORT(UNIQUE(FILTER(Teams!B2:B,Teams!B2:B<>"")))),"")'
  );
  sh.getRange('E26').setFormula(
    '=IFERROR(LET(tileIds,UNIQUE(FILTER(Items!A2:A,Items!A2:A<>"")),' +
    'required,MAP(tileIds,LAMBDA(tileId,LET(value,' +
    'INDEX(FILTER(Items!F2:F,Items!A2:A=tileId),1),IF(value="",1,value)))),' +
    'teams,SORT(UNIQUE(FILTER(Teams!B2:B,Teams!B2:B<>""))),' +
    'MAKEARRAY(ROWS(tileIds),ROWS(teams),LAMBDA(rowIndex,columnIndex,' +
    'LET(team,INDEX(teams,columnIndex,1),tileId,INDEX(tileIds,rowIndex,1),' +
    'needed,INDEX(required,rowIndex,1),progress,COUNTUNIQUEIFS(Claims!D2:D,' +
    'Claims!A2:A,team,Claims!B2:B,tileId,Claims!D2:D,"<>"),' +
    'claimedBy,IF(progress>=needed,INDEX(FILTER(Claims!F2:F,Claims!A2:A=team,' +
    'Claims!B2:B=tileId),needed),""),winningItem,IF(progress>=needed,' +
    'INDEX(FILTER(Claims!E2:E,Claims!A2:A=team,Claims!B2:B=tileId),needed),""),' +
    'IF(progress>=needed,"✓ "&progress&"/"&needed&" "&claimedBy&" — "&winningItem,' +
    'progress&"/"&needed))))),"")'
  );

  sh.getRange('A1').setFontSize(16).setFontWeight('bold');
  sh.getRange('A2').setFontColor('#5f6368');
  sh.getRange('A4:E4').setBackground('#f1f3f4').setFontWeight('bold');
  sh.getRange('A25:CV25').setBackground('#f1f3f4').setFontWeight('bold');
  sh.getRange('B5:E24').setNumberFormat('0');
  sh.setColumnWidth(1, 90);
  sh.setColumnWidth(2, 220);
  sh.setColumnWidth(3, 70);
  sh.setColumnWidth(4, 70);
  sh.setColumnWidths(5, 2, 150);
  sh.setFrozenColumns(4);
  sh.setTabColor('#34a853');

  var claimedRule = SpreadsheetApp.newConditionalFormatRule()
    .whenTextStartsWith('✓')
    .setBackground('#d9ead3')
    .setFontColor('#274e13')
    .setRanges([sh.getRange('E26:CV1000')])
    .build();
  sh.setConditionalFormatRules([claimedRule]);
}
