package dev.borges.shadow.util;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;
import java.util.Base64;
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
}
