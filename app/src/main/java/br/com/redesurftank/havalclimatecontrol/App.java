package br.com.redesurftank.havalclimatecontrol;

import android.app.Application;
import android.content.Context;
import android.content.Intent;

import br.com.redesurftank.havalclimatecontrol.services.ClimateControlService;

public class App extends Application {

    private static Application sApplication;
    private static Context deviceProtectedContext;

    public static Application getApplication() {
        return sApplication;
    }

    public static Context getContext() {
        return getApplication().getApplicationContext();
    }

    public synchronized static Context getDeviceProtectedContext() {
        if (deviceProtectedContext == null) {
            deviceProtectedContext = getApplication().createDeviceProtectedStorageContext();
        }
        return deviceProtectedContext;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sApplication = this;
        Intent serviceIntent = new Intent(getContext(), ClimateControlService.class);
        getContext().startForegroundService(serviceIntent);
    }
}
