package dinkbingo;

/** Which tiles the sidebar shows, by how far along they are. */
public enum BoardProgressFilter {

    ALL("All"),

    /** Nothing has been credited toward the tile yet. */
    OPEN("Open"),

    /** At least one item is credited, but the tile is not finished. */
    PARTIAL("In progress"),

    COMPLETED("Completed");

    private final String label;

    BoardProgressFilter(String label) {
        this.label = label;
    }

    /**
     * Whether the tile belongs in this bucket.
     * <p>
     * A tile counts as in progress on either signal the backend gives us: a non-zero
     * {@code progress}, or a credited contribution. A backend that reports one without the
     * other still sorts and filters the same way.
     */
    public boolean matches(BingoTile tile) {
        switch (this) {
            case OPEN:
                return !tile.isClaimed() && !isPartial(tile);
            case PARTIAL:
                return !tile.isClaimed() && isPartial(tile);
            case COMPLETED:
                return tile.isClaimed();
            default:
                return true;
        }
    }

    private static boolean isPartial(BingoTile tile) {
        return tile.getProgress() > 0 || !tile.getClaimedItems().isEmpty();
    }

    @Override
    public String toString() {
        return label;
    }
}
