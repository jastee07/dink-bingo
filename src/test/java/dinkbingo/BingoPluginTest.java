package dinkbingo;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Launches RuneLite with this plugin side-loaded, for manual end-to-end testing.
 * <p>
 * Run with {@code ./gradlew run}. Install Dink from the Plugin Hub in the launched client
 * and enable <em>External Plugin Requests &gt; Enable External Plugin Notifications</em>,
 * otherwise claims are recorded on the board but never announced.
 */
public class BingoPluginTest {

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        ExternalPluginManager.loadBuiltin(BingoPlugin.class);
        RuneLite.main(args);
    }
}
