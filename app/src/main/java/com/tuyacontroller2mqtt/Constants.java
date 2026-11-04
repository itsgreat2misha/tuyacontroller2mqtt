package com.tuyacontroller2mqtt;
// Shared app-wide constants. The preferences file name, log tag and
// Keystore alias all changed with the 1.2.0 package rename
// (com.misha.f5daemon -> com.tuyacontroller2mqtt); Android treats the
// renamed app as a new app, so no state is read from the old names.
public final class Constants {
    public static final String TAG = "TuyaController";
    public static final String PREFS = "tuyacontroller2mqtt";
    private Constants() {}
}
