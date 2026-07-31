package dinkbingo;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup(BingoConfig.GROUP)
public interface BingoConfig extends Config {

    String GROUP = "dinkbingo";

    @ConfigSection(
        name = "Connection",
        description = "Where the bingo board lives",
        position = 0
    )
    String connectionSection = "Connection";

    @ConfigSection(
        name = "Detection",
        description = "What counts as a bingo drop",
        position = 10
    )
    String detectionSection = "Detection";

    @ConfigSection(
        name = "Announcements",
        description = "How accepted progress and completions are announced via Dink",
        position = 20
    )
    String announceSection = "Announcements";

    // -----------------------------------------------------------------------
    // Connection
    // -----------------------------------------------------------------------

    @ConfigItem(
        keyName = "backendUrl",
        name = "Backend URL",
        description = "The HTTPS Apps Script web app URL for your event (ends in /exec).<br/>" +
            "Nothing is sent anywhere until this is set.",
        position = 1,
        section = connectionSection
    )
    default String backendUrl() {
        return "";
    }

    @ConfigItem(
        keyName = "eventToken",
        name = "Event Token",
        description = "The shared token from your board's Config tab",
        position = 2,
        section = connectionSection,
        secret = true
    )
    default String eventToken() {
        return "";
    }

    @Range(min = 1, max = 60)
    @ConfigItem(
        keyName = "refreshMinutes",
        name = "Refresh Interval (minutes)",
        description = "How often the board is re-fetched so teammates' claims show up",
        position = 3,
        section = connectionSection
    )
    default int refreshMinutes() {
        return 5;
    }

    // -----------------------------------------------------------------------
    // Detection
    // -----------------------------------------------------------------------

    @ConfigItem(
        keyName = "includeCollectionLog",
        name = "Include Collection Log",
        description = "Also treat a new collection log entry as a bingo drop.<br/>" +
            "Useful for items that are equipped or banked rather than picked up as loot",
        position = 11,
        section = detectionSection
    )
    default boolean includeCollectionLog() {
        return true;
    }

    @ConfigItem(
        keyName = "includePlayerLoot",
        name = "Include PK Loot",
        description = "Whether items taken from other players can claim a tile",
        position = 12,
        section = detectionSection
    )
    default boolean includePlayerLoot() {
        return false;
    }

    // -----------------------------------------------------------------------
    // Announcements
    // -----------------------------------------------------------------------

    @ConfigItem(
        keyName = "sendScreenshot",
        name = "Send Screenshot",
        description = "Ask Dink to attach a screenshot as proof of the drop.<br/>" +
            "Dink's own 'External Plugin Requests > Send Image' setting can override this",
        position = 21,
        section = announceSection
    )
    default boolean sendScreenshot() {
        return true;
    }

    @ConfigItem(
        keyName = "showVerificationOverlay",
        name = "Show Verification Overlay",
        description = "Show the current date, time, time zone, and bingo code in the game view.<br/>" +
            "This remains visible until the setting is turned off",
        position = 22,
        section = announceSection
    )
    default boolean showVerificationOverlay() {
        return false;
    }

    @ConfigItem(
        keyName = "verificationCode",
        name = "Bingo Verification Code",
        description = "The organizer-provided code shown in the verification overlay.<br/>" +
            "Update this at the start of each bingo",
        position = 23,
        section = announceSection
    )
    default String verificationCode() {
        return "";
    }

    @ConfigItem(
        keyName = "bingoWebhook",
        name = "Bingo Webhook Override",
        description = "If non-empty, bingo updates are sent to this Discord webhook.<br/>" +
            "Otherwise Dink's 'External Webhook Override' (or primary URL) is used",
        position = 24,
        section = announceSection,
        secret = true
    )
    default String bingoWebhook() {
        return "";
    }

    @ConfigItem(
        keyName = "notifyMessage",
        name = "Completion Message",
        description = "Used when a contribution completes a tile.<br/>" +
            "%USERNAME%, %ITEM%, %TILE%, %TEAM%, %PROGRESS%, %REQUIRED%, " +
            "%REMAINING% and %SOURCE% are replaced",
        position = 25,
        section = announceSection
    )
    default String notifyMessage() {
        return "%USERNAME% claimed %ITEM% for %TEAM% — %REMAINING% tiles left";
    }

    @ConfigItem(
        keyName = "progressMessage",
        name = "Progress Message",
        description = "Used when a distinct item advances a threshold tile without completing it.<br/>" +
            "%USERNAME%, %ITEM%, %TILE%, %TEAM%, %PROGRESS%, %REQUIRED%, " +
            "%REMAINING% and %SOURCE% are replaced",
        position = 26,
        section = announceSection
    )
    default String progressMessage() {
        return "%USERNAME% added %ITEM% to %TILE% for %TEAM% — %PROGRESS%/%REQUIRED%";
    }

    @ConfigItem(
        keyName = "chatMessageOnClaim",
        name = "Game Chat Confirmation",
        description = "Print the claim result to your game chat so you know it registered",
        position = 27,
        section = announceSection
    )
    default boolean chatMessageOnClaim() {
        return true;
    }
}
