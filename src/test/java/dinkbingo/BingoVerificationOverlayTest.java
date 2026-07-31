package dinkbingo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

class BingoVerificationOverlayTest {

    @Mock
    private BingoConfig config;

    private Graphics2D graphics;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        graphics = new BufferedImage(400, 200, BufferedImage.TYPE_INT_ARGB).createGraphics();
    }

    @Test
    void doesNotRenderWhenDisabled() {
        when(config.showVerificationOverlay()).thenReturn(false);

        BingoVerificationOverlay overlay = overlayAtKnownTime();

        assertNull(overlay.render(graphics));
    }

    @Test
    void rendersContinuouslyWhenEnabled() {
        when(config.showVerificationOverlay()).thenReturn(true);
        when(config.verificationCode()).thenReturn(" SUMMER26 ");

        BingoVerificationOverlay overlay = overlayAtKnownTime();
        // PanelComponent measures its children on its first frame, then uses the measured
        // dimensions on subsequent frames.
        overlay.render(graphics);
        Dimension first = overlay.render(graphics);
        Dimension second = overlay.render(graphics);

        assertNotNull(first);
        assertNotNull(second);
        assertEquals(first, second);
        assertEquals("SUMMER26", overlay.verificationCodeText());
        assertEquals("07/30/2026", overlay.currentDateText());
        assertEquals("5:42 PM EDT", overlay.currentTimeText());
    }

    @Test
    void makesAMissingCodeObvious() {
        when(config.verificationCode()).thenReturn("  ");

        assertEquals("Not configured", overlayAtKnownTime().verificationCodeText());
    }

    private BingoVerificationOverlay overlayAtKnownTime() {
        Clock clock = Clock.fixed(
            Instant.parse("2026-07-30T21:42:18Z"),
            ZoneId.of("America/Kentucky/Louisville")
        );
        return new BingoVerificationOverlay(config, clock);
    }
}
