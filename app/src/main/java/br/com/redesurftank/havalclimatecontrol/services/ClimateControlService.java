package br.com.redesurftank.havalclimatecontrol.services;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.beantechs.intelligentvehiclecontrol.IIntelligentVehicleControlService;
import com.beantechs.intelligentvehiclecontrol.sdk.IListener;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import br.com.redesurftank.havalclimatecontrol.App;
import br.com.redesurftank.havalclimatecontrol.broadcastReceivers.RestartReceiver;
import br.com.redesurftank.havalclimatecontrol.utils.IPTablesUtils;
import br.com.redesurftank.havalclimatecontrol.utils.TelnetClientWrapper;
import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;

@SuppressLint("PrivateApi")
public class ClimateControlService extends Service implements Shizuku.OnBinderDeadListener {

    private static final String TAG = "ClimateControlService";

    private static Method getServiceMethod;

    static {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            getServiceMethod = sm.getMethod("getService", String.class);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            Log.w(TAG, "Failed to get android.os.ServiceManager.getService method", e);
        }
    }

    private static IBinder getSystemService(String serviceName) {
        try {
            return (IBinder) Objects.requireNonNull(getServiceMethod.invoke(null, serviceName));
        } catch (IllegalAccessException | InvocationTargetException | NullPointerException e) {
            throw new RuntimeException("Failed to get system service: " + serviceName, e);
        }
    }
    private static final String CHANNEL_ID = "ClimateControlChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final String PREFS_NAME = "climate_control_prefs";
    private static final String KEY_SHIZUKU_LIB = "shizuku_lib_location";
    private static final String KEY_INSTALLED_CHECK = "self_installation_integrity_check";

    private static final String PROP_AUTO_ENABLE = "car.hvac.auto_enable";
    private static final String PROP_INSIDE_TEMP = "car.basic.inside_temp";
    private static final String PROP_DRIVER_TEMP = "car.hvac.driver_temperature";
    private static final String PROP_POWER_MODE = "car.hvac.power_mode";

    private HandlerThread handlerThread;
    private Handler backgroundHandler;
    private Boolean isShizukuInitialized = false;
    private Boolean isServiceRunning = false;

    private IIntelligentVehicleControlService controlService;
    private final Map<String, String> dataCache = new HashMap<>();

    private final IListener vehicleDataListener = new IListener.Stub() {
        @Override
        public void onDataChanged(String key, String value) {
            dataCache.put(key, value);
            if (PROP_AUTO_ENABLE.equals(key) || PROP_INSIDE_TEMP.equals(key)
                    || PROP_DRIVER_TEMP.equals(key) || PROP_POWER_MODE.equals(key)) {
                backgroundHandler.post(ClimateControlService.this::evaluateClimateControl);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        handlerThread = new HandlerThread("ClimateControlThread");
        handlerThread.start();
        backgroundHandler = new Handler(handlerThread.getLooper());
    }

    @Override
    public synchronized int onStartCommand(Intent intent, int flags, int startId) {
        if (isServiceRunning) {
            Log.w(TAG, "Service already running, skipping start.");
            return START_STICKY;
        }

        try {
            isServiceRunning = true;
            Log.w(TAG, "Service started");

            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("Controle Climático Haval")
                    .setContentText("Monitorando temperatura do habitáculo")
                    .setSmallIcon(android.R.drawable.ic_notification_overlay)
                    .build();
            startForeground(NOTIFICATION_ID, notification);

            SharedPreferences prefs = App.getDeviceProtectedContext()
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

            if (!prefs.getBoolean(KEY_INSTALLED_CHECK, false)) {
                try {
                    var selfInfo = getApplicationContext().getPackageManager()
                            .getApplicationInfo(getApplicationContext().getPackageName(), 0);
                    if (selfInfo.uid > 10999) {
                        Log.w(TAG, "UID > 10999, Shizuku cannot start automatically.");
                        return START_NOT_STICKY;
                    } else {
                        prefs.edit().putBoolean(KEY_INSTALLED_CHECK, true).apply();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to get application info: " + e.getMessage(), e);
                }
            }

            final String cachedLibLocation = prefs.getString(KEY_SHIZUKU_LIB, "");

            final Runnable timeoutRunnable = () -> {
                if (!isShizukuInitialized) {
                    Log.w(TAG, "Timeout waiting for Shizuku binder, restarting...");
                    restart();
                }
            };

            backgroundHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        TelnetClientWrapper telnetClient = new TelnetClientWrapper();
                        telnetClient.connect("127.0.0.1", 23);
                        String filePath = cachedLibLocation;
                        if (filePath.isEmpty()) {
                            filePath = telnetClient.executeCommand("find /data/app -name libshizuku.so");
                            if (filePath.isEmpty()) throw new RuntimeException("libshizuku.so not found");
                            prefs.edit().putString(KEY_SHIZUKU_LIB, filePath).apply();
                            Log.w(TAG, "libshizuku.so found at: " + filePath);
                        }

                        String result = telnetClient.executeCommand(filePath);
                        if (Pattern.compile("killed \\d+ \\(shizuku_server\\)").matcher(result).find()) {
                            Log.w(TAG, "Old Shizuku process killed, waiting 5s...");
                            Thread.sleep(5000);
                        }
                        telnetClient.disconnect();

                        Shizuku.addBinderReceivedListenerSticky(ClimateControlService.this::onShizukuBinderReceived);
                        backgroundHandler.postDelayed(timeoutRunnable, 5000);
                    } catch (Exception e) {
                        Log.e(TAG, "Error bootstrapping Shizuku: " + e.getMessage(), e);
                        backgroundHandler.postDelayed(this, 1000);
                    }
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "Error in onStartCommand: " + e.getMessage(), e);
            isServiceRunning = false;
            stopSelf();
            return START_NOT_STICKY;
        }

        return START_STICKY;
    }

    private synchronized void onShizukuBinderReceived() {
        if (!isServiceRunning) return;
        Shizuku.removeBinderReceivedListener(this::onShizukuBinderReceived);
        Log.w(TAG, "Shizuku binder received");
        isShizukuInitialized = true;
        backgroundHandler.removeCallbacksAndMessages(null);
        checkAndInitialize();
    }

    private void checkAndInitialize() {
        if (!isShizukuInitialized) return;

        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Requesting Shizuku permission...");
            Shizuku.addRequestPermissionResultListener((requestCode, grantResult) -> {
                if (requestCode == 0 && grantResult == PackageManager.PERMISSION_GRANTED) {
                    checkAndInitialize();
                } else {
                    Log.e(TAG, "Shizuku permission denied");
                }
            });
            Shizuku.requestPermission(0);
            return;
        }

        try {
            IPTablesUtils.unlockInputOutputAll();
        } catch (Exception e) {
            Log.e(TAG, "Error unlocking iptables: " + e.getMessage(), e);
        }

        backgroundHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    IPTablesUtils.unlockInputOutputAll();
                    backgroundHandler.postDelayed(this, 15000);
                } catch (Exception e) {
                    backgroundHandler.postDelayed(this, 5000);
                }
            }
        });

        if (!connectToVehicleService()) {
            Log.e(TAG, "Failed to connect to vehicle service, restarting...");
            restart();
            return;
        }

        IntentFilter filter = new IntentFilter("com.beantechs.intelligentvehiclecontrol.INIT_COMPLETED");
        ContextCompat.registerReceiver(App.getContext(), new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (isServiceRunning) {
                    Log.w(TAG, "intelligentvehiclecontrol restarted, restarting service...");
                    restart();
                } else {
                    checkAndInitialize();
                }
            }
        }, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    private boolean connectToVehicleService() {
        try {
            if (!Shizuku.pingBinder()) {
                Log.e(TAG, "Shizuku not available");
                return false;
            }

            IBinder controlBinder = new ShizukuBinderWrapper(getSystemService("com.beantechs.intelligentvehiclecontrol"));
            if (!controlBinder.pingBinder()) {
                Log.e(TAG, "IntelligentVehicleControlService binder not alive");
                return false;
            }
            controlService = IIntelligentVehicleControlService.Stub.asInterface(controlBinder);
            controlService.addListenerKey(getPackageName(), new String[]{
                    PROP_AUTO_ENABLE, PROP_INSIDE_TEMP, PROP_DRIVER_TEMP, PROP_POWER_MODE
            });
            controlService.registerDataChangedListener(getPackageName(), vehicleDataListener);

            // Fetch initial values
            String[] keys = {PROP_AUTO_ENABLE, PROP_INSIDE_TEMP, PROP_DRIVER_TEMP, PROP_POWER_MODE};
            String[] values = controlService.fetchDatas(keys);
            if (values != null) {
                for (int i = 0; i < keys.length && i < values.length; i++) {
                    if (values[i] != null) dataCache.put(keys[i], values[i]);
                }
            }

            Log.w(TAG, "Connected to vehicle service. Initial state:"
                    + " auto=" + dataCache.get(PROP_AUTO_ENABLE)
                    + " inside=" + dataCache.get(PROP_INSIDE_TEMP)
                    + " set=" + dataCache.get(PROP_DRIVER_TEMP)
                    + " power=" + dataCache.get(PROP_POWER_MODE));

            Shizuku.addBinderDeadListener(this);
            backgroundHandler.post(this::evaluateClimateControl);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error connecting to vehicle service: " + e.getMessage(), e);
            return false;
        }
    }

    private void evaluateClimateControl() {
        try {
            String autoEnable = dataCache.get(PROP_AUTO_ENABLE);
            if (!"1".equals(autoEnable)) return; // Only act when AC is in Automatic mode

            String insideTempStr = dataCache.get(PROP_INSIDE_TEMP);
            String driverTempStr = dataCache.get(PROP_DRIVER_TEMP);
            String powerModeStr = dataCache.get(PROP_POWER_MODE);

            if (insideTempStr == null || driverTempStr == null || powerModeStr == null) return;

            float insideTemp = Float.parseFloat(insideTempStr);
            float setTemp = Float.parseFloat(driverTempStr);
            boolean isAcOn = "1".equals(powerModeStr);

            if (insideTemp <= setTemp - 0.5f && isAcOn) {
                Log.w(TAG, "Inside temp (" + insideTemp + ") <= set (" + setTemp + ") - 0.5, turning AC OFF");
                controlService.request("set", PROP_POWER_MODE, "0");
                dataCache.put(PROP_POWER_MODE, "0");
            } else if (insideTemp >= setTemp + 0.5f && !isAcOn) {
                Log.w(TAG, "Inside temp (" + insideTemp + ") >= set (" + setTemp + ") + 0.5, turning AC ON");
                controlService.request("set", PROP_POWER_MODE, "1");
                dataCache.put(PROP_POWER_MODE, "1");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error evaluating climate control: " + e.getMessage(), e);
        }
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Controle Climático", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (handlerThread != null) handlerThread.quitSafely();
        isServiceRunning = false;
        Shizuku.removeBinderReceivedListener(this::onShizukuBinderReceived);
        Shizuku.removeBinderDeadListener(this);
        try {
            if (controlService != null) {
                controlService.unRegisterDataChangedListener(getPackageName(), vehicleDataListener);
            }
        } catch (Exception ignored) {}
        Log.w(TAG, "Service destroyed");
        super.onDestroy();
    }

    @Override
    public void onBinderDead() {
        Shizuku.removeBinderReceivedListener(this::onShizukuBinderReceived);
        Shizuku.removeBinderDeadListener(this);
        Log.w(TAG, "Shizuku binder dead, restarting...");
        restart();
    }

    private synchronized void restart() {
        isShizukuInitialized = false;
        isServiceRunning = false;
        Shizuku.removeBinderReceivedListener(this::onShizukuBinderReceived);
        Shizuku.removeBinderDeadListener(this);
        Log.w(TAG, "Scheduling service restart...");
        Intent broadcastIntent = new Intent(this, RestartReceiver.class);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                this, 0, broadcastIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 1000, pendingIntent);
        stopSelf();
    }
}
