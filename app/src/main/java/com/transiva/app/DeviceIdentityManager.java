package com.transiva.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

public final class DeviceIdentityManager {
    private static final String PREF_NAME = "transiva_device_identity";
    private static final String KEY_UUID = "installation_uuid";
    private DeviceIdentityManager() {}

    public static String getInstallationUuid(Context context) {
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String value = prefs.getString(KEY_UUID, "");
        if (value != null && !value.trim().isEmpty()) return value.trim();
        String generated = UUID.randomUUID().toString();
        prefs.edit().putString(KEY_UUID, generated).commit();
        return generated;
    }
}
