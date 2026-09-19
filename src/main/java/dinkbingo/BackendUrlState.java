package dinkbingo;

/**
 * What {@link BingoConfig#backendUrl()} amounts to once parsed.
 * <p>
 * {@link BingoClient} reduces the setting to a boolean for everything it does -- either there
 * is a URL to post to or there is not -- but a player staring at a board that will not load
 * needs the difference between "you have not set one", "that is not a URL", and "that URL is
 * not HTTPS, so nothing will be sent over it".
 */
public enum BackendUrlState {

    /** No backend configured. Nothing is sent anywhere until one is. */
    UNSET,

    /** Set, but not parseable as a URL at all. */
    INVALID,

    /** A URL, but not HTTPS and not loopback, so the client refuses to use it. */
    INSECURE,

    /** Usable. */
    OK;

    public boolean isUsable() {
        return this == OK;
    }
}
