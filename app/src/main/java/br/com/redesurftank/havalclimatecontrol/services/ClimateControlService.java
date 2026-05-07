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
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.beantechs.intelligentvehiclecontrol.IIntelligentVehicleControlService;
import com.beantechs.intelligentvehiclecontrol.sdk.IListener;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import br.com.redesurftank.havalclimatecontrol.App;
import br.com.redesurftank.havalclimatecontrol.ClimateStateHolder;
import br.com.redesurftank.havalclimatecontrol.broadcastReceivers.RestartReceiver;
import br.com.redesurftank.havalclimatecontrol.utils.IPTablesUtils;
import br.com.redesurftank.havalclimatecontrol.utils.TelnetClientWrapper;
import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;

@SuppressLint("PrivateApi")
public class ClimateControlService extends Service implements Shizuku.OnBinderDeadListener {

    private static final String TAG = "ClimateControlService";

    private static final String CHANNEL_ID         = "ClimateControlChannel";
    private static final int    NOTIFICATION_ID     = 1;
    private static final String PREFS_NAME          = "climate_control_prefs";
    private static final String KEY_SHIZUKU_LIB     = "shizuku_lib_location";
    private static final String KEY_INSTALLED_CHECK = "self_installation_integrity_check";

    private static final String PROP_AUTO_ENABLE = "car.hvac.auto_enable";
    private static final String PROP_INSIDE_TEMP = "car.basic.inside_temp";
    private static final String PROP_DRIVER_TEMP = "car.hvac.driver_temperature";
    private static final String PROP_POWER_MODE  = "car.hvac.power_mode";

    private static final String PROP_AC_ENABLE         = "car.hvac.ac_enable";
    private static final String PROP_FRONT_DEFROST     = "car.hvac.front_defrost_enable";
    private static final String PROP_HEATING           = "car.hvac.heating_enable";
    private static final String PROP_INTELLIGENT_SW    = "car.hvac.Intelligent_switch_enable";
    private static final String PROP_LIMIT_ENABLE      = "car.hvac.setting.limit_enable";
    private static final String PROP_FRONT_TEMP_RANGE  = "car.hvac.front_temperature_range";
    private static final String PROP_INT_TEMP_RANGE    = "car.hvac.Intelligent_temperature_range";
    private static final String PROP_PM25              = "car.hvac.pm2.5_value";
    private static final String PROP_COMFORT_CURVE     = "car.hvac.setting.comfort_curve";

    private static final String[] ALL_PROPS = {
        "car.hvac.auto_enable", "car.basic.inside_temp",
        "car.hvac.driver_temperature", "car.hvac.power_mode",
        "car.hvac.ac_enable", "car.hvac.front_defrost_enable",
        "car.hvac.heating_enable", "car.hvac.Intelligent_switch_enable",
        "car.hvac.setting.limit_enable", "car.hvac.front_temperature_range",
        "car.hvac.Intelligent_temperature_range", "car.hvac.pm2.5_value",
        "car.hvac.setting.comfort_curve"
    };

    private static Method getServiceMethod;

