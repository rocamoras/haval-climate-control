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
import br.com.redesurftank.havalclimatecontrol.utils.PersistentLog;
import br.com.redesurftank.havalclimatecontrol.utils.FridaUtils;
import br.com.redesurftank.havalclimatecontrol.utils.ShizukuUtils;
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
    // Autoriza este app a SUBIR o shizuku_server por telnet. Default false: numa central
    // com o app-tool (Impulse) instalado, quem sobe o server e ele — e o server e
    // singleton, quem sobe depois mata quem estava lá. Subir por conta propria ali
    // derrubaria o server do Impulse, e se ele reagisse subindo o dele de novo os dois
    // ficariam se matando em loop. Fica aqui em climate_control_prefs (device-protected)
    // de proposito: onStartCommand roda no LOCKED_BOOT_COMPLETED, antes do unlock, e
    // climate_ui_prefs (credential-protected) leria false sempre no boot frio.
    private static final String KEY_START_SHIZUKU   = "start_shizuku_server";

    // Prefs da UI (climate_ui_prefs) — lidos direto pelo serviço no boot, pois o
    // espelho em ClimateStateHolder só é populado quando a Activity é aberta.
    private static final String UI_PREFS_NAME         = "climate_ui_prefs";
    private static final String KEY_REAL_OUTSIDE_TEMP = "real_outside_temp_enabled";
    private static final String KEY_HOME_CARD         = "home_card_enabled";

    private static final String HVAC_PACKAGE_NAME    = "com.beantechs.hvac";
    private static final String WEATHER_PACKAGE_NAME = "com.beantechs.weatherservice";
    private static final long   REAL_TEMP_WATCHDOG_MS = 10_000; // re-injeta o hook Frida se cair / SystemUI reiniciar
    private static final long   HOME_CARD_WATCHDOG_MS = 10_000; // idem para a MediaCenter
    // Enquanto a primeira injecao nao pegou (MediaCenter ainda subindo no boot),
    // reintenta rapido: cada segundo aqui e um segundo de fileira do OEM na tela.
    private static final long   HOME_CARD_RETRY_MS    = 1_000;
    // Janela para o script restaurar a fileira original antes de matarmos o injetor.
    private static final long   HOME_CARD_RESTORE_MS  = 3_000;
    private static final long   HVAC_RESUME_DELAY_MS = 300;
    private static final long   EVAL_DEBOUNCE_MS     = 50;     // coalesce bursts de onDataChanged numa única avaliação
    private static final long   IPTABLES_REFRESH_MS  = 60_000; // re-assert da regra iptables (idempotente, antes 15s)
    private static final long   BOOTSTRAP_BACKOFF_MAX_MS = 30_000;
    // Medido em campo (log de 23/08): em boot frio o binder do Shizuku leva 4,3s e
    // 6,3s para chegar. O timeout antigo de 10s deixava 37% de margem — um boot mais
    // lento caia em restart(), que na pratica so recomeca a mesma espera. Esperar mais
    // e de graca: se o binder chega, seguimos na hora.
    private static final long   SHIZUKU_BINDER_TIMEOUT_MS    = 30_000;
    // Depois de subir o servidor via telnet o restart TEM valor (refaz o bootstrap),
    // então aqui a espera fica curta de proposito — mas nao tao curta quanto 5s.
    private static final long   SHIZUKU_BOOTSTRAP_TIMEOUT_MS = 15_000;

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

    private static final String PROP_DRIVER_SEAT_VENT      = "car.comfort_setting.driver_seat_ventilation_level";
    private static final String PROP_PASSENGER_SEAT_VENT   = "car.comfort_setting.passenger_seat_ventilation_level";
    private static final String PROP_OUTSIDE_TEMP          = "car.basic.outside_temp";
    private static final String PROP_WADE_MODE             = "car.ev.setting.wade_mode_enable";

    private static final String[] ALL_PROPS = {
        "car.hvac.auto_enable", "car.basic.inside_temp",
        "car.hvac.driver_temperature", "car.hvac.power_mode",
        "car.hvac.ac_enable", "car.hvac.front_defrost_enable",
        "car.hvac.heating_enable", "car.hvac.Intelligent_switch_enable",
        "car.hvac.setting.limit_enable", "car.hvac.front_temperature_range",
        "car.hvac.Intelligent_temperature_range", "car.hvac.pm2.5_value",
        "car.hvac.setting.comfort_curve",
        "car.comfort_setting.driver_seat_ventilation_level",
        "car.comfort_setting.passenger_seat_ventilation_level",
        "car.basic.outside_temp",
        "car.ev.setting.wade_mode_enable"
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
    /** Espelha KEY_START_SHIZUKU no ciclo atual, só para a mensagem de timeout. */
    private boolean bootstrapAllowed = false;
    private boolean isServiceRunning     = false;
    /** elapsedRealtime de quando comecamos a esperar o binder — vira metrica no log. */
    private volatile long binderWaitStartedMs = 0;

    // Listeners guardados em campos: `this::metodo` cria uma instancia de lambda NOVA
    // a cada avaliacao, entao removeXListener(this::metodo) nunca removia o que havia
    // sido registrado — os listeners acumulavam a cada ciclo de restart.
    private final Shizuku.OnBinderReceivedListener binderReceivedListener =
            this::onShizukuBinderReceived;
    private final Shizuku.OnRequestPermissionResultListener permissionResultListener =
            this::onShizukuPermissionResult;

    /** Registrado uma vez em onCreate, desregistrado em onDestroy. */
    private BroadcastReceiver vehicleInitReceiver;

    private IIntelligentVehicleControlService controlService;
    private final Map<String, String> dataCache = new HashMap<>();

    private long    acOffTimestamp        = 0;    // epoch ms do último desligamento do AC pelo controle automático
    private long    carStartTimestamp     = 0;    // epoch ms da última partida do carro detectada
    private boolean insideTempWasOffline  = true; // true enquanto o sensor estava offline (carro desligado)

    private boolean  isHvacSuspended    = false;
    private Runnable resumeHvacRunnable = null;

    // Temperatura Externa Real (UI) — injeção Frida no SystemUI + watchdog de 10s
    private volatile boolean realTempEnabled = false;
    private String  injectedSystemUiPid      = "";
    private final Runnable realTempWatchdogRunnable = this::realTempWatchdogTick;

    // Card na Home (MediaCenter) — injeção Frida + watchdog de 10s
    private volatile boolean homeCardEnabled    = false;
    private volatile boolean homeCardBootstrapped = false;
    private volatile boolean homeCardInjected     = false;
    private String  injectedMediaCenterPid      = "";
    private final Runnable homeCardWatchdogRunnable = this::homeCardWatchdogTick;
    private final Runnable acOffCheckRunnable = this::evaluateClimateControl;
    // Instância única e estável para coalescência via removeCallbacks/postDelayed.
    // Mantida SEPARADA de acOffCheckRunnable para não cancelar o recheck de 62s do AC.
    private final Runnable evalRunnable        = this::evaluateClimateControl;

    // Rastreamento do último valor enviado pelo app — usado para detectar alterações externas
    private volatile String  lastSentDriverVent    = null;
    private volatile String  lastSentPassengerVent = null;
    private volatile boolean prevSeatVentAuto      = false;
    private volatile String  lastSentComfortCurve  = null;
    private volatile String  prevComfortMode       = null;

    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private final IListener vehicleDataListener = new IListener.Stub() {
        @Override
        public void onDataChanged(String key, String value) {
            dataCache.put(key, value);
            // Coalesce: um burst de N propriedades dispara uma única avaliação após assentar.
            backgroundHandler.removeCallbacks(evalRunnable);
            backgroundHandler.postDelayed(evalRunnable, EVAL_DEBOUNCE_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        handlerThread = new HandlerThread("ClimateControlThread");
        handlerThread.start();
        backgroundHandler = new Handler(handlerThread.getLooper());

        // UMA vez por instancia, no contexto do servico. Antes isso ficava dentro de
        // checkAndInitialize() e no contexto da Application: cada init acumulava um
        // receiver que sobrevivia ao onDestroy, e no INIT_COMPLETED seguinte todos
        // disparavam juntos — inclusive os de instancias mortas, cada um pedindo
        // restart. Era a explicacao mais provavel dos reinicios "do nada".
        vehicleInitReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!isServiceRunning) return;   // instancia encerrada: quem sobe de novo e o alarme
                restart("intelligentvehiclecontrol reinicializou (INIT_COMPLETED)");
            }
        };
        ContextCompat.registerReceiver(this, vehicleInitReceiver,
                new IntentFilter("com.beantechs.intelligentvehiclecontrol.INIT_COMPLETED"),
                ContextCompat.RECEIVER_NOT_EXPORTED);

        PersistentLog.w(TAG, "servico criado");
    }

    @Override
    public synchronized int onStartCommand(Intent intent, int flags, int startId) {
        if (isServiceRunning) {
            Log.w(TAG, "Service already running, skipping start.");
            return START_STICKY;
        }

        try {
            isServiceRunning = true;
            PersistentLog.w(TAG, "servico iniciado (flags=" + flags + " startId=" + startId
                    + " intent=" + (intent == null ? "null (recriado pelo sistema)" : "ok") + ")");

            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("Controle Climático Haval")
                    .setContentText("Monitorando temperatura do habitáculo")
                    .setSmallIcon(android.R.drawable.ic_notification_overlay)
                    .build();
            startForeground(NOTIFICATION_ID, notification);

            SharedPreferences prefs = App.getDeviceProtectedContext()
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

            // Quem decide se subimos o server e a pref, nao mais o uid: um app de usuario
            // numa central sem o Impulse ficava esperando para sempre um binder que
            // ninguem ia subir. O uid continua no log porque ainda explica a permissao
            // que temos para falar com o telnet.
            boolean needsBootstrap = prefs.getBoolean(KEY_START_SHIZUKU, false);
            bootstrapAllowed = needsBootstrap;
            int selfUid = -1;
            try {
                selfUid = getApplicationContext().getPackageManager()
                        .getApplicationInfo(getApplicationContext().getPackageName(), 0).uid;
            } catch (Exception e) {
                PersistentLog.e(TAG, "falha lendo o ApplicationInfo: " + e);
            }
            PersistentLog.w(TAG, "uid=" + selfUid + " start_shizuku_server=" + needsBootstrap
                    + " → caminho: "
                    + (needsBootstrap ? "bootstrap do Shizuku por telnet"
                                      : "esperar o binder existente (subido pelo app-tool)"));
            // O firewall por uid do Android barra o loopback:23 para uid alto, e a regra
            // que libera (IPTablesUtils) só entra depois que o Shizuku esta de pe — ou
            // seja, circular. Sem uid baixo o bootstrap nao tem como dar certo; avisa
            // no log em vez de deixar o backoff girando calado.
            if (needsBootstrap && selfUid > 10999) {
                PersistentLog.e(TAG, "start_shizuku_server=true mas uid=" + selfUid
                        + " (>10999): o telnet:23 esta barrado pelo firewall e o bootstrap"
                        + " deve falhar — reinstale o app pelo metodo que da uid baixo");
            }

            // Holder e nao String final porque o caminho pode ser invalidado no meio das
            // tentativas: se o libshizuku.so morava no APK do app-tool e ele foi
            // desinstalado, o cache aponta para um arquivo que nao existe mais.
            final String[] cachedLibLocation = { prefs.getString(KEY_SHIZUKU_LIB, "") };

            final Runnable timeoutRunnable = () -> {
                if (!isShizukuInitialized) {
                    // Sem essa dica o carro sem Impulse fica num loop de 30s sem dizer o
                    // que fazer — e a gente so descobria quando alguem mandava o log.
                    String hint = bootstrapAllowed ? ""
                            : " — nenhum app-tool subiu o server e \"Subir servidor do Shizuku\""
                              + " esta desligado no Debug; ligue essa opcao nesta central";
                    restart("timeout esperando o binder do Shizuku apos "
                            + waitedForBinderMs() + "ms" + hint);
                }
            };

            if (!needsBootstrap) {
                // Shizuku already running (started by app-tool); just attach to the existing binder
                binderWaitStartedMs = SystemClock.elapsedRealtime();
                Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
                backgroundHandler.postDelayed(timeoutRunnable, SHIZUKU_BINDER_TIMEOUT_MS);
            } else {
                final int[] bootstrapAttempt = {0};
                backgroundHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            // Guard: a pref autoriza subir o server, mas nao autoriza matar
                            // um que ja esteja de pe. Rodar o libshizuku.so mata o
                            // shizuku_server existente (ver o regex "killed ... " abaixo), e
                            // se esse for o do Impulse a gente derruba o app dele de graca.
                            if (ShizukuUtils.isAvailable()) {
                                PersistentLog.w(TAG, "server do Shizuku de terceiro ja ativo"
                                        + " — anexando ao binder existente em vez de subir o nosso");
                                binderWaitStartedMs = SystemClock.elapsedRealtime();
                                Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
                                backgroundHandler.postDelayed(timeoutRunnable,
                                        SHIZUKU_BINDER_TIMEOUT_MS);
                                return;
                            }

                            TelnetClientWrapper telnetClient = new TelnetClientWrapper();
                            telnetClient.connect("127.0.0.1", 23);
                            String filePath = cachedLibLocation[0];
                            if (!filePath.isEmpty()
                                    && !telnetClient.executeCommand("ls " + filePath).contains(filePath)) {
                                PersistentLog.w(TAG, "libshizuku.so cacheado sumiu (" + filePath
                                        + ") — app-tool desinstalado? refazendo o find");
                                prefs.edit().remove(KEY_SHIZUKU_LIB).apply();
                                cachedLibLocation[0] = "";
                                filePath = "";
                            }
                            if (filePath.isEmpty()) {
                                filePath = telnetClient.executeCommand("find /data/app -name libshizuku.so");
                                if (filePath.isEmpty()) throw new RuntimeException("libshizuku.so not found");
                                prefs.edit().putString(KEY_SHIZUKU_LIB, filePath).apply();
                                cachedLibLocation[0] = filePath;
                                Log.w(TAG, "libshizuku.so found at: " + filePath);
                            }

                            String result = telnetClient.executeCommand(filePath);
                            if (Pattern.compile("killed \\d+ \\(shizuku_server\\)").matcher(result).find()) {
                                Log.w(TAG, "Old Shizuku process killed, waiting 5s...");
                                Thread.sleep(5000);
                            }
                            telnetClient.disconnect();

                            PersistentLog.w(TAG, "bootstrap do Shizuku concluido na tentativa "
                                    + (bootstrapAttempt[0] + 1) + " — esperando o binder");
                            bootstrapAttempt[0] = 0;
                            binderWaitStartedMs = SystemClock.elapsedRealtime();
                            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
                            backgroundHandler.postDelayed(timeoutRunnable, SHIZUKU_BOOTSTRAP_TIMEOUT_MS);
                        } catch (Exception e) {
                            // Backoff exponencial (1s→2s→…→30s) para não martelar o Telnet em falha persistente.
                            int  attempt = ++bootstrapAttempt[0];
                            long backoff = Math.min(BOOTSTRAP_BACKOFF_MAX_MS,
                                    1000L << Math.min(attempt - 1, 5));
                            // No log persistente, e não só no logcat: sem isso um bootstrap
                            // que falha em loop fica INVISÍVEL exatamente no log que deveria
                            // explicá-lo — o timeoutRunnable só é agendado depois do sucesso,
                            // então nem a linha de REINICIO aparece. Foi o que aconteceu no
                            // log de 2026-08-24: 12 minutos de silêncio absoluto.
                            PersistentLog.e(TAG, "bootstrap do Shizuku falhou na tentativa "
                                    + attempt + " (retry em " + backoff + "ms): " + e);
                            backgroundHandler.postDelayed(this, backoff);
                        }
                    }
                });
            }

        } catch (Exception e) {
            PersistentLog.e(TAG, "erro no onStartCommand, encerrando: " + e);
            isServiceRunning = false;
            stopSelf();
            return START_NOT_STICKY;
        }

        return START_STICKY;
    }

    private synchronized void onShizukuBinderReceived() {
        if (!isServiceRunning) return;
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        PersistentLog.w(TAG, "binder do Shizuku recebido apos " + waitedForBinderMs() + "ms");
        isShizukuInitialized = true;
        backgroundHandler.removeCallbacksAndMessages(null);
        checkAndInitialize();
    }

    /** Quanto tempo esperamos pelo binder, em ms; -1 se a espera nem comecou. */
    private long waitedForBinderMs() {
        long started = binderWaitStartedMs;
        return started == 0 ? -1 : SystemClock.elapsedRealtime() - started;
    }

    private void checkAndInitialize() {
        if (!isShizukuInitialized) return;

        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Requesting Shizuku permission...");
            // Listener em campo e removido no callback: antes um lambda anonimo novo
            // era adicionado a cada checkAndInitialize() e nunca saia.
            Shizuku.addRequestPermissionResultListener(permissionResultListener);
            Shizuku.requestPermission(0);
            return;
        }

        // Card na Home ANTES do resto: a fileira de midia online do OEM fica visivel
        // ate o hook entrar, e connectToVehicleService() + iptables adicionam segundos
        // a essa janela. O card so depende do Shizuku e da pref, nao do veiculo.
        backgroundHandler.post(this::bootstrapHomeCard);

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
                    backgroundHandler.postDelayed(this, IPTABLES_REFRESH_MS);
                } catch (Exception e) {
                    backgroundHandler.postDelayed(this, 5000);
                }
            }
        });

        if (!connectToVehicleService()) {
            restart("falha conectando ao servico do veiculo");
            return;
        }
    }

    private synchronized void onShizukuPermissionResult(int requestCode, int grantResult) {
        if (requestCode != 0) return;
        Shizuku.removeRequestPermissionResultListener(permissionResultListener);
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            PersistentLog.w(TAG, "permissao do Shizuku concedida");
            checkAndInitialize();
        } else {
            PersistentLog.e(TAG, "permissao do Shizuku negada");
        }
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
                            sendHvacCommand(key, value);
                            dataCache.put(key, value);
                            Log.w(TAG, "Command sent: " + key + " = " + value);
                            pushState(true, null);
                        } catch (Exception e) {
                            Log.e(TAG, "Error sending command: " + e.getMessage(), e);
                        }
                    });

            // Configurações — Temperatura Externa Real (UI): registra callback UI → serviço.
            ClimateStateHolder.INSTANCE.setOnRealOutsideTempToggle(enabled ->
                    backgroundHandler.post(() -> applyRealOutsideTemp(enabled)));
            // Reaplica o estado PERSISTIDO. Lê direto do climate_ui_prefs porque, no boot
            // do carro (antes da Activity abrir), o espelho em ClimateStateHolder ainda é
            // o default false — daí a injeção não acontecia até alternar manualmente.
            boolean realTempWanted = false;
            try {
                realTempWanted = App.getContext()
                        .getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)
                        .getBoolean(KEY_REAL_OUTSIDE_TEMP, false);
            } catch (Exception e) {
                Log.w(TAG, "Falha lendo pref real_outside_temp: " + e.getMessage());
            }
            if (realTempWanted) {
                ClimateStateHolder.INSTANCE.setRealOutsideTempEnabled(true);
                backgroundHandler.post(() -> applyRealOutsideTemp(true));
            }

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
            if (!ClimateStateHolder.INSTANCE.getAutoControlEnabled()) {
                pushState(true, null);
                return;
            }

            String insideTempStr = dataCache.get(PROP_INSIDE_TEMP);
            if (insideTempStr == null) {
                insideTempWasOffline = true;
                pushState(true, null);
                return;
            }
            float insideTemp = Float.parseFloat(insideTempStr);
            if (insideTemp == 87f) {
                // Sensor offline — car is off, ignore reading
                insideTempWasOffline = true;
                pushState(true, null);
                return;
            }

            String logEntry = null;

            // Detectar partida do carro (transição offline → online)
            if (insideTempWasOffline) {
                insideTempWasOffline = false;
                carStartTimestamp    = System.currentTimeMillis();
                Log.w(TAG, "Partida do carro detectada — AC protegido por 30s");

                // Na partida, o firmware do HVAC reseta comfort_curve e ventilação para os
                // padrões dele. Zeramos o rastreamento de mudança externa para NÃO interpretar
                // esse reset como alteração manual do usuário (o que sobrescreveria o modo salvo).
                // O app re-aplica a config salva na próxima avaliação.
                lastSentComfortCurve  = null;
                lastSentDriverVent    = null;
                lastSentPassengerVent = null;
                String acEnableStr = dataCache.get(PROP_AC_ENABLE);
                if (!"1".equals(acEnableStr) && "1".equals(dataCache.get(PROP_AUTO_ENABLE))) {
                    sendHvacCommand(PROP_AC_ENABLE, "1");
                    dataCache.put(PROP_AC_ENABLE, "1");
                    acOffTimestamp = 0;
                    backgroundHandler.removeCallbacks(acOffCheckRunnable);
                    logEntry = timeFormat.format(new Date()) + "  AC ligado — partida do carro (30s protegido)";
                }
            }

            // Bloco A — AC + comfort curve (requer modo auto do HVAC ligado)
            String autoEnable = dataCache.get(PROP_AUTO_ENABLE);
            if ("1".equals(autoEnable)) {
                String driverTempStr = dataCache.get(PROP_DRIVER_TEMP);
                String acEnableStr   = dataCache.get(PROP_AC_ENABLE);
                if (driverTempStr != null && acEnableStr != null) {
                    float setTemp  = Float.parseFloat(driverTempStr);
                    boolean isAcOn = "1".equals(acEnableStr);

                    // Histerese variável conforme temperatura externa
                    float hysteresis = 0.5f;
                    String outsideTempStr = dataCache.get(PROP_OUTSIDE_TEMP);
                    if (outsideTempStr != null) {
                        try {
                            if (Float.parseFloat(outsideTempStr) > 28f) hysteresis = 1.0f;
                        } catch (NumberFormatException ignored) {}
                    }

                    boolean acOffOver1Min = acOffTimestamp > 0
                            && (System.currentTimeMillis() - acOffTimestamp) > 60_000L;

                    // Proteção de 30s após partida — não desliga o AC
                    boolean inStartProtection = carStartTimestamp > 0
                            && (System.currentTimeMillis() - carStartTimestamp) < 30_000L;

                    if (insideTemp <= setTemp - hysteresis && isAcOn && !inStartProtection) {
                        String msg = String.format(Locale.getDefault(),
                                "AC desligado — interna %.1f°C ≤ set %.1f°C (histerese %.1f°C)",
                                insideTemp, setTemp, hysteresis);
                        Log.w(TAG, msg);
                        sendHvacCommand(PROP_AC_ENABLE, "0");
                        dataCache.put(PROP_AC_ENABLE, "0");
                        acOffTimestamp = System.currentTimeMillis();
                        backgroundHandler.removeCallbacks(acOffCheckRunnable);
                        backgroundHandler.postDelayed(acOffCheckRunnable, 62_000L);
                        logEntry = timeFormat.format(new Date()) + "  " + msg;
                    } else if (!isAcOn && (insideTemp >= setTemp + 0.5f
                            || (insideTemp >= setTemp && acOffOver1Min))) {
                        String reason = (insideTemp >= setTemp + 0.5f)
                                ? String.format(Locale.getDefault(), "interna %.1f°C ≥ set %.1f°C", insideTemp, setTemp)
                                : String.format(Locale.getDefault(), "interna %.1f°C = set %.1f°C após >1 min", insideTemp, setTemp);
                        String msg = "AC ligado — " + reason;
                        Log.w(TAG, msg);
                        sendHvacCommand(PROP_AC_ENABLE, "1");
                        dataCache.put(PROP_AC_ENABLE, "1");
                        acOffTimestamp = 0;
                        backgroundHandler.removeCallbacks(acOffCheckRunnable);
                        logEntry = timeFormat.format(new Date()) + "  " + msg;
                    }

                    String currentCurve = dataCache.get(PROP_COMFORT_CURVE);
                    String cMode = ClimateStateHolder.INSTANCE.getComfortMode();

                    // Se o modo foi alterado pelo app, reinicia rastreamento para evitar falsos positivos
                    if (!cMode.equals(prevComfortMode)) {
                        lastSentComfortCurve = null;
                        prevComfortMode = cMode;
                    }

                    // Se a curva foi alterada externamente (menu nativo do HVAC), converte para modo manual
                    boolean externalComfortChange =
                            lastSentComfortCurve != null && !lastSentComfortCurve.equals(currentCurve);

                    if (externalComfortChange) {
                        String msg = String.format(Locale.getDefault(),
                                "Curva de conforto alterada externamente (%s) → modo manual", currentCurve);
                        Log.w(TAG, msg);
                        if (logEntry == null) logEntry = timeFormat.format(new Date()) + "  " + msg;
                        final String finalCurve = currentCurve != null ? currentCurve : "1";
                        mainHandler.post(() -> ClimateStateHolder.INSTANCE.notifyExternalComfortChange(finalCurve));
                        // Não sobrescreve o valor externo — continua para pushState
                    } else {
                        String desiredCurve;
                        switch (cMode) {
                            case "SUAVE":  desiredCurve = "0"; break;
                            case "NORMAL": desiredCurve = "1"; break;
                            case "FORTE":  desiredCurve = "2"; break;
                            default: {
                                float oTemp = 0f;
                                String oStr = dataCache.get(PROP_OUTSIDE_TEMP);
                                if (oStr != null) {
                                    try { oTemp = Float.parseFloat(oStr); } catch (NumberFormatException ignored) {}
                                }
                                if (oTemp >= 24f)      desiredCurve = "2";
                                else if (oTemp >= 19f) desiredCurve = "1";
                                else                   desiredCurve = "0";
                                break;
                            }
                        }
                        if (!desiredCurve.equals(currentCurve)) {
                            String msg = String.format(Locale.getDefault(),
                                    "Comfort curve → %s — interna %.1f°C", desiredCurve, insideTemp);
                            Log.w(TAG, msg);
                            lastSentComfortCurve = desiredCurve;
                            sendHvacCommand(PROP_COMFORT_CURVE, desiredCurve);
                            dataCache.put(PROP_COMFORT_CURVE, desiredCurve);
                            if (logEntry == null) logEntry = timeFormat.format(new Date()) + "  " + msg;
                        }
                    }
                }
            }

            // Bloco B — Ventilação dos bancos (independente do modo auto do HVAC)
            boolean seatVentAutoNow = ClimateStateHolder.INSTANCE.getSeatVentAutoEnabled();
            if (seatVentAutoNow) {
                String currentDriverVent    = dataCache.get(PROP_DRIVER_SEAT_VENT);
                String currentPassengerVent = dataCache.get(PROP_PASSENGER_SEAT_VENT);

                // Ao (re)ativar o modo AUTO, reinicia rastreamento para evitar falsos positivos
                if (!prevSeatVentAuto) {
                    lastSentDriverVent    = null;
                    lastSentPassengerVent = null;
                }
                prevSeatVentAuto = true;

                // Se a ventilação foi alterada externamente (menu nativo), desativa o modo AUTO
                boolean externalVentChange =
                        (lastSentDriverVent    != null && !lastSentDriverVent.equals(currentDriverVent))
                     || (lastSentPassengerVent != null && !lastSentPassengerVent.equals(currentPassengerVent));

                if (externalVentChange) {
                    String msg = String.format(Locale.getDefault(),
                            "Ventilação alterada externamente (driver=%s) → modo AUTO desativado", currentDriverVent);
                    Log.w(TAG, msg);
                    if (logEntry == null) logEntry = timeFormat.format(new Date()) + "  " + msg;
                    final String finalLevel = currentDriverVent != null ? currentDriverVent : "0";
                    mainHandler.post(() -> ClimateStateHolder.INSTANCE.notifyExternalVentChange(finalLevel));
                    // Não sobrescreve o valor externo — continua para pushState
                } else {
                    String desiredVentLevel;
                    if (insideTemp > 28f)      desiredVentLevel = "3";
                    else if (insideTemp > 26f) desiredVentLevel = "2";
                    else if (insideTemp > 24f) desiredVentLevel = "1";
                    else                       desiredVentLevel = "0";

                    boolean ventChanged = !desiredVentLevel.equals(currentDriverVent)
                            || !desiredVentLevel.equals(currentPassengerVent);

                    if (!desiredVentLevel.equals(currentDriverVent)) {
                        lastSentDriverVent = desiredVentLevel;
                        sendHvacCommand(PROP_DRIVER_SEAT_VENT, desiredVentLevel);
                        dataCache.put(PROP_DRIVER_SEAT_VENT, desiredVentLevel);
                    }
                    if (!desiredVentLevel.equals(currentPassengerVent)) {
                        lastSentPassengerVent = desiredVentLevel;
                        sendHvacCommand(PROP_PASSENGER_SEAT_VENT, desiredVentLevel);
                        dataCache.put(PROP_PASSENGER_SEAT_VENT, desiredVentLevel);
                    }
                    if (ventChanged && logEntry == null) {
                        String msg = String.format(Locale.getDefault(),
                                "Ventilação bancos → %s — interna %.1f°C", desiredVentLevel, insideTemp);
                        Log.w(TAG, msg);
                        logEntry = timeFormat.format(new Date()) + "  " + msg;
                    }
                }
            } else {
                prevSeatVentAuto = false;
            }

            // Bloco C — Aquecimento por temperatura externa
            String outsideTempForHeating = dataCache.get(PROP_OUTSIDE_TEMP);
            if (outsideTempForHeating != null) {
                try {
                    float outsideTemp = Float.parseFloat(outsideTempForHeating);
                    String desiredHeating = outsideTemp < 20f ? "1" : "0";
                    String currentHeating = dataCache.get(PROP_HEATING);
                    if (!desiredHeating.equals(currentHeating)) {
                        String msg = String.format(Locale.getDefault(),
                                "Aquecimento → %s — externa %.1f°C",
                                "1".equals(desiredHeating) ? "ligado" : "desligado", outsideTemp);
                        Log.w(TAG, msg);
                        sendHvacCommand(PROP_HEATING, desiredHeating);
                        dataCache.put(PROP_HEATING, desiredHeating);
                        if (logEntry == null) logEntry = timeFormat.format(new Date()) + "  " + msg;
                    }
                } catch (NumberFormatException ignored) {}
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
        String wadeMode    = dataCache.get(PROP_WADE_MODE);
        final String finalLog = logEntry;

        String driverVent      = dataCache.get(PROP_DRIVER_SEAT_VENT);
        String passengerVent   = dataCache.get(PROP_PASSENGER_SEAT_VENT);
        String outsideTemp     = dataCache.get(PROP_OUTSIDE_TEMP);

        mainHandler.post(() -> {
            ClimateStateHolder.INSTANCE.updateVehicleData(connected, inside, driver, power, auto, outsideTemp);
            ClimateStateHolder.INSTANCE.updateHvacExtras(acEn, frontDef, heating, intSw, limitEn,
                    frontTRange, intTRange, pm25, comfort, wadeMode);
            ClimateStateHolder.INSTANCE.updateSeatData(driverVent, passengerVent);
            if (finalLog != null) {
                ClimateStateHolder.INSTANCE.addLog(finalLog);
            }
        });
    }

    private void sendHvacCommand(String key, String value) throws Exception {
        ensureHvacSuspended(key);
        controlService.request("cmd.common.request.set", key, value);
        scheduleHvacResumption();
    }

    private void ensureHvacSuspended(String triggerKey) {
        if (resumeHvacRunnable != null) {
            backgroundHandler.removeCallbacks(resumeHvacRunnable);
            resumeHvacRunnable = null;
        }
        if (!isHvacSuspended) {
            if (isHvacAppInForeground()) {
                Log.w(TAG, "HVAC app em foreground, pulando suspensão para: " + triggerKey);
                return;
            }
            Log.w(TAG, "Suspendendo HVAC app para: " + triggerKey);
            ShizukuUtils.runCommandAndGetOutput(new String[]{"pm", "disable-user", "--user", "0", HVAC_PACKAGE_NAME});
            ShizukuUtils.runCommandAndGetOutput(new String[]{"am", "force-stop", HVAC_PACKAGE_NAME});
            isHvacSuspended = true;
            SystemClock.sleep(150);
        }
    }

    private boolean isHvacAppInForeground() {
        try {
            String output = ShizukuUtils.runCommandAndGetOutput(
                    new String[]{"sh", "-c", "dumpsys activity activities | grep ResumedActivity"});
            boolean isForeground = output != null && output.contains(HVAC_PACKAGE_NAME);
            if (isForeground) Log.w(TAG, "HVAC app está em foreground");
            return isForeground;
        } catch (Exception e) {
            Log.e(TAG, "Erro ao verificar foreground do HVAC", e);
        }
        return false;
    }

    private void scheduleHvacResumption() {
        if (resumeHvacRunnable != null) {
            backgroundHandler.removeCallbacks(resumeHvacRunnable);
        }
        resumeHvacRunnable = () -> {
            Log.w(TAG, "Reabilitando HVAC app");
            ShizukuUtils.runCommandAndGetOutput(new String[]{"pm", "enable", HVAC_PACKAGE_NAME});
            isHvacSuspended = false;
            resumeHvacRunnable = null;
        };
        backgroundHandler.postDelayed(resumeHvacRunnable, HVAC_RESUME_DELAY_MS);
    }

    // ─────────────────────────────────────────────────────────────
    // Temperatura Externa Real (UI) — injeção Frida no SystemUI
    // ─────────────────────────────────────────────────────────────

    /** (Des)ativa a exibição da temperatura externa real na barra da central.
     *  Roda no backgroundHandler. */
    private void applyRealOutsideTemp(boolean enabled) {
        realTempEnabled = enabled;
        backgroundHandler.removeCallbacks(realTempWatchdogRunnable);
        if (enabled) {
            // Desativa o serviço nativo de previsão do tempo da central.
            ShizukuUtils.runCommandAndGetOutput(new String[]{"pm", "disable-user", "--user", "0", WEATHER_PACKAGE_NAME});
            ShizukuUtils.runCommandAndGetOutput(new String[]{"am", "force-stop", WEATHER_PACKAGE_NAME});
            // Injeta o hook que lê o sensor real e repinta a barra.
            String msg = FridaUtils.startAndInject();
            injectedSystemUiPid = FridaUtils.systemUiPid();
            Log.w(TAG, "[realtemp] ativado: " + msg + " (systemui pid=" + injectedSystemUiPid + ")");
            backgroundHandler.postDelayed(realTempWatchdogRunnable, REAL_TEMP_WATCHDOG_MS);
        } else {
            // Para a injeção e reativa o serviço nativo de previsão do tempo.
            String msg = FridaUtils.stop();
            injectedSystemUiPid = "";
            ShizukuUtils.runCommandAndGetOutput(new String[]{"pm", "enable", WEATHER_PACKAGE_NAME});
            Log.w(TAG, "[realtemp] desativado: " + msg);
        }
    }

    /** Watchdog: a cada 10s re-injeta o hook se ele caiu ou o SystemUI reiniciou. */
    private void realTempWatchdogTick() {
        if (!realTempEnabled) return;
        try {
            String currentPid = FridaUtils.systemUiPid();
            boolean systemUiRestarted = !currentPid.isEmpty() && !currentPid.equals(injectedSystemUiPid);
            if (systemUiRestarted || !FridaUtils.isInjectionAlive()) {
                Log.w(TAG, "[realtemp] watchdog re-injetando (restart=" + systemUiRestarted + ")");
                FridaUtils.startAndInject();
                injectedSystemUiPid = FridaUtils.systemUiPid();
            }
        } catch (Exception e) {
            Log.e(TAG, "[realtemp] watchdog erro: " + e.getMessage(), e);
        } finally {
            if (realTempEnabled) {
                backgroundHandler.postDelayed(realTempWatchdogRunnable, REAL_TEMP_WATCHDOG_MS);
            }
        }
    }

    // ─────────────────────────────
    // Card na Home — injeção Frida na MediaCenter
    // ─────────────────────────────

    /**
     * Registra o callback da UI e aplica a pref persistida. Chamado assim que o
     * Shizuku esta utilizavel, antes de connectToVehicleService(), para encurtar a
     * janela em que a fileira de midia online do OEM aparece depois do boot.
     * Idempotente: checkAndInitialize() pode rodar mais de uma vez.
     */
    private void bootstrapHomeCard() {
        if (homeCardBootstrapped) return;
        homeCardBootstrapped = true;

        ClimateStateHolder.INSTANCE.setOnHomeCardToggle(enabled ->
                backgroundHandler.post(() -> applyHomeCard(enabled)));

        boolean wanted = false;
        try {
            wanted = App.getContext()
                    .getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(KEY_HOME_CARD, false);
        } catch (Exception e) {
            Log.w(TAG, "Falha lendo pref home_card: " + e.getMessage());
        }
        if (wanted) {
            ClimateStateHolder.INSTANCE.setHomeCardEnabled(true);
            applyHomeCard(true);
        }
    }

    /** (Des)ativa o card de clima na tela principal da MediaCenter.
     *  Roda no backgroundHandler. */
    private void applyHomeCard(boolean enabled) {
        homeCardEnabled = enabled;
        backgroundHandler.removeCallbacks(homeCardWatchdogRunnable);
        if (enabled) {
            String msg = FridaUtils.startHomeCard();
            injectedMediaCenterPid = FridaUtils.mediaCenterPid();
            homeCardInjected = !injectedMediaCenterPid.isEmpty()
                    && FridaUtils.isHomeCardInjectionAlive();
            Log.w(TAG, "[homecard] ativado: " + msg + " (mediacenter pid="
                    + injectedMediaCenterPid + ", injetado=" + homeCardInjected + ")");
            // Se a MediaCenter ainda nao subiu (pid vazio no boot), reintenta em 1s
            // em vez de esperar o watchdog de 10s.
            backgroundHandler.postDelayed(homeCardWatchdogRunnable,
                    homeCardInjected ? HOME_CARD_WATCHDOG_MS : HOME_CARD_RETRY_MS);
        } else {
            // Grava "off" e só encerra o injetor depois que o script teve tempo de
            // restaurar a fileira — matá-lo antes congelaria a tela sem os ícones.
            String msg = FridaUtils.stopHomeCard();
            injectedMediaCenterPid = "";
            homeCardInjected       = false;
            backgroundHandler.postDelayed(() -> {
                if (!homeCardEnabled) FridaUtils.stopHomeCardInjection();
            }, HOME_CARD_RESTORE_MS);
            Log.w(TAG, "[homecard] desativado: " + msg);
        }
    }

    /** Watchdog: a cada 10s re-injeta se o injetor caiu ou a MediaCenter reiniciou. */
    private void homeCardWatchdogTick() {
        if (!homeCardEnabled) return;
        try {
            String currentPid = FridaUtils.mediaCenterPid();
            boolean restarted = !currentPid.isEmpty() && !currentPid.equals(injectedMediaCenterPid);
            if (restarted || !FridaUtils.isHomeCardInjectionAlive()) {
                Log.w(TAG, "[homecard] watchdog re-injetando (restart=" + restarted + ")");
                FridaUtils.startHomeCard();
                injectedMediaCenterPid = FridaUtils.mediaCenterPid();
                homeCardInjected = !injectedMediaCenterPid.isEmpty()
                        && FridaUtils.isHomeCardInjectionAlive();
            } else {
                homeCardInjected = true;
            }
        } catch (Exception e) {
            Log.e(TAG, "[homecard] watchdog erro: " + e.getMessage(), e);
        } finally {
            if (homeCardEnabled) {
                backgroundHandler.postDelayed(homeCardWatchdogRunnable,
                        homeCardInjected ? HOME_CARD_WATCHDOG_MS : HOME_CARD_RETRY_MS);
            }
        }
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
        backgroundHandler.removeCallbacks(realTempWatchdogRunnable);
        ClimateStateHolder.INSTANCE.setOnRealOutsideTempToggle(null);
        if (handlerThread != null) handlerThread.quitSafely();
        isServiceRunning = false;
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeRequestPermissionResultListener(permissionResultListener);
        Shizuku.removeBinderDeadListener(this);
        if (vehicleInitReceiver != null) {
            try { unregisterReceiver(vehicleInitReceiver); } catch (Exception ignored) {}
            vehicleInitReceiver = null;
        }
        try {
            if (controlService != null)
                controlService.unRegisterDataChangedListener(getPackageName(), vehicleDataListener);
        } catch (Exception ignored) {}
        mainHandler.post(() -> {
            ClimateStateHolder.INSTANCE.updateVehicleData(false, null, null, null, null, null);
            ClimateStateHolder.INSTANCE.commandCallback = null;
        });
        PersistentLog.w(TAG, "servico destruido");
        super.onDestroy();
    }

    @Override
    public void onBinderDead() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeBinderDeadListener(this);
        restart("binder do Shizuku morreu");
    }

    /** @param reason vai para o log persistente — e o que responde "por que reiniciou?". */
    private synchronized void restart(String reason) {
        isShizukuInitialized = false;
        isServiceRunning     = false;
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeRequestPermissionResultListener(permissionResultListener);
        Shizuku.removeBinderDeadListener(this);
        mainHandler.post(() -> ClimateStateHolder.INSTANCE.updateVehicleData(
                false, null, null, null, null, null));
        PersistentLog.w(TAG, "REINICIO agendado (+1s) — motivo: " + reason);
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
