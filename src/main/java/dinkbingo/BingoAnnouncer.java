package dinkbingo;

import dinkbingo.BingoResponses.ClaimResponse;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.PluginMessage;
import okhttp3.HttpUrl;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hands a successful claim to Dink, which renders the embed, attaches the screenshot, and
 * performs the actual webhook POST.
 * <p>
 * Announcing client-side rather than from the backend is what makes screenshot proof
 * possible — the backend has no view of the game.
 * <p>
 * Requires the user to enable Dink's <em>External Plugin Requests &gt; Enable External Plugin
 * Notifications</em>. If Dink is absent or that setting is off, the message is silently
 * dropped by Dink; set {@code announce_from_backend} on the sheet as the fallback.
 *
 * @see <a href="https://github.com/pajlads/DinkPlugin/blob/master/docs/external-plugin-messaging.md">Dink external plugin messaging</a>
 */
@Slf4j
@Singleton
public class BingoAnnouncer {

    private static final String DINK_NAMESPACE = "dink";
    private static final String DINK_NOTIFY = "notify";
    private static final String SOURCE_PLUGIN = "Dink Bingo";
    private static final String ITEM_ICON_URL = "https://static.runelite.net/cache/item/icon/";
    private static final String WIKI_SEARCH_URL = "https://oldschool.runescape.wiki/w/Special:Search?search=";

    private final EventBus eventBus;
    private final BingoConfig config;

    @Inject
    public BingoAnnouncer(EventBus eventBus, BingoConfig config) {
        this.eventBus = eventBus;
        this.config = config;
    }

    public void announce(ClaimResponse claim, String source) {
        if (claim == null || !claim.isClaimed() || claim.isReplay()) {
            // A replay is a retry of an already-announced claim; announcing again would be
            // exactly the duplicate post this plugin exists to prevent.
            return;
        }

        String itemName = claim.getItemName() != null ? claim.getItemName() : "an item";
        String team = claim.getTeam() != null ? claim.getTeam() : "their team";
        post(claim, source, itemName, team, config.notifyMessage(), "Bingo tile claimed", false);
    }

    /**
     * Exercises the real Dink external-notification and screenshot path without contacting
     * the bingo backend or changing board state. The deliberately conspicuous copy keeps a
     * test sent to a non-test webhook from looking like a genuine claim.
     */
    public void announceTest(BingoBoard board) {
        ClaimResponse claim = new ClaimResponse();
        claim.setStatus(BingoResponses.CLAIMED);
        claim.setTeam(board.isConfigured() ? board.getTeam() + " (test)" : "Test Team");
        claim.setItemId(1205); // Bronze dagger: stable icon for a synthetic notification.
        claim.setItemName("Test tile - no claim was made");
        claim.setRemaining(board.isConfigured() ? board.getRemainingCount() : 0);
        claim.setTotal(board.isConfigured() ? board.getItems().size() : 0);

        post(
            claim,
            "Manual test",
            claim.getItemName(),
            claim.getTeam(),
            "[TEST] Dink Bingo Discord and screenshot check for %USERNAME%. No board tile was claimed.",
            "Dink Bingo test",
            true
        );
    }

    private void post(
        ClaimResponse claim,
        String source,
        String itemName,
        String team,
        String text,
        String title,
        boolean test
    ) {

        Map<String, Object> data = new HashMap<>();
        data.put("sourcePlugin", SOURCE_PLUGIN);
        data.put("text", text);
        data.put("title", title);
        data.put("thumbnail", ITEM_ICON_URL + claim.getItemId() + ".png");
        data.put("imageRequested", config.sendScreenshot());

        Map<String, Object> replacements = new HashMap<>();
        replacements.put("%ITEM%", linkReplacement(itemName, WIKI_SEARCH_URL + urlEncode(itemName)));
        replacements.put("%TEAM%", textReplacement(team));
        replacements.put("%REMAINING%", textReplacement(String.valueOf(claim.getRemaining())));
        replacements.put("%SOURCE%", textReplacement(source != null ? source : "unknown"));
        data.put("replacements", replacements);

        List<Map<String, Object>> fields = new ArrayList<>(3);
        fields.add(field("Team", team, true));
        fields.add(field("Tiles Remaining", claim.getRemaining() + " / " + claim.getTotal(), true));
        if (source != null && !source.isEmpty()) {
            fields.add(field("Source", source, true));
        }
        data.put("fields", fields);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("itemId", claim.getItemId());
        metadata.put("itemName", itemName);
        metadata.put("team", team);
        metadata.put("remaining", claim.getRemaining());
        metadata.put("points", claim.getPoints());
        metadata.put("test", test);
        data.put("metadata", metadata);

        // Dink rejects the whole request unless every element is an okhttp3.HttpUrl.
        String webhook = config.bingoWebhook().trim();
        if (!webhook.isEmpty()) {
            List<HttpUrl> urls = new ArrayList<>();
            for (String candidate : webhook.split("\n")) {
                HttpUrl url = HttpUrl.parse(candidate.trim());
                if (url != null) {
                    urls.add(url);
                }
            }
            if (!urls.isEmpty()) {
                data.put("urls", urls);
            }
        }

        log.info("{} Dink notification for {}", test ? "Sending test" : "Announcing bingo claim", itemName);
        eventBus.post(new PluginMessage(DINK_NAMESPACE, DINK_NOTIFY, data));
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
        replacement.put("richValue", "[" + text + "](" + link + ")");
        return replacement;
    }

    private static String urlEncode(String text) {
        return text.replace(" ", "+");
    }
}
