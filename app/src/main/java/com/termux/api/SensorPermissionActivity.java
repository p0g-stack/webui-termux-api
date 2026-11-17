package com.termux.api;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;

/** Android's required permission-usage explanation for heart-rate sensor access. */
public final class SensorPermissionActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        new AlertDialog.Builder(this)
                .setTitle("Sensor access")
                .setMessage(
                        "This companion reads heart-rate sensor measurements when the module"
                            + " requests them and you grant permission. Measurements are returned"
                            + " to the module's authorized root helper through a local socket. The"
                            + " companion does not store these measurements or upload them. The"
                            + " requesting module controls their further use; consult that module's"
                            + " privacy information. You can revoke sensor access in Android"
                            + " Settings at any time.")
                .setPositiveButton(android.R.string.ok, (dialog, which) -> finish())
                .setOnDismissListener(dialog -> finish())
                .show();
    }
}
