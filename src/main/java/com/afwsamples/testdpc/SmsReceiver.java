package com.afwsamples.testdpc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.SmsMessage;
import android.util.Log;
import android.widget.Toast;
import android.content.SharedPreferences;
import static android.content.Context.MODE_PRIVATE;

import java.io.PrintWriter;
import java.util.Arrays;

public class SmsReceiver extends BroadcastReceiver {

    private static final String TAG = "SMSReceiver";
    private static final int NOTIFICATION_ID = 1; // Unique notification ID

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getAction().equals("android.provider.Telephony.SMS_RECEIVED")) {
            // Extract the SMS message from the intent
            Object[] pdus = (Object[]) intent.getExtras().get("pdus");
            StringBuilder messageBody = new StringBuilder();

            for (Object pdu : pdus) {
                SmsMessage message = SmsMessage.createFromPdu((byte[]) pdu);
                messageBody.append(message.getMessageBody());
            }

            String message = messageBody.toString();
            Log.d(TAG, "Received SMS: " + message);

            // Execute command based on the message content
            executeCommand(context, message);
        }
    }

    private SharedPreferences sharedPreferences;

    private String getSavedPassword(Context context) {
        try {
            sharedPreferences = context.getSharedPreferences(PasswordActivity.PREFS_NAME, MODE_PRIVATE);
            String encryptedPassword = sharedPreferences.getString(PasswordActivity.KEY_PASSWORD, null);
            return PasswordActivity.decryptPassword(encryptedPassword);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private void executeCommand(Context context, String message) {
        // Split the message body into individual arguments
        String[] commandArgs = message.split("\\s+"); // Split by spaces to get individual args

        if (commandArgs.length > 0) {
            // Retrieve the saved password
            String savedPassword = getSavedPassword(context);

            // Check if the first argument matches the saved password
            // TODO: instead of checking for password, allow only some commands, especially "start-theft-mode"
            if (savedPassword != null && commandArgs[0].equals(savedPassword)) {
                // If the password matches, execute the command with the remaining arguments
                Log.d(TAG, "Password matched. Executing command with args: " + Arrays.toString(Arrays.copyOfRange(commandArgs, 1, commandArgs.length)));

                // Run ShellCommand with the rest of the arguments
                PrintWriter writer = new PrintWriter(System.out); // Use appropriate writer for output
                ShellCommand shellCommand = new ShellCommand(context, writer, Arrays.copyOfRange(commandArgs, 1, commandArgs.length));
                shellCommand.run();
            } else {
                Log.d(TAG, "Password mismatch. Command not executed.");
            }
        } else {
            Log.d(TAG, "Invalid command received.");
        }
    }
}
