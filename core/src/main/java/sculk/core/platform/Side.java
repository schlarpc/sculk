package sculk.core.platform;

/** Which end of a game connection this process is. */
public enum Side {
    /** We are the client; inbound traffic comes from a server. */
    CLIENT,
    /** We are the server; inbound traffic comes from a client. */
    SERVER
}
