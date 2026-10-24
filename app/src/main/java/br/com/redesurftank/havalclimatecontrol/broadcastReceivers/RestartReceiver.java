package br.com.redesurftank.havalclimatecontrol.broadcastReceivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import br.com.redesurftank.havalclimatecontrol.services.ClimateControlService;

public class RestartReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Intent serviceIntent = new Intent(context, ClimateControlService.class);
        context.startForegroundService(serviceIntent);
    }
}
