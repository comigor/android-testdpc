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

            executeCommand(context, messageBody.toString());
        }
    }

    private void executeCommand(Context context, String message) {
        // Split the message body into individual arguments
        String[] commandArgs = message.trim().split("\\s+");
        if (commandArgs.length < 2 || !PasswordHelper.checkPassword(context, commandArgs[0])) {
            return;
        }
        // Password is the first token; never log message contents.
        new ShellCommand(context, new PrintWriter(System.out),
            Arrays.copyOfRange(commandArgs, 1, commandArgs.length)).run();
    }
}
