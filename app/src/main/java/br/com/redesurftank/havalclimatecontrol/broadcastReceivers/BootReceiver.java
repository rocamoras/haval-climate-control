package br.com.redesurftank.havalclimatecontrol.broadcastReceivers;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import br.com.redesurftank.havalclimatecontrol.services.ClimateControlService;

public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @SuppressLint("UnsafeProtectedBroadcastReceiver")
    @Override
    public void onReceive(Context context, Intent intent) {
        Log.w(TAG, "Boot completed received, starting ClimateControlService...");
        Intent serviceIntent = new Intent(context, ClimateControlService.class);
        context.startForegroundService(serviceIntent);
    }
}
