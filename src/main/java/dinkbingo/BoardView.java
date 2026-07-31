package dinkbingo;

/** What each row in the sidebar board represents. */
public enum BoardView {

    NAMED_TILES("Named Tiles"),
    POSSIBLE_ITEMS("Possible Items");

    private final String label;

    BoardView(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
