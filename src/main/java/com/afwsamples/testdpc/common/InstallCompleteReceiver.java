package com.afwsamples.testdpc.common;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;

public class InstallCompleteReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirmation != null) {
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(confirmation);
            }
            return;
        }
        Log.i(PackageInstallationUtils.TAG, "Install status " + status + ": "
            + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
        context.sendBroadcast(new Intent(PackageInstallationUtils.ACTION_INSTALL_COMPLETE)
            .setPackage(context.getPackageName()).putExtras(intent));
    }
}