    static {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            getServiceMethod = sm.getMethod("getService", String.class);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            Log.w(TAG, "Failed to get android.os.ServiceManager.getService", e);
        }
    }

    private static IBinder getServiceBinder(String serviceName) {
        try {
            return (IBinder) Objects.requireNonNull(getServiceMethod.invoke(null, serviceName));
        } catch (IllegalAccessException | InvocationTargetException | NullPointerException e) {
            throw new RuntimeException("Failed to get system service: " + serviceName, e);
        }
    }

    private HandlerThread handlerThread;
    private Handler       backgroundHandler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean isShizukuInitialized = false;
    private boolean isServiceRunning     = false;

    private IIntelligentVehicleControlService controlService;
    private final Map<String, String> dataCache = new HashMap<>();

    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private final IListener vehicleDataListener = new IListener.Stub() {
        @Override
        public void onDataChanged(String key, String value) {
            dataCache.put(key, value);
            backgroundHandler.post(ClimateControlService.this::evaluateClimateControl);
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

            boolean needsBootstrap = true;
            try {
                var selfInfo = getApplicationContext().getPackageManager()
                        .getApplicationInfo(getApplicationContext().getPackageName(), 0);
                if (selfInfo.uid > 10999) {
                    // Regular user app — Shizuku is started by app-tool; just wait for the binder
                    Log.w(TAG, "UID > 10999, skipping Shizuku bootstrap, waiting for existing binder...");
                    needsBootstrap = false;
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to get application info: " + e.getMessage(), e);
            }

            final String cachedLibLocation = prefs.getString(KEY_SHIZUKU_LIB, "");

            final Runnable timeoutRunnable = () -> {
                if (!isShizukuInitialized) {
                    Log.w(TAG, "Timeout waiting for Shizuku binder, restarting...");
                    restart();
                }
            };

            if (!needsBootstrap) {
                // Shizuku already running (started by app-tool); just attach to the existing binder
                Shizuku.addBinderReceivedListenerSticky(this::onShizukuBinderReceived);
                backgroundHandler.postDelayed(timeoutRunnable, 10000);
            } else {
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
            }

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

            IBinder controlBinder = new ShizukuBinderWrapper(
                    getServiceBinder("com.beantechs.intelligentvehiclecontrol"));
            if (!controlBinder.pingBinder()) {
                Log.e(TAG, "IntelligentVehicleControlService binder not alive");
                return false;
            }
            controlService = IIntelligentVehicleControlService.Stub.asInterface(controlBinder);
            controlService.addListenerKey(getPackageName(), ALL_PROPS);
            controlService.registerDataChangedListener(getPackageName(), vehicleDataListener);

            String[] values = controlService.fetchDatas(ALL_PROPS);
            if (values != null) {
                for (int i = 0; i < ALL_PROPS.length && i < values.length; i++) {
                    if (values[i] != null) dataCache.put(ALL_PROPS[i], values[i]);
                }
            }

            Log.w(TAG, "Connected to vehicle service — auto=" + dataCache.get(PROP_AUTO_ENABLE)
                    + " inside=" + dataCache.get(PROP_INSIDE_TEMP)
                    + " set=" + dataCache.get(PROP_DRIVER_TEMP)
                    + " power=" + dataCache.get(PROP_POWER_MODE));

            ClimateStateHolder.INSTANCE.commandCallback = (key, value) ->
                    backgroundHandler.post(() -> {
                        try {
                            controlService.request("cmd.common.request.set", key, value);
                            dataCache.put(key, value);
                            Log.w(TAG, "Command sent: " + key + " = " + value);
                            pushState(true, null);
                        } catch (Exception e) {
                            Log.e(TAG, "Error sending command: " + e.getMessage(), e);
                        }
                    });

            Shizuku.addBinderDeadListener(this);
            pushState(true, null);
            backgroundHandler.post(this::evaluateClimateControl);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error connecting to vehicle service: " + e.getMessage(), e);
            return false;
        }
    }

    private void evaluateClimateControl() {
        try {
            String autoEnable    = dataCache.get(PROP_AUTO_ENABLE);
            String insideTempStr = dataCache.get(PROP_INSIDE_TEMP);
            String driverTempStr = dataCache.get(PROP_DRIVER_TEMP);
            String powerModeStr  = dataCache.get(PROP_POWER_MODE);

            if (!"1".equals(autoEnable)) {
                pushState(true, null);
                return;
            }
            String acEnableStr = dataCache.get(PROP_AC_ENABLE);
            if (insideTempStr == null || driverTempStr == null || acEnableStr == null) {
                pushState(true, null);
                return;
            }

            float insideTemp = Float.parseFloat(insideTempStr);
            if (insideTemp == 87f) {
                // Sensor offline — car is off, ignore reading
                pushState(true, null);
                return;
            }
            float setTemp  = Float.parseFloat(driverTempStr);
            boolean isAcOn = "1".equals(acEnableStr);
            String logEntry  = null;

            if (insideTemp <= setTemp - 0.5f && isAcOn) {
                String msg = String.format(Locale.getDefault(),
                        "AC desligado — interna %.1f°C ≤ set %.1f°C", insideTemp, setTemp);
                Log.w(TAG, msg);
                controlService.request("cmd.common.request.set", PROP_AC_ENABLE, "0");
                dataCache.put(PROP_AC_ENABLE, "0");
                logEntry = timeFormat.format(new Date()) + "  " + msg;
            } else if (insideTemp >= setTemp + 0.5f && !isAcOn) {
                String msg = String.format(Locale.getDefault(),
                        "AC ligado — interna %.1f°C ≥ set %.1f°C", insideTemp, setTemp);
                Log.w(TAG, msg);
                controlService.request("cmd.common.request.set", PROP_AC_ENABLE, "1");
                dataCache.put(PROP_AC_ENABLE, "1");
                logEntry = timeFormat.format(new Date()) + "  " + msg;
            }

            String currentCurve = dataCache.get(PROP_COMFORT_CURVE);
            String desiredCurve;
            if (insideTemp < 22f) {
                desiredCurve = "0";
            } else if (insideTemp <= 28f) {
                desiredCurve = "1";
            } else {
                desiredCurve = "3";
            }
            if (!desiredCurve.equals(currentCurve)) {
                String msg = String.format(Locale.getDefault(),
                        "Comfort curve → %s — interna %.1f°C", desiredCurve, insideTemp);
                Log.w(TAG, msg);
                controlService.request("cmd.common.request.set", PROP_COMFORT_CURVE, desiredCurve);
                dataCache.put(PROP_COMFORT_CURVE, desiredCurve);
                if (logEntry == null) logEntry = timeFormat.format(new Date()) + "  " + msg;
            }

            pushState(true, logEntry);
        } catch (Exception e) {
            Log.e(TAG, "Error evaluating climate control: " + e.getMessage(), e);
        }
    }

    private void pushState(boolean connected, String logEntry) {
        String inside      = dataCache.get(PROP_INSIDE_TEMP);
        String driver      = dataCache.get(PROP_DRIVER_TEMP);
        String power       = dataCache.get(PROP_POWER_MODE);
        String auto        = dataCache.get(PROP_AUTO_ENABLE);
        String acEn        = dataCache.get(PROP_AC_ENABLE);
        String frontDef    = dataCache.get(PROP_FRONT_DEFROST);
        String heating     = dataCache.get(PROP_HEATING);
        String intSw       = dataCache.get(PROP_INTELLIGENT_SW);
        String limitEn     = dataCache.get(PROP_LIMIT_ENABLE);
        String frontTRange = dataCache.get(PROP_FRONT_TEMP_RANGE);
        String intTRange   = dataCache.get(PROP_INT_TEMP_RANGE);
        String pm25        = dataCache.get(PROP_PM25);
        String comfort     = dataCache.get(PROP_COMFORT_CURVE);
        final String finalLog = logEntry;

        mainHandler.post(() -> {
            ClimateStateHolder.INSTANCE.updateVehicleData(connected, inside, driver, power, auto);
            ClimateStateHolder.INSTANCE.updateHvacExtras(acEn, frontDef, heating, intSw, limitEn,
                    frontTRange, intTRange, pm25, comfort);
            if (finalLog != null) {
                ClimateStateHolder.INSTANCE.addLog(finalLog);
            }
        });
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Controle Climático", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        if (handlerThread != null) handlerThread.quitSafely();
        isServiceRunning = false;
        Shizuku.removeBinderReceivedListener(this::onShizukuBinderReceived);
        Shizuku.removeBinderDeadListener(this);
        try {
            if (controlService != null)
                controlService.unRegisterDataChangedListener(getPackageName(), vehicleDataListener);
        } catch (Exception ignored) {}
        mainHandler.post(() -> {
            ClimateStateHolder.INSTANCE.updateVehicleData(false, null, null, null, null);
            ClimateStateHolder.INSTANCE.commandCallback = null;
        });
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
        isServiceRunning     = false;
        Shizuku.removeBinderReceivedListener(this::onShizukuBinderReceived);
        Shizuku.removeBinderDeadListener(this);
        mainHandler.post(() -> ClimateStateHolder.INSTANCE.updateVehicleData(
                false, null, null, null, null));
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
