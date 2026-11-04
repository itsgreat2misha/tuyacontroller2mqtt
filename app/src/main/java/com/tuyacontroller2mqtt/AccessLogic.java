package com.tuyacontroller2mqtt;
// Pure decision logic for the Write Settings grant and the screen
// power keyevent, kept Android-free so the plain-JVM tests can cover
// it (see tests/run_tests.sh). The Android glue lives in
// WriteSettingsGrant and ButtonService.
public final class AccessLogic {
    private AccessLogic() {}

    // ---- Write Settings grant ----

    // Settings-screen button label for each grant state. The granted
    // label is the string the button has always shown when the grant
    // is in place; the button is now disabled in that state too.
    public static String grantButtonLabel(boolean granted) {
        return granted ? "Write Settings: granted" : "Grant Write Settings (brightness / rotation)";
    }

    // When the grant is already in place there is nothing to click.
    public static boolean grantButtonClickable(boolean granted) { return !granted; }

    // The auto-grant attempt only runs while the grant is missing.
    public static boolean shouldAttemptAutoGrant(boolean granted) { return !granted; }

    // The su argv (after the caller's "su 0" prefix) that applies the
    // grant. Settings.System.canWrite is backed by the WRITE_SETTINGS
    // app-op, so setting that op to allow for this package is what
    // flips canWrite to true; there is no runtime permission to
    // request for this special app-access.
    public static String[] autoGrantArgs(String packageName) {
        return new String[]{"appops", "set", "--uid", packageName, "WRITE_SETTINGS", "allow"};
    }

    // ---- Screen power ----

    // "input keyevent 26" is a power TOGGLE, not a set: send it only
    // when the wanted state differs from the current one. Sending it
    // blindly turns an already-off screen ON when an OFF command
    // arrives (and vice versa).
    public static boolean shouldSendPowerKeyevent(boolean wantOn, boolean interactive) {
        return wantOn != interactive;
    }
}
