package com.termux.ai;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * Manages encrypted SharedPreferences for secure storage of sensitive data.
 *
 * Uses AndroidX Security Crypto library (AES-256-GCM) to encrypt both keys
 * and values. Falls back gracefully to standard SharedPreferences if the
 * crypto library fails (known issue on some Android 7-8 devices with the
 * alpha security-crypto library).
 */
public class EncryptedPreferencesManager {
    private static final String TAG = "EncryptedPrefs";

    /**
     * Get or create encrypted SharedPreferences instance.
     * Falls back to standard SharedPreferences if encryption setup fails,
     * rather than crashing the app.
     *
     * @param context Application context
     * @param prefName Name of the preferences file
     * @return Encrypted or fallback SharedPreferences instance
     */
    public static SharedPreferences getEncryptedPrefs(Context context, String prefName) {
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build();

            SharedPreferences encPrefs = EncryptedSharedPreferences.create(
                context,
                prefName,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );

            // Verify we can actually read/write (catches corrupted keystores)
            encPrefs.getAll();
            Log.i(TAG, "Encrypted SharedPreferences initialized successfully for: " + prefName);
            return encPrefs;

        } catch (GeneralSecurityException | IOException e) {
            Log.e(TAG, "Failed to create encrypted preferences, falling back to standard", e);
            return getFallbackPrefs(context, prefName);
        } catch (Exception e) {
            // Catch-all for unexpected issues (corrupted keystore, StrongBox failures, etc.)
            Log.e(TAG, "Unexpected error creating encrypted preferences, falling back", e);
            return getFallbackPrefs(context, prefName);
        }
    }

    /**
     * Fallback to standard SharedPreferences when encryption isn't available.
     * Better than crashing. The API key is still protected by Android's
     * app sandboxing (other apps can't read it without root).
     */
    private static SharedPreferences getFallbackPrefs(Context context, String prefName) {
        Log.w(TAG, "Using unencrypted SharedPreferences for: " + prefName +
            ". Credentials are protected by app sandbox but not encrypted at rest.");
        return context.getSharedPreferences(prefName + "_fallback", Context.MODE_PRIVATE);
    }

    /**
     * Migrate existing plaintext preferences to encrypted storage.
     *
     * IMPORTANT: plaintextPrefName and encryptedPrefName MUST be different,
     * otherwise this is a no-op (reads and writes the same file).
     *
     * @param context Application context
     * @param plaintextPrefName Name of the old plaintext preferences file
     * @param encryptedPrefName Name of the new encrypted preferences file
     * @return true if migration successful or unnecessary, false on error
     */
    public static boolean migratePlaintextToEncrypted(Context context,
                                                       String plaintextPrefName,
                                                       String encryptedPrefName) {
        // Guard: if names are the same, migration is impossible
        if (plaintextPrefName.equals(encryptedPrefName)) {
            Log.w(TAG, "Migration source and destination are the same name ('" +
                plaintextPrefName + "'). Skipping migration — this is a configuration error.");
            return false;
        }

        try {
            SharedPreferences plaintextPrefs = context.getSharedPreferences(
                plaintextPrefName, Context.MODE_PRIVATE);

            // Nothing to migrate
            if (plaintextPrefs.getAll().isEmpty()) {
                return true;
            }

            Log.i(TAG, "Migrating " + plaintextPrefs.getAll().size() +
                " entries from '" + plaintextPrefName + "' to '" + encryptedPrefName + "'");

            SharedPreferences encryptedPrefs = getEncryptedPrefs(context, encryptedPrefName);
            SharedPreferences.Editor editor = encryptedPrefs.edit();

            for (String key : plaintextPrefs.getAll().keySet()) {
                Object value = plaintextPrefs.getAll().get(key);

                if (value instanceof String) {
                    editor.putString(key, (String) value);
                } else if (value instanceof Integer) {
                    editor.putInt(key, (Integer) value);
                } else if (value instanceof Boolean) {
                    editor.putBoolean(key, (Boolean) value);
                } else if (value instanceof Float) {
                    editor.putFloat(key, (Float) value);
                } else if (value instanceof Long) {
                    editor.putLong(key, (Long) value);
                }
            }

            boolean success = editor.commit();

            if (success) {
                // Wipe the old plaintext file
                plaintextPrefs.edit().clear().commit();
                Log.i(TAG, "Migration successful. Plaintext preferences cleared.");
            } else {
                Log.e(TAG, "Migration commit failed. Plaintext preferences preserved.");
            }

            return success;

        } catch (Exception e) {
            Log.e(TAG, "Migration failed", e);
            return false;
        }
    }

    /**
     * Check if encrypted preferences are accessible.
     */
    public static boolean isEncryptedPrefsAccessible(Context context, String prefName) {
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build();

            SharedPreferences prefs = EncryptedSharedPreferences.create(
                context, prefName, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );

            prefs.getAll();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Encrypted preferences not accessible", e);
            return false;
        }
    }
}
