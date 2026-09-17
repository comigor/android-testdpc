package dev.borges.shadow;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

public abstract class AuthenticatedActivity extends AppCompatActivity {
    private boolean initialized;
    private boolean authenticationPending;
    private Bundle initialState;
    private final ActivityResultLauncher<Intent> authentication = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(), result -> {
            authenticationPending = false;
            if (result.getResultCode() != RESULT_OK || !AdminSession.isAuthenticated()) {
                finish();
                return;
            }
            initializeAuthenticatedContent();
        });

    @Override
    protected final void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        initialState = state;
        if (AdminSession.isAuthenticated()) {
            initializeAuthenticatedContent();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!AdminSession.isAuthenticated()) {
            findViewById(android.R.id.content).setVisibility(View.INVISIBLE);
            if (!authenticationPending) {
                authenticationPending = true;
                authentication.launch(new Intent(this, PasswordActivity.class));
            }
            return;
        }
        initializeAuthenticatedContent();
    }

    @Override
    protected void onStop() {
        findViewById(android.R.id.content).setVisibility(View.INVISIBLE);
        super.onStop();
    }

    private void initializeAuthenticatedContent() {
        if (!initialized) {
            initialized = true;
            onAuthenticatedCreate(initialState);
            initialState = null;
        }
        findViewById(android.R.id.content).setVisibility(View.VISIBLE);
    }

    protected abstract void onAuthenticatedCreate(Bundle state);
}
