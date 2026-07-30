#!/usr/bin/env bash
#
# Launch the side-loaded client with credentials created by RuneLite's official
# Jagex-account development flow. This script never reads or prints credential values.
#
# One-time setup:
#   1. /Applications/RuneLite.app/Contents/MacOS/RuneLite --configure
#   2. Add --insecure-write-credentials under "Client arguments" and save.
#   3. Launch RuneLite once through the Jagex Launcher, then close that client.
#   4. Run this script.
#
# Remove the client argument and delete ~/.runelite/credentials.properties when done.
#
set -euo pipefail
cd "$(dirname "$0")"

credentials_file="${HOME}/.runelite/credentials.properties"
jagex_runelite_app="${HOME}/Library/Application Support/Jagex Launcher/Games/Old School RuneScape/RuneLite/RuneLite.app"
standalone_runelite_app="/Applications/RuneLite.app"

if [[ "${1:-}" == "--configure" ]]; then
  if [[ -d "$jagex_runelite_app" ]]; then
    configure_app="$jagex_runelite_app"
  else
    configure_app="$standalone_runelite_app"
  fi

  if [[ ! -d "$configure_app" ]]; then
    echo "RuneLite.app was not found." >&2
    exit 1
  fi

  echo "Opening the RuneLite copy used by Jagex Launcher:"
  echo "  $configure_app"
  open -na "$configure_app" --args --configure
  cat <<'MSG'
Add --insecure-write-credentials under "Client arguments" and click Save.
Then launch RuneLite with Play in Jagex Launcher before rerunning ./jagex-dev.sh.
MSG
  exit 0
fi

if [[ ! -f "$credentials_file" ]]; then
  launcher_log="${HOME}/.runelite/logs/launcher.log"
  if [[ -f "$launcher_log" ]]; then
    last_config_line=$(grep -n 'Updated launcher configuration' "$launcher_log" |
      tail -1 | cut -d: -f1 || true)
    last_launch_line=$(grep -n 'RuneLite Launcher version' "$launcher_log" |
      tail -1 | cut -d: -f1 || true)
    last_save_error_line=$(grep -n 'unable to save launcher settings' "$launcher_log" |
      tail -1 | cut -d: -f1 || true)

    if [[ -n "$last_config_line" &&
          ${last_config_line:-0} -gt ${last_launch_line:-0} ]]; then
      cat >&2 <<'MSG'
RuneLite saved the configuration, but no RuneLite client has launched since that save.

Open Jagex Launcher, select Old School RuneScape and the intended character, select RuneLite
as the client, and click Play. Wait until RuneLite reaches the login screen or logs in, then
close it and run ./jagex-dev.sh again.

Opening /Applications/RuneLite.app directly does not receive the Jagex Account session and
cannot create credentials.properties.
MSG
      exit 1
    fi
  fi

  if [[ -n "${last_save_error_line:-}" &&
        ${last_save_error_line:-0} -gt ${last_config_line:-0} ]]; then
    cat >&2 <<'MSG'
RuneLite did not create ~/.runelite/credentials.properties.

The RuneLite log contains "unable to save launcher settings". This commonly happens when
the configuration window was started from a sandboxed terminal: the window accepts the
argument, but Save cannot write settings.json, and the Jagex-launched client subsequently
starts with "client arguments: none".

Run this from the ordinary macOS Terminal app (not a Codex task terminal):

  ./jagex-dev.sh --configure

Add --insecure-write-credentials under "Client arguments", click Save, then launch RuneLite
once through the Jagex Launcher. Close that client before running ./jagex-dev.sh again.
MSG
    exit 1
  fi

  cat >&2 <<'MSG'
No ~/.runelite/credentials.properties file was found.

Use RuneLite's documented Jagex-account development setup:
  1. In the ordinary macOS Terminal app, run: ./jagex-dev.sh --configure
  2. Add --insecure-write-credentials under "Client arguments" and save.
  3. Launch RuneLite once through the Jagex Launcher, then close that client.
  4. Run ./jagex-dev.sh again.

Treat credentials.properties like a password. Do not print, copy, or commit it.
MSG
  exit 1
fi

echo "Found RuneLite's saved Jagex credentials; starting the side-loaded client."
echo "Close any client already using this character first."
exec ./run-dev.sh "$@"
