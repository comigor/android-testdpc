package dev.borges.shadow.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

public final class PasswordHelper {
    private static final String TAG = "PasswordHelper";
    private static final String PREF_FILE_NAME = "secure_password_prefs";
    private static final String PASSWORD_HASH_KEY = "hashed_password";
    private static final String BIOMETRIC_KEY_NAME = "shadow_biometric_key";

    private PasswordHelper() {}

    public static boolean checkPassword(Context context, String password) {
        String stored = retrievePasswordHash(context);
        return stored != null && PasswordHash.matches(password, stored);
    }

    public static void setPassword(Context context, String password) {
        SharedPreferences preferences = getEncryptedSharedPreferences(context);
        String encoded = PasswordHash.create(password);
        if (!preferences.edit().putString(PASSWORD_HASH_KEY, encoded).commit()) {
            throw new IllegalStateException("Could not save password");
        }
    }

    public static String retrievePasswordHash(Context context) {
        String encoded = getEncryptedSharedPreferences(context).getString(PASSWORD_HASH_KEY, null);
        if (encoded != null) {
            PasswordHash.validate(encoded);
        }
        return encoded;
    }

    private static SharedPreferences getEncryptedSharedPreferences(Context context) {
        try {
            String alias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            return EncryptedSharedPreferences.create(PREF_FILE_NAME, alias, context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("Password storage unavailable", e);
        }
    }

    public static void generateBiometricKey() {
        try {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(BIOMETRIC_KEY_NAME,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
                .build());
            generator.generateKey();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not enable biometric login", e);
        }
    }

    public static Cipher createBiometricCipher() {
        try {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            SecretKey key = (SecretKey) store.getKey(BIOMETRIC_KEY_NAME, null);
            if (key == null) {
                throw new IllegalStateException("Biometric login is not enabled");
            }
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS7Padding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            return cipher;
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("Biometric key unavailable; sign in with your password", e);
        }
    }

    public static boolean isBiometricKeyValid() {
        try {
            createBiometricCipher();
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    public static boolean biometricKeyExists() {
        try {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            return store.containsAlias(BIOMETRIC_KEY_NAME);
        } catch (GeneralSecurityException | IOException e) {
            Log.e(TAG, "Cannot inspect biometric key", e);
            return false;
        }
    }

    public static void deleteBiometricKey() {
        try {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            store.deleteEntry(BIOMETRIC_KEY_NAME);
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("Could not disable biometric login", e);
        }
    }
}
