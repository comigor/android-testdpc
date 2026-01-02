package dev.borges.shadow.util;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.util.Log;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

@TargetApi(Build.VERSION_CODES.O)
public class PasswordHelper {
    private static final String TAG = "PasswordHelper";

    private static final int SALT_LENGTH = 16;
    private static final String PREF_FILE_NAME = "secure_password_prefs";
    private static final String PASSWORD_HASH_KEY = "hashed_password";
    private static final int ITERATION_COUNT = 65536;
    private static final int KEY_LENGTH = 256;
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";

    public static String hashPassword(String password) {
        byte[] salt = generateSalt();
        byte[] hashedPassword = hashPasswordWithSalt(password, salt);
        String saltBase64 = Base64.getEncoder().encodeToString(salt);
        String hashedPasswordBase64 = Base64.getEncoder().encodeToString(hashedPassword);
        return saltBase64 + ":" + hashedPasswordBase64;
    }

    public static boolean checkPassword(Context context, String password) {
        String storedHash = retrievePasswordHash(context);
        if (storedHash == null) {
            return false;
        }

        String[] parts = storedHash.split(":");
        if (parts.length != 2) {
            Log.e(TAG, "Invalid stored hash format");
            return false;
        }

        byte[] salt;
        try {
            salt = Base64.getDecoder().decode(parts[0]);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Invalid salt format", e);
            return false;
        }
        byte[] storedHashedPassword;
        try {
            storedHashedPassword = Base64.getDecoder().decode(parts[1]);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Invalid stored hash format", e);
            return false;
        }

        byte[] hashedPassword = hashPasswordWithSalt(password, salt);
        return Arrays.equals(hashedPassword, storedHashedPassword);
    }

    public static void storePasswordHash(Context context, String passwordHash) {
        SharedPreferences sharedPreferences = getEncryptedSharedPreferences(context);
        if (sharedPreferences != null) {
            sharedPreferences.edit().putString(PASSWORD_HASH_KEY, passwordHash).apply();
        }
    }

    public static String retrievePasswordHash(Context context) {
        SharedPreferences sharedPreferences = getEncryptedSharedPreferences(context);
        return sharedPreferences != null ? sharedPreferences.getString(PASSWORD_HASH_KEY, null) : null;
    }

    private static byte[] generateSalt() {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[SALT_LENGTH];
        random.nextBytes(salt);
        return salt;
    }

    private static byte[] hashPasswordWithSalt(String password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATION_COUNT, KEY_LENGTH);
            SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITHM);
            return factory.generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            Log.e(TAG, "Error hashing", e);
            e.printStackTrace();
        }
        return null;
    }

    private static SharedPreferences getEncryptedSharedPreferences(Context context) {
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);

            return EncryptedSharedPreferences.create(
                    PREF_FILE_NAME,
                    masterKeyAlias,
                    context,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (GeneralSecurityException | IOException e) {
            Log.e(TAG, "Error creating encrypted shared preferences", e);
            e.printStackTrace();
        }
        return null;
    }

    // ============ Biometric Key Management ============
    // The key is invalidated when new fingerprints are enrolled

    private static final String BIOMETRIC_KEY_NAME = "shadow_biometric_key";

    /**
     * Generates a key in Android KeyStore that will be invalidated if new biometrics are enrolled.
     * Call this after user successfully authenticates with password.
     */
    public static void generateBiometricKey() {
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);

            // Delete existing key if any
            if (keyStore.containsAlias(BIOMETRIC_KEY_NAME)) {
                keyStore.deleteEntry(BIOMETRIC_KEY_NAME);
            }

            KeyGenerator keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");

            keyGenerator.init(new KeyGenParameterSpec.Builder(
                BIOMETRIC_KEY_NAME,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
                .build());

            keyGenerator.generateKey();
            Log.i(TAG, "Biometric key generated successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to generate biometric key", e);
        }
    }

    /**
     * Checks if the biometric key exists and is still valid.
     * Returns false if:
     * - Key doesn't exist (fingerprint never enabled)
     * - Key was invalidated (new fingerprints enrolled)
     */
    public static boolean isBiometricKeyValid() {
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);

            SecretKey key = (SecretKey) keyStore.getKey(BIOMETRIC_KEY_NAME, null);
            if (key == null) {
                Log.d(TAG, "Biometric key does not exist");
                return false;
            }

            // Try to initialize cipher - this will throw if key is invalidated
            Cipher cipher = Cipher.getInstance(
                KeyProperties.KEY_ALGORITHM_AES + "/" +
                KeyProperties.BLOCK_MODE_CBC + "/" +
                KeyProperties.ENCRYPTION_PADDING_PKCS7);
            cipher.init(Cipher.ENCRYPT_MODE, key);

            Log.d(TAG, "Biometric key is valid");
            return true;
        } catch (KeyPermanentlyInvalidatedException e) {
            Log.w(TAG, "Biometric key invalidated - new fingerprints enrolled");
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Error checking biometric key validity", e);
            return false;
        }
    }

    /**
     * Checks if biometric key exists (regardless of validity).
     * Used to determine if fingerprint was ever enabled.
     */
    public static boolean biometricKeyExists() {
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            return keyStore.containsAlias(BIOMETRIC_KEY_NAME);
        } catch (Exception e) {
            Log.e(TAG, "Error checking biometric key existence", e);
            return false;
        }
    }

    /**
     * Deletes the biometric key. Call when user wants to disable fingerprint.
     */
    public static void deleteBiometricKey() {
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            if (keyStore.containsAlias(BIOMETRIC_KEY_NAME)) {
                keyStore.deleteEntry(BIOMETRIC_KEY_NAME);
                Log.i(TAG, "Biometric key deleted");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error deleting biometric key", e);
        }
    }
}
