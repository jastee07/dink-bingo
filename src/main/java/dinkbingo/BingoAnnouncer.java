package dinkbingo;

import dinkbingo.BingoResponses.ClaimResponse;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.PluginMessage;
import okhttp3.HttpUrl;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.regex.Pattern;

/**
 * Hands a successful claim to Dink, which renders the embed, attaches the screenshot, and
 * performs the actual webhook POST.
 * <p>
 * Announcing client-side rather than from the backend is what makes screenshot proof
 * possible — the backend has no view of the game.
 * <p>
 * Requires the user to enable Dink's <em>External Plugin Requests &gt; Enable External Plugin
 * Notifications</em>. If Dink is absent or that setting is off, the message is silently
 * dropped by Dink and the claim is announced nowhere. The claim itself is unaffected: the
 * sheet already committed it. There is no backend fallback, because a backend embed carries
 * no screenshot and is weaker proof than none.
 *
 * @see <a href="https://github.com/pajlads/DinkPlugin/blob/master/docs/external-plugin-messaging.md">Dink external plugin messaging</a>
 */
@Slf4j
@Singleton
public class BingoAnnouncer {

    private static final String DINK_NAMESPACE = "dink";
    private static final String DINK_NOTIFY = "notify";
    private static final String SOURCE_PLUGIN = "Bingo with Dink Notifications";
    private static final String ITEM_ICON_URL = "https://static.runelite.net/cache/item/icon/";
    private static final String WIKI_SEARCH_URL = "https://oldschool.runescape.wiki/w/Special:Search?search=";

    /**
     * Unmistakably a test in Discord, and phrased so it cannot be mistaken for a drop or
     * screenshotted as proof of one.
     */
    private static final String TEST_TITLE = "Bingo test notification (not a claim)";
    private static final String TEST_TEXT =
        "**TEST** \u2014 %USERNAME% is checking that bingo notifications reach this channel. "
            + "No item was dropped and no tile was claimed.";

    /**
     * Discord formatting characters that would be read as markup inside a link label rather
     * than as part of the item name. {@code ]} ends the label early and takes the link with
     * it; the rest only distort the rendered name.
     */
    private static final Pattern MARKDOWN_METACHARACTER = Pattern.compile("[\\\\`*_~|\\[\\]]");

    private final EventBus eventBus;
    private final BingoConfig config;

    @Inject
    public BingoAnnouncer(EventBus eventBus, BingoConfig config) {
        this.eventBus = eventBus;
        this.config = config;
    }

    public void announce(ClaimResponse claim, String source) {
        if (claim == null || !claim.isAnnounceable()) {
            return;
        }

        String itemName = claim.getItemName() != null ? claim.getItemName() : "an item";
        String tileName = claim.getTileName() != null ? claim.getTileName() : itemName;
        String team = claim.getTeam() != null ? claim.getTeam() : "their team";

        Map<String, Object> data = newPayload(
            claim.isProgress() ? "Bingo tile progress" : "Bingo tile completed",
            claim.isProgress() ? config.progressMessage() : config.notifyMessage());
        data.put("thumbnail", ITEM_ICON_URL + claim.getItemId() + ".png");

        Map<String, Object> replacements = new HashMap<>();
        replacements.put("%ITEM%", linkReplacement(itemName, WIKI_SEARCH_URL + urlEncode(itemName)));
        replacements.put("%TILE%", textReplacement(tileName));
        replacements.put("%TEAM%", textReplacement(team));
        replacements.put("%PROGRESS%", textReplacement(String.valueOf(claim.getProgress())));
        replacements.put("%REQUIRED%", textReplacement(String.valueOf(claim.getRequired())));
        replacements.put("%REMAINING%", textReplacement(String.valueOf(claim.getRemaining())));
        replacements.put("%SOURCE%", textReplacement(source != null ? source : "unknown"));
        data.put("replacements", replacements);

        List<Map<String, Object>> fields = new ArrayList<>(6);
        if (!tileName.equalsIgnoreCase(itemName)) {
            fields.add(field("Bingo Tile", tileName, false));
            fields.add(field(claim.isProgress() ? "Contributed Item" : "Completing Item",
                itemName, false));
        }
        fields.add(field("Progress", claim.getProgress() + " / " + claim.getRequired(), true));
        fields.add(field("Team", team, true));
        fields.add(field("Tiles Remaining", claim.getRemaining() + " / " + claim.getTotal(), true));
        if (claim.getClaimedItems() != null && !claim.getClaimedItems().isEmpty()) {
            StringJoiner credited = new StringJoiner(", ");
            for (BingoResponses.BoardClaimedItem contribution : claim.getClaimedItems()) {
                credited.add(contribution.getName());
            }
            fields.add(field("Credited Items", credited.toString(), false));
        }
        if (source != null && !source.isEmpty()) {
            fields.add(field("Source", source, true));
        }
        data.put("fields", fields);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("itemId", claim.getItemId());
        metadata.put("itemName", itemName);
        metadata.put("tileId", claim.getTileId());
        metadata.put("tileName", tileName);
        metadata.put("team", team);
        metadata.put("remaining", claim.getRemaining());
        metadata.put("points", claim.getPoints());
        metadata.put("progress", claim.getProgress());
        metadata.put("required", claim.getRequired());
        metadata.put("complete", claim.isComplete());
        data.put("metadata", metadata);

        log.info("Announcing bingo {} for {}", claim.isProgress() ? "progress" : "completion", itemName);
        send(data);
    }

