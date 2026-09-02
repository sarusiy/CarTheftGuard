package com.sarusiy.cartheftguard;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * Persists the last Wi-Fi network the board was provisioned with, encrypted at
 * rest via the platform Keystore, so the Connect screen can offer it again on
 * the next app launch instead of asking for the password every time.
 */
public final class SecureWifiStore {
    private static final String PREFS_NAME = "ctg_secure_wifi";
    private static final String KEY_SSID = "ssid";
    private static final String KEY_PASSWORD = "password";

    private final SharedPreferences prefs;

    public SecureWifiStore(Context context) {
        SharedPreferences created;
        try {
            MasterKey masterKey = new MasterKey.Builder(context.getApplicationContext())
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            created = EncryptedSharedPreferences.create(
                    context.getApplicationContext(),
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (GeneralSecurityException | IOException exception) {
            // Falls back to a plain (unencrypted) store rather than crashing the app;
            // credentials just won't survive a Keystore failure, which is rare.
            created = context.getApplicationContext()
                    .getSharedPreferences(PREFS_NAME + "_fallback", Context.MODE_PRIVATE);
        }
        prefs = created;
    }

    public boolean hasSavedCredentials() {
        return !getSsid().isEmpty();
    }

    public String getSsid() {
        return prefs.getString(KEY_SSID, "");
    }

    public String getPassword() {
        return prefs.getString(KEY_PASSWORD, "");
    }

    public void save(String ssid, String password) {
        prefs.edit().putString(KEY_SSID, ssid).putString(KEY_PASSWORD, password).apply();
    }

    public void clear() {
        prefs.edit().clear().apply();
    }
}
