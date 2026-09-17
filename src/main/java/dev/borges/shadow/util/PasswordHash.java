package dev.borges.shadow.util;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class PasswordHash {
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;
    private static final int ITERATIONS = 65536;

    private PasswordHash() {}

    public static String create(String password) {
        if (password.isEmpty()) {
            throw new IllegalArgumentException("Password must not be empty");
        }
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt) + ":"
            + Base64.getEncoder().encodeToString(derive(password, salt));
    }

    public static void validate(String encoded) {
        decode(encoded);
    }

    public static boolean matches(String password, String encoded) {
        byte[][] parts = decode(encoded);
        byte[] actual = derive(password, parts[0]);
        try {
            return MessageDigest.isEqual(parts[1], actual);
        } finally {
            Arrays.fill(actual, (byte) 0);
        }
    }

    private static byte[][] decode(String encoded) {
        try {
            String[] parts = encoded.split(":", -1);
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid credential format");
            }
            byte[] salt = Base64.getDecoder().decode(parts[0]);
            byte[] hash = Base64.getDecoder().decode(parts[1]);
            if (salt.length != SALT_BYTES || hash.length != HASH_BYTES) {
                throw new IllegalArgumentException("Invalid credential size");
            }
            return new byte[][] {salt, hash};
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Stored credential is invalid", e);
        }
    }

    private static byte[] derive(String password, byte[] salt) {
        char[] chars = password.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(chars, salt, ITERATIONS, HASH_BYTES * 8);
        Arrays.fill(chars, '\0');
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Password hashing unavailable", e);
        } finally {
            spec.clearPassword();
        }
    }
}
