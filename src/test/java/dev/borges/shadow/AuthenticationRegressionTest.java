package dev.borges.shadow;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.ContextWrapper;
import dev.borges.shadow.util.PasswordHelper;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AuthenticationRegressionTest {
    @Test
    public void callerSuppliedExtraCannotGrantAdministrativeAccess() {
        AdminSession.lock();
        Intent intent = new Intent().putExtra("already_authenticated", true);
        try {
            Robolectric.buildActivity(SettingsActivity.class, intent).create();
        } catch (RuntimeException expected) {
            // The caller must remain unauthenticated even if credential storage is unavailable.
        }
        assertFalse(AdminSession.isAuthenticated());
    }

    @Test
    public void credentialStorageFailureCannotLookLikeFirstTimeSetup() {
        Context unreadable = new ContextWrapper(org.robolectric.RuntimeEnvironment.getApplication()) {
            @Override
            public Context getApplicationContext() {
                return this;
            }

            @Override
            public SharedPreferences getSharedPreferences(String name, int mode) {
                throw new IllegalStateException("Credential storage unavailable");
            }
        };
        boolean denied = false;
        try {
            PasswordHelper.retrievePasswordHash(unreadable);
        } catch (RuntimeException expected) {
            denied = true;
        }
        assertTrue("Unreadable credentials must fail closed, never return 'no password'", denied);
    }
}
