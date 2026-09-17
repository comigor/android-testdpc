package dev.borges.shadow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AdminSessionLifecycleTest {
    @Before
    public void lock() {
        AdminSession.lock();
    }

    @Test
    public void nestedNavigationAndBackKeepSessionButHomeRequiresAuthentication() {
        AdminSession.authenticate();
        ActivityController<SettingsScreen> parent = screen().create().start().resume();
        parent.pause();
        ActivityController<SettingsScreen> child = screen().create().start().resume();
        parent.stop();
        assertTrue(AdminSession.isAuthenticated());
        assertTrue(child.get().contentCreated);
        child.pause();
        parent.start().resume();
        child.stop().destroy();
        assertTrue(AdminSession.isAuthenticated());
        parent.pause().stop();
        assertFalse(AdminSession.isAuthenticated());
        parent.start().resume();
        Intent prompt = shadowOf(parent.get()).getNextStartedActivityForResult().intent;
        assertEquals(PasswordActivity.class.getName(), prompt.getComponent().getClassName());
        parent.pause().stop().destroy();
    }

    @Test
    public void lockedEntryDoesNotInitializeSensitiveSettings() {
        SettingsScreen screen = screen().create().start().resume().get();
        assertFalse(screen.contentCreated);
        assertEquals(PasswordActivity.class.getName(), shadowOf(screen).getNextStartedActivityForResult().intent.getComponent().getClassName());
    }

    @Test
    public void screenOffRevokesAdministrativeAuthentication() {
        shadowOf(RuntimeEnvironment.getApplication().getSystemService(android.os.UserManager.class)).setUserUnlocked(false);
        AdminSession.authenticate();
        RuntimeEnvironment.getApplication().sendBroadcast(new Intent(Intent.ACTION_SCREEN_OFF));
        shadowOf(android.os.Looper.getMainLooper()).idle();
        assertFalse(AdminSession.isAuthenticated());
    }

    private ActivityController<SettingsScreen> screen() {
        ActivityController<SettingsScreen> controller = Robolectric.buildActivity(SettingsScreen.class);
        controller.get().setTheme(com.afwsamples.testdpc.R.style.SettingsTheme);
        return controller;
    }

    public static class SettingsScreen extends AuthenticatedActivity {
        boolean contentCreated;

        @Override
        protected void onAuthenticatedCreate(Bundle state) {
            contentCreated = true;
            TextView content = new TextView(this);
            content.setText("Administrative settings");
            setContentView(content);
        }
    }
}
