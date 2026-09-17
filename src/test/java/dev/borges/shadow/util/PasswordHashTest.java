package dev.borges.shadow.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.junit.Test;

public class PasswordHashTest {
    @Test
    public void existingPasswordsAndUnicodeRemainValid() throws Exception {
        byte[] salt = new byte[16];
        java.util.Arrays.fill(salt, (byte) 42);
        String password = "senha ç日本語";
        byte[] legacy = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(new PBEKeySpec(password.toCharArray(), salt, 65536, 256)).getEncoded();
        String encoded = Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(legacy);
        assertTrue(PasswordHash.matches(password, encoded));
        assertFalse(PasswordHash.matches(password + "x", encoded));
    }

    @Test
    public void identicalPinsUseIndependentSalts() {
        String first = PasswordHash.create("135790");
        String second = PasswordHash.create("135790");
        assertNotEquals(first, second);
        assertTrue(PasswordHash.matches("135790", first));
        assertTrue(PasswordHash.matches("135790", second));
    }

    @Test
    public void malformedCredentialsFailClosedRatherThanMatchEmptyPassword() {
        assertThrows(IllegalStateException.class, () -> PasswordHash.matches("", ":"));
        assertThrows(IllegalStateException.class, () -> PasswordHash.matches("", "invalid"));
        assertThrows(IllegalArgumentException.class, () -> PasswordHash.create(""));
    }
}
