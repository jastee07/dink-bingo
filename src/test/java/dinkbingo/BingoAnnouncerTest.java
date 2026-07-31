package dinkbingo;

import dinkbingo.BingoResponses.ClaimResponse;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.PluginMessage;
import okhttp3.HttpUrl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BingoAnnouncerTest {

    @Mock
    private EventBus eventBus;

    @Mock
    private BingoConfig config;

    private BingoAnnouncer announcer;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(config.notifyMessage()).thenReturn("%USERNAME% claimed %ITEM% for %TEAM% — %REMAINING% tiles left");
        when(config.progressMessage()).thenReturn(
            "%USERNAME% added %ITEM% to %TILE% for %TEAM% — %PROGRESS%/%REQUIRED%");
        when(config.sendScreenshot()).thenReturn(true);
        when(config.bingoWebhook()).thenReturn("");

        announcer = new BingoAnnouncer(eventBus, config);
    }

    @Test
    void postsADinkNotifyMessageForAClaim() {
        announcer.announce(claim(BingoResponses.CLAIMED), "Abyssal demon");

        PluginMessage message = capture();
        assertEquals("dink", message.getNamespace());
        assertEquals("notify", message.getName());

        Map<String, Object> data = message.getData();
        assertEquals("Dink Bingo", data.get("sourcePlugin"));
        assertEquals("Bingo tile completed", data.get("title"));
        assertEquals(true, data.get("imageRequested"));
        assertEquals("https://static.runelite.net/cache/item/icon/4151.png", data.get("thumbnail"));
        assertTrue(String.valueOf(data.get("text")).contains("%ITEM%"));
    }

    @Test
    void suppliesReplacementsForEveryTemplateToken() {
        announcer.announce(claim(BingoResponses.CLAIMED), "Abyssal demon");

        @SuppressWarnings("unchecked")
        Map<String, Map<String, String>> replacements =
            (Map<String, Map<String, String>>) capture().getData().get("replacements");

        assertEquals("Abyssal whip", replacements.get("%ITEM%").get("value"));
        assertTrue(replacements.get("%ITEM%").get("richValue").contains("oldschool.runescape.wiki"));
        assertEquals("Any rare drop", replacements.get("%TILE%").get("value"));
        assertEquals("Team One", replacements.get("%TEAM%").get("value"));
        assertEquals("2", replacements.get("%PROGRESS%").get("value"));
        assertEquals("2", replacements.get("%REQUIRED%").get("value"));
        assertEquals("3", replacements.get("%REMAINING%").get("value"));
        assertEquals("Abyssal demon", replacements.get("%SOURCE%").get("value"));
    }

    @Test
    void includesTheLogicalTileAndWinningItemAsEvidence() {
        announcer.announce(claim(BingoResponses.CLAIMED), "Abyssal demon");

        Map<String, Object> data = capture().getData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) data.get("fields");
        assertTrue(fields.stream().anyMatch(field ->
            "Bingo Tile".equals(field.get("name")) && "Any rare drop".equals(field.get("value"))));
        assertTrue(fields.stream().anyMatch(field ->
            "Completing Item".equals(field.get("name")) && "Abyssal whip".equals(field.get("value"))));
        assertTrue(fields.stream().anyMatch(field ->
            "Progress".equals(field.get("name")) && "2 / 2".equals(field.get("value"))));
        assertTrue(fields.stream().anyMatch(field ->
            "Credited Items".equals(field.get("name"))
                && "Bandos chestplate, Abyssal whip".equals(field.get("value"))));
    }

    @Test
    void postsADinkNotifyMessageForAcceptedProgress() {
        ClaimResponse response = claim(BingoResponses.PROGRESS);
        response.setProgress(1);
        response.setComplete(false);
        response.setPoints(0);

        announcer.announce(response, "Abyssal demon");

        Map<String, Object> data = capture().getData();
        assertEquals("Bingo tile progress", data.get("title"));
        assertEquals(config.progressMessage(), data.get("text"));
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) data.get("metadata");
        assertEquals(0, metadata.get("points"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) data.get("fields");
        assertTrue(fields.stream().anyMatch(field ->
            "Contributed Item".equals(field.get("name")) && "Abyssal whip".equals(field.get("value"))));
    }

    /** Dink drops the entire request unless every url is an okhttp3.HttpUrl instance. */
    @Test
    void passesWebhookOverrideAsHttpUrlInstances() {
        when(config.bingoWebhook()).thenReturn("https://discord.com/api/webhooks/a/b");

        announcer.announce(claim(BingoResponses.CLAIMED), "Abyssal demon");

        Object urls = capture().getData().get("urls");
        assertTrue(urls instanceof List);
        for (Object url : (List<?>) urls) {
            assertTrue(url instanceof HttpUrl, "Dink rejects url entries that are not HttpUrl");
        }
    }

    @Test
    void omitsUrlsWhenNoOverrideIsSet() {
        announcer.announce(claim(BingoResponses.CLAIMED), "Abyssal demon");
        assertFalse(capture().getData().containsKey("urls"));
    }

    @Test
    void omitsAnInsecureWebhookOverride() {
        when(config.bingoWebhook()).thenReturn("http://example.com/webhook");
        announcer.announce(claim(BingoResponses.CLAIMED), "Abyssal demon");
        assertFalse(capture().getData().containsKey("urls"));
    }

    @Test
    void doesNotAnnounceADuplicate() {
        announcer.announce(claim(BingoResponses.DUPLICATE), "Abyssal demon");
        verify(eventBus, never()).post(any());
    }

    @Test
    void doesNotAnnounceWhenTheRsnIsNotOnATeam() {
        announcer.announce(claim(BingoResponses.NOT_ON_TEAM), "Abyssal demon");
        verify(eventBus, never()).post(any());
    }

    @Test
    void announcesAReplayReturnedToTheOriginalInFlightClaim() {
        ClaimResponse response = claim(BingoResponses.CLAIMED);
        response.setReplay(true);

        announcer.announce(response, "Abyssal demon");

        assertEquals("notify", capture().getName());
    }

    @Test
    void toleratesANullResponse() {
        announcer.announce(null, "Abyssal demon");
        verify(eventBus, never()).post(any());
    }

    // ------------------------------------------------------------------

    private PluginMessage capture() {
        ArgumentCaptor<PluginMessage> captor = ArgumentCaptor.forClass(PluginMessage.class);
        verify(eventBus).post(captor.capture());
        return captor.getValue();
    }

    private static ClaimResponse claim(String status) {
        ClaimResponse response = new ClaimResponse();
        response.setStatus(status);
        response.setTeam("Team One");
        response.setTileId("rare-drop");
        response.setTileName("Any rare drop");
        response.setItemId(4151);
        response.setItemName("Abyssal whip");
        response.setPoints(1);
        response.setProgress(2);
        response.setRequired(2);
        response.setComplete(true);
        response.setRemaining(3);
        response.setTotal(10);
        BingoResponses.BoardClaimedItem first = new BingoResponses.BoardClaimedItem();
        first.setId(11832);
        first.setName("Bandos chestplate");
        BingoResponses.BoardClaimedItem second = new BingoResponses.BoardClaimedItem();
        second.setId(4151);
        second.setName("Abyssal whip");
        response.setClaimedItems(java.util.Arrays.asList(first, second));
        return response;
    }
}
