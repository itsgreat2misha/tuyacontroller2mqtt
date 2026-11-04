package com.tuyacontroller2mqtt;
// Plain-JVM unit tests for the pure grant / screen-power decisions
// (AccessLogic). No Android runtime needed: compile the pure source
// file together with this test and run it, see tests/run_tests.sh.
// Exits non-zero on the first failure.
public class AccessLogicTest {
    private static int passed = 0;
    private static void check(String name, Object actual, Object expected) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            System.out.println("FAIL " + name + ": expected <" + expected + "> got <" + actual + ">");
            System.exit(1);
        }
        passed++;
    }
    private static void checkTrue(String name, boolean cond) {
        if (!cond) { System.out.println("FAIL " + name); System.exit(1); }
        passed++;
    }
    public static void main(String[] args) {
        // Grant button: the label states the grant state, and a
        // granted button is not clickable (nothing left to do).
        check("label when granted", AccessLogic.grantButtonLabel(true), "Write Settings: granted");
        check("label when not granted", AccessLogic.grantButtonLabel(false), "Grant Write Settings (brightness / rotation)");
        checkTrue("clickable when not granted", AccessLogic.grantButtonClickable(false));
        checkTrue("not clickable when granted", !AccessLogic.grantButtonClickable(true));

        // Auto-grant is attempted only while the grant is missing.
        checkTrue("attempt when not granted", AccessLogic.shouldAttemptAutoGrant(false));
        checkTrue("no attempt when granted", !AccessLogic.shouldAttemptAutoGrant(true));

        // The appops command that backs Settings.System.canWrite:
        // op name and package must be exact, or the grant silently
        // lands on the wrong target.
        String[] argv = AccessLogic.autoGrantArgs("com.tuyacontroller2mqtt");
        check("appops argc", argv.length, 6);
        check("appops verb", argv[0] + " " + argv[1], "appops set");
        check("appops uid flag", argv[2], "--uid");
        check("appops package", argv[3], "com.tuyacontroller2mqtt");
        check("appops op", argv[4], "WRITE_SETTINGS");
        check("appops mode", argv[5], "allow");

        // Power keyevent 26 is a toggle: send it exactly when the
        // wanted state differs from the current state. The two
        // no-send cases are the regression this guards: OFF to an
        // already-off panel must not turn it on, ON to an on panel
        // must not turn it off.
        checkTrue("on + off-screen -> send", AccessLogic.shouldSendPowerKeyevent(true, false));
        checkTrue("off + on-screen -> send", AccessLogic.shouldSendPowerKeyevent(false, true));
        checkTrue("on + on-screen -> skip", !AccessLogic.shouldSendPowerKeyevent(true, true));
        checkTrue("off + off-screen -> skip", !AccessLogic.shouldSendPowerKeyevent(false, false));

        System.out.println("OK: " + passed + " checks passed");
    }
}
