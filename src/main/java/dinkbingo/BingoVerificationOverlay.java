package dinkbingo;

import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Persistent, organizer-controlled proof that a Dink screenshot came from the current bingo.
 * The timestamp uses the player's local time zone and updates whenever RuneLite renders a frame.
 */
@Singleton
public class BingoVerificationOverlay extends OverlayPanel {

    private static final Color ACCENT = new Color(0x00, 0xB0, 0x50);
    private static final int WIDTH = 240;
    private static final DateTimeFormatter DATE_FORMAT =
        DateTimeFormatter.ofPattern("MM/dd/uuuu");
    private static final DateTimeFormatter TIME_FORMAT =
        DateTimeFormatter.ofPattern("h:mm a z");

    private final BingoConfig config;
    private final Clock clock;

    @Inject
    public BingoVerificationOverlay(BingoConfig config) {
        this(config, Clock.systemDefaultZone());
    }

    BingoVerificationOverlay(BingoConfig config, Clock clock) {
        this.config = config;
        this.clock = clock;
        setPosition(OverlayPosition.TOP_LEFT);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setPreferredSize(new Dimension(WIDTH, 0));
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (!config.showVerificationOverlay()) {
            return null;
        }

        panelComponent.getChildren().add(LineComponent.builder()
            .left("Bingo verification")
            .leftColor(ACCENT)
            .build());
        ZonedDateTime now = ZonedDateTime.now(clock);
        panelComponent.getChildren().add(LineComponent.builder()
            .left(formatDate(now))
            .build());
        panelComponent.getChildren().add(LineComponent.builder()
            .left(formatTime(now))
            .build());
        panelComponent.getChildren().add(LineComponent.builder()
            .left("Code: " + verificationCodeText())
            .build());

        return super.render(graphics);
    }

    String currentDateText() {
        return formatDate(ZonedDateTime.now(clock));
    }

    String currentTimeText() {
        return formatTime(ZonedDateTime.now(clock));
    }

    String verificationCodeText() {
        String code = config.verificationCode();
        if (code == null || code.trim().isEmpty()) {
            return "Not configured";
        }
        return code.trim();
    }

    private static String formatDate(ZonedDateTime dateTime) {
        return DATE_FORMAT.format(dateTime);
    }

    private static String formatTime(ZonedDateTime dateTime) {
        return TIME_FORMAT.format(dateTime);
    }
}
