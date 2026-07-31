# Security policy

## Reporting a vulnerability

Please report vulnerabilities privately through
[GitHub Security Advisories](https://github.com/jastee07/dink-bingo/security/advisories/new).
Do not open a public issue containing an event token, admin token, Discord webhook, deployed
Apps Script URL, private Sheet URL, or player data.

Include the affected version or commit, reproduction steps, and the impact you observed.

## Credential boundaries

- The event token is a shared participant credential. Give it only to event participants.
- The admin token is organizer-only and must never be shared with participants.
- Discord webhooks remain in the organizer-owned Sheet or the individual player's secret
  RuneLite/Dink configuration.
- Players should receive only the deployed `/exec` URL and event token. Do not share access
  to the organizer's editable spreadsheet.
- Hiding the `Config` tab is cosmetic and is not a security boundary.

If a token or webhook may have leaked, rotate it before continuing the event.
