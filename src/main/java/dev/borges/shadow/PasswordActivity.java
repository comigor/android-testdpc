package dev.borges.shadow;

import android.content.Intent;
import android.os.Bundle;
import android.os.UserManager;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;
import com.afwsamples.testdpc.R;
import dev.borges.shadow.util.PasswordHelper;
import java.security.GeneralSecurityException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import javax.crypto.Cipher;

public class PasswordActivity extends FragmentActivity {
    public static final String EXTRA_LAUNCH_SETTINGS_ON_SUCCESS = "launch_settings_on_success";
    private enum Mode { LOGIN, SETUP, CHANGE }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private EditText currentPassword;
    private EditText newPassword;
    private EditText confirmation;
    private TextView title;
    private TextView status;
    private Button submit;
    private Button changePassword;
    private Button biometric;
    private BiometricPrompt biometricPrompt;
    private Cipher biometricCipher;
    private Mode mode = Mode.LOGIN;
    private boolean busy;
    private boolean storageAvailable;
    private boolean biometricAvailable;
    private boolean foreground;
    private boolean launchSettings;
    private int generation;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_password);
        launchSettings = getIntent().getBooleanExtra(EXTRA_LAUNCH_SETTINGS_ON_SUCCESS, false);
        title = findViewById(R.id.passwordTitle);
        status = findViewById(R.id.passwordStatus);
        currentPassword = findViewById(R.id.etCurrentPassword);
        newPassword = findViewById(R.id.etPassword);
        confirmation = findViewById(R.id.etConfirmPassword);
        submit = findViewById(R.id.btnSubmit);
        changePassword = findViewById(R.id.btnChangePassword);
        biometric = findViewById(R.id.btnUseFingerprint);
        submit.setOnClickListener(v -> submitPassword());
        changePassword.setOnClickListener(v -> {
            mode = mode == Mode.CHANGE ? Mode.LOGIN : Mode.CHANGE;
            clearFields();
            status.setText("");
            render();
        });
        biometric.setOnClickListener(v -> authenticateWithBiometrics());
        currentPassword.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE && mode == Mode.LOGIN) {
                submitPassword();
                return true;
            }
            return false;
        });
        confirmation.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submitPassword();
                return true;
            }
            return false;
        });
        biometricPrompt = new BiometricPrompt(this, ContextCompat.getMainExecutor(this),
            new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                    if (!foreground || !storageAvailable) return;
                    try {
                        BiometricPrompt.CryptoObject crypto = result.getCryptoObject();
                        if (crypto == null || crypto.getCipher() != biometricCipher) {
                            throw new GeneralSecurityException("Missing biometric key authorization");
                        }
                        crypto.getCipher().doFinal(new byte[] {1});
                        authenticated();
                    } catch (GeneralSecurityException e) {
                        showError("Biometric authorization failed. Use your Shadow password.");
                    }
                }

                @Override
                public void onAuthenticationError(int code, @NonNull CharSequence message) {
                    busy = false;
                    biometricCipher = null;
                    if (foreground) {
                        status.setText(message);
                        render();
                    }
                }

                @Override
                public void onAuthenticationFailed() {
                    status.setText(R.string.shadow_biometric_failed);
                }
            });
    }

    @Override
    protected void onResume() {
        super.onResume();
        foreground = true;
        if (!busy) loadCredentialState();
    }

    @Override
    protected void onStop() {
        foreground = false;
        generation++;
        busy = false;
        biometricCipher = null;
        biometricPrompt.cancelAuthentication();
        clearFields();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private void loadCredentialState() {
        runCredentialOperation(() -> {
            boolean exists = PasswordHelper.retrievePasswordHash(this) != null;
            boolean available = exists && getSystemService(UserManager.class).isSystemUser()
                && PasswordHelper.isBiometricKeyValid()
                && BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    == BiometricManager.BIOMETRIC_SUCCESS;
            return () -> {
                storageAvailable = true;
                biometricAvailable = available;
                mode = exists ? (mode == Mode.SETUP ? Mode.LOGIN : mode) : Mode.SETUP;
                status.setText("");
            };
        });
    }

    private void submitPassword() {
        if (busy || !storageAvailable) return;
        String current = currentPassword.getText().toString();
        String replacement = newPassword.getText().toString();
        String repeated = confirmation.getText().toString();
        Mode requested = mode;
        if ((requested != Mode.SETUP && current.isEmpty())
            || (requested != Mode.LOGIN && replacement.isEmpty())) {
            showError("Enter the required password fields.");
            return;
        }
        if (requested != Mode.LOGIN && !replacement.equals(repeated)) {
            showError("New passwords do not match.");
            return;
        }
        runCredentialOperation(() -> {
            if (requested == Mode.SETUP) {
                if (PasswordHelper.retrievePasswordHash(this) != null) {
                    throw new IllegalStateException("Password already exists");
                }
            } else if (!PasswordHelper.checkPassword(this, current)) {
                return () -> {
                    currentPassword.setText("");
                    status.setText(R.string.shadow_incorrect_password);
                };
            }
            if (requested != Mode.LOGIN) PasswordHelper.setPassword(this, replacement);
            return this::authenticated;
        });
    }

    private void runCredentialOperation(Supplier<Runnable> operation) {
        busy = true;
        render();
        int attempt = ++generation;
        worker.execute(() -> {
            try {
                Runnable completed = operation.get();
                runOnUiThread(() -> {
                    if (attempt != generation || !foreground || isFinishing()) return;
                    busy = false;
                    completed.run();
                    render();
                });
            } catch (RuntimeException e) {
                runOnUiThread(() -> {
                    if (attempt != generation || !foreground || isFinishing()) return;
                    busy = false;
                    storageAvailable = false;
                    showError("Secure password storage is unavailable. Access is locked; your password was not reset.");
                });
            }
        });
    }

    private void authenticateWithBiometrics() {
        if (busy || !storageAvailable || !biometricAvailable) return;
        try {
            biometricCipher = PasswordHelper.createBiometricCipher();
            busy = true;
            render();
            BiometricPrompt.PromptInfo prompt = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Shadow")
                .setSubtitle("Authenticate with a strong biometric")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText("Use password")
                .build();
            biometricPrompt.authenticate(prompt, new BiometricPrompt.CryptoObject(biometricCipher));
        } catch (RuntimeException e) {
            busy = false;
            biometricAvailable = false;
            showError("Biometric login is unavailable. Use your Shadow password.");
        }
    }

    private void authenticated() {
        AdminSession.authenticate();
        clearFields();
        setResult(RESULT_OK);
        if (launchSettings) {
            startActivity(new Intent(this, SettingsActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
        }
        finish();
    }

    private void showError(String message) {
        status.setText(message);
        render();
    }

    private void clearFields() {
        currentPassword.setText("");
        newPassword.setText("");
        confirmation.setText("");
    }

    private void render() {
        title.setText(mode == Mode.SETUP ? R.string.shadow_create_password
            : mode == Mode.CHANGE ? R.string.shadow_change_password : R.string.shadow_unlock);
        currentPassword.setVisibility(mode == Mode.SETUP ? View.GONE : View.VISIBLE);
        currentPassword.setImeOptions(mode == Mode.LOGIN ? EditorInfo.IME_ACTION_DONE : EditorInfo.IME_ACTION_NEXT);
        newPassword.setVisibility(mode == Mode.LOGIN ? View.GONE : View.VISIBLE);
        confirmation.setVisibility(mode == Mode.LOGIN ? View.GONE : View.VISIBLE);
        submit.setText(mode == Mode.LOGIN ? R.string.shadow_unlock : R.string.shadow_save_password);
        changePassword.setVisibility(mode == Mode.SETUP ? View.GONE : View.VISIBLE);
        changePassword.setText(mode == Mode.CHANGE ? R.string.shadow_back_to_login : R.string.shadow_change_password);
        biometric.setVisibility(mode == Mode.LOGIN && biometricAvailable ? View.VISIBLE : View.GONE);
        boolean enabled = storageAvailable && !busy;
        currentPassword.setEnabled(enabled);
        newPassword.setEnabled(enabled);
        confirmation.setEnabled(enabled);
        submit.setEnabled(enabled);
        changePassword.setEnabled(enabled);
        biometric.setEnabled(enabled);
    }
}
