package dev.borges.shadow;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.SmsMessage;
import android.util.Log;

import com.afwsamples.testdpc.ShellCommand;

import java.io.PrintWriter;
import java.util.Arrays;

import dev.borges.shadow.util.PasswordHelper;

public class SmsReceiver extends BroadcastReceiver {
    private static final String TAG = "SMSReceiver";

    private static final int NOTIFICATION_ID = 1; // Unique notification ID

    @Override
    public void onReceive(Context context, Intent intent) {
        if ("android.provider.Telephony.SMS_RECEIVED".equals(intent.getAction())) {
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

    private void executeCommand(Context context, String message) {
        // Split the message body into individual arguments
        String[] commandArgs = message.split("\\s+"); // Split by spaces to get individual args

        if (commandArgs.length > 0) {
            // Check if the first argument matches the saved password
            // TODO(igor): instead of checking for password, allow only some commands, especially "start-theft-mode"
            if (PasswordHelper.checkPassword(context, commandArgs[0])) {
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
