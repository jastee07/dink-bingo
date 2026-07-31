package dinkbingo;

import lombok.Value;
import org.jetbrains.annotations.Nullable;

/**
 * One distinct accepted item credited toward a team's logical tile.
 */
@Value
public class BingoContribution {

    int id;

    String name;

    @Nullable
    String claimedBy;

    @Nullable
    String claimedAt;
}