    /**
     * Posts a notification that claims nothing, so a player can prove the Dink handoff works
     * before the event rather than on their first real drop.
     * <p>
     * Deliberately the same namespace, name, url selection and screenshot flag as a real
     * announcement -- a test that took a different path would verify a path nothing else
     * uses -- but it carries no item, no tile and no team, and the backend is never called.
     * <p>
     * Posting the message is not delivery. Dink acknowledges nothing, so the only proof is
     * the message appearing in Discord; callers must say so rather than report success.
     */
    public void announceTest() {
        Map<String, Object> data = newPayload(TEST_TITLE, TEST_TEXT);

        List<Map<String, Object>> fields = new ArrayList<>(1);
        fields.add(field("Test", "Configuration test \u2014 no tile was claimed and no board "
            + "was changed", false));
        data.put("fields", fields);

        Map<String, Object> metadata = new HashMap<>(1);
        metadata.put("test", true);
        data.put("metadata", metadata);

        log.info("Sending Dink test notification");
        send(data);
    }

    /**
     * The part of the payload every notification shares.
     * <p>
     * A test deliberately goes through here too: the screenshot flag and the url selection are
     * the settings most likely to be wrong, so a test that built its own payload would verify
     * a path nothing else uses.
     */
    private Map<String, Object> newPayload(String title, String text) {
        Map<String, Object> data = new HashMap<>();
        data.put("sourcePlugin", SOURCE_PLUGIN);
        data.put("title", title);
        data.put("text", text);
        data.put("imageRequested", config.sendScreenshot());
        return data;
    }

    /** The one handoff to Dink, so url selection cannot be applied to one payload and not another. */
    private void send(Map<String, Object> data) {
        applyWebhookOverride(data);
        eventBus.post(new PluginMessage(DINK_NAMESPACE, DINK_NOTIFY, data));
    }

    /**
     * Routes to the configured bingo webhook when one is set, leaving Dink's own override or
     * primary url in charge otherwise.
     * <p>
     * Dink rejects the whole request unless every element is an okhttp3.HttpUrl, and a
     * non-HTTPS webhook is dropped rather than sent in the clear.
     */
    private void applyWebhookOverride(Map<String, Object> data) {
        String webhook = config.bingoWebhook().trim();
        if (webhook.isEmpty()) {
            return;
        }
        List<HttpUrl> urls = new ArrayList<>();
        for (String candidate : webhook.split("\n")) {
            HttpUrl url = HttpUrl.parse(candidate.trim());
            if (url != null && url.isHttps()) {
                urls.add(url);
            }
        }
        if (!urls.isEmpty()) {
            data.put("urls", urls);
        }
    }

    // ------------------------------------------------------------------
    // Dink payload helpers (see Dink's docs/external-plugin-messaging.md)
    // ------------------------------------------------------------------

    private static Map<String, Object> field(String name, String value, boolean inline) {
        Map<String, Object> field = new HashMap<>(3);
        field.put("name", name);
        field.put("value", value);
        field.put("inline", inline);
        return field;
    }

    private static Map<String, String> textReplacement(String text) {
        Map<String, String> replacement = new HashMap<>(1);
        replacement.put("value", text);
        return replacement;
    }

    private static Map<String, String> linkReplacement(String text, String link) {
        Map<String, String> replacement = new HashMap<>(2);
        replacement.put("value", text);
        replacement.put("richValue", "[" + escapeMarkdown(text) + "](" + link + ")");
        return replacement;
    }

    private static String escapeMarkdown(String text) {
        return MARKDOWN_METACHARACTER.matcher(text).replaceAll("\\\\$0");
    }

    private static String urlEncode(String text) {
        // Query-string encoding, so spaces become '+' and everything else is percent-escaped.
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }
}
