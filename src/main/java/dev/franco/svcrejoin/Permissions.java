package dev.franco.svcrejoin;

/** Permission nodes declared in {@code plugin.yml}. All default to OP. */
public final class Permissions {

    public static final String USE = "voicechat.admin.reconnect";
    public static final String OTHERS = "voicechat.admin.reconnect.others";
    public static final String BYPASS_COOLDOWN = "voicechat.admin.reconnect.bypasscooldown";
    public static final String RELOAD = "voicechat.admin.reconnect.reload";

    private Permissions() {
    }
}
