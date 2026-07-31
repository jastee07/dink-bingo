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
        assertEquals("Bingo tile claimed", data.get("title"));
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
        assertEquals("Team One", replacements.get("%TEAM%").get("value"));
        assertEquals("3", replacements.get("%REMAINING%").get("value"));
        assertEquals("Abyssal demon", replacements.get("%SOURCE%").get("value"));
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

    @Test
    void manualTestUsesTheRealScreenshotPathWithoutLookingLikeAClaim() {
        announcer.announceTest(BingoBoard.EMPTY);

        Map<String, Object> data = capture().getData();
        assertEquals("Dink Bingo test", data.get("title"));
        assertEquals(true, data.get("imageRequested"));
        assertTrue(String.valueOf(data.get("text")).startsWith("[TEST]"));

        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) data.get("metadata");
        assertEquals(true, metadata.get("test"));
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
        response.setItemId(4151);
        response.setItemName("Abyssal whip");
        response.setPoints(1);
        response.setRemaining(3);
        response.setTotal(10);
        return response;
    }
}
