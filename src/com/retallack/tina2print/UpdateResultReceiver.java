package com.retallack.tina2print;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

/**
 * Receives the outcome of the PackageInstaller session started by {@link Updater}.
 * If the system needs the user to confirm the install it returns
 * {@link PackageInstaller#STATUS_PENDING_USER_ACTION} with an intent we must launch.
 */
public class UpdateResultReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(
                PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        Log.i(Updater.TAG, "install result status=" + status + " msg=" + msg
                + " extras=" + intent.getExtras());

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = getParcelable(intent, Intent.EXTRA_INTENT);
            Log.i(Updater.TAG, "pending user action, confirm=" + confirm);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(confirm);
            }
            return;
        }

        if (status == PackageInstaller.STATUS_SUCCESS) {
            Toast.makeText(context, "Update installed", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(context, "Update failed: "
                    + (msg == null ? ("status " + status) : msg), Toast.LENGTH_LONG).show();
        }
    }

    @SuppressWarnings("deprecation")
    private static Intent getParcelable(Intent intent, String key) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? intent.getParcelableExtra(key, Intent.class)
                : intent.getParcelableExtra(key);
    }
}
