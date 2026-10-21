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
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import br.com.redesurftank.havalclimatecontrol.App;
import br.com.redesurftank.havalclimatecontrol.CarProps;
import br.com.redesurftank.havalclimatecontrol.ClimateStateHolder;
import br.com.redesurftank.havalclimatecontrol.broadcastReceivers.RestartReceiver;
import br.com.redesurftank.havalclimatecontrol.utils.IPTablesUtils;
import br.com.redesurftank.havalclimatecontrol.utils.OverscanUtils;
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
    // Faixa de clima fixa. Device-protected pelo mesmo motivo: no boot, com a pref
    // desligada, o servico limpa o overscan que tenha sobrado — e isso pode rodar antes
    // do unlock, quando climate_ui_prefs ainda nao e legivel.
    private static final String KEY_CLIMATE_STRIP   = "climate_strip_enabled";

    // Prefs da UI (climate_ui_prefs) — lidos direto pelo serviço no boot, pois o
    // espelho em ClimateStateHolder só é populado quando a Activity é aberta.
    private static final String UI_PREFS_NAME         = "climate_ui_prefs";
    private static final String KEY_REAL_OUTSIDE_TEMP = "real_outside_temp_enabled";
    private static final String KEY_HOME_CARD         = "home_card_enabled";
    private static final String KEY_TURBO_ENABLED     = "turbo_enabled";
    private static final String KEY_TURBO_THRESHOLD   = "turbo_threshold";

    // ── Modo turbo ────────────────────────────────────────────────────────────
    // Persistido em climate_control_prefs (device-protected), e nao na UI: se o servico
    // morrer no meio do turbo, a instancia nova precisa saber que o AUTO desligado foi
    // obra nossa e que devolve-lo ainda e responsabilidade nossa.
    private static final String KEY_TURBO_ACTIVE      = "turbo_active";
    // Janela depois de uma escrita do turbo em que auto_enable = 1 NAO conta como o
    // motorista religando o AUTO — pode ser o eco atrasado do valor anterior.
    private static final long   TURBO_GRACE_MS        = 5_000;
    // Folga minima entre o limiar e a temperatura de saida. Sem ela, um alvo acima do
    // limiar (ou ilegivel, como "LO") faria o turbo ligar e desligar a cada leitura.
    private static final float  TURBO_MIN_DROP        = 2f;
    // Mesmo fallback da tela: fan_speed_range quase nunca chega (ver CarProps).
    private static final int    FAN_SPEED_MAX_FALLBACK = 7;

    // ── Limpeza do experimento de country code (removido) ──────────────────────
    // A supressao da fileira online por persist.bean.country.code foi abandonada: o OEM
    // reescreve a property sozinho (volta a 17 em segundos) e forcar o valor exigia um
    // `am force-stop` na MediaCenter a cada boot — ou seja, ERAMOS NOS reiniciando a Home.
    // Quem esconde a fileira agora e so o hook Frida (getMCpList vazio + repinta no mesmo
    // frame do onCreate). Mantemos apenas o necessario para desfazer, uma vez, o valor que
    // versoes 1.25.0 tenham deixado gravado nos devices em campo.
    private static final String COUNTRY_CODE_PROP      = "persist.bean.country.code";
    private static final String COUNTRY_CODE_DEFAULT   = "17";  // Brasil, se nao houver original salvo
    private static final String KEY_SAVED_COUNTRY_CODE = "saved_country_code";

    // ── Idade minima do processo alvo antes de injetar (anti-reinicio no boot) ──
    // Portao opcional: com isto > 0, so injetamos na MediaCenter depois que o processo
    // dela tem esse tempo de vida (evita attach num processo ainda subindo). Fica em 0
    // por padrao — o log mostrou que os reinicios da Home eram do nosso force-stop e do
    // crash do system_server, nao do attach; e adiar o attach so alargava a janela de
    // Deezer no boot. Subir de novo se o diagnostico apontar o attach como culpado.
    private static final long   MIN_MEDIACENTER_INJECT_AGE_MS = 0;

    private static final String HVAC_PACKAGE_NAME    = "com.beantechs.hvac";
    private static final String WEATHER_PACKAGE_NAME = "com.beantechs.weatherservice";
    // UM watchdog cobre os dois alvos Frida, e cada tick e UM shell (FridaUtils.probe).
    // Antes eram dois watchdogs de 10s com 2 shells cada = 24 newProcess por minuto,
    // para sempre. Cada newProcess deixa um RemoteProcessHolder no shizuku_server ate o
    // proxy binder deste lado ser coletado; em 29/08/2026 isso estourou o heap de 96MB
    // do server depois de 5h43 de sessao e derrubou o binder.
    private static final long   WATCHDOG_MIN_MS       = 10_000;
    // Enquanto nada muda o intervalo dobra ate aqui: num carro parado o SystemUI e a
    // MediaCenter ficam horas sem reiniciar, e vigiar de 10 em 10s so gera fork. Teto
    // em 30s e nao 60s de proposito: o que se paga aqui e a latencia para perceber um
    // restart do SystemUI (ate 30s sem a temperatura real na barra / sem o card), e o
    // fork economizado de 30s para 60s ja e irrelevante perto do corte de 24/min.
    private static final long   WATCHDOG_MAX_MS       = 30_000;
    // Enquanto a primeira injecao nao pegou (MediaCenter ainda subindo no boot),
    // reintenta rapido: cada segundo aqui e um segundo de fileira do OEM na tela.
    private static final long   HOME_CARD_RETRY_MS    = 1_000;
    // Janela para o script restaurar a fileira original antes de matarmos o injetor.
    private static final long   HOME_CARD_RESTORE_MS  = 3_000;
    // Janela em que o HVAC do OEM fica desabilitado depois de uma escrita. Cada ciclo
    // custa um `dumpsys activity`, um `pm disable-user`, um `am force-stop`, 150ms de
    // sleep e depois um `pm enable` — e o `pm` reescreve estado e dispara
    // PACKAGE_CHANGED para todo mundo. Com 300ms, mexer na tela por alguns segundos
    // virava um ciclo por toque: no log de 04/09 sao 5 ciclos em 7s, e a barra de status
    // do OEM respondeu a cada um tentando abrir o app desabilitado — 197 stack traces em
    // 12 minutos, no thread principal do SystemUI. Com a janela maior, uma rajada de
    // toques suspende UMA vez e reabilita no fim.
    private static final long   HVAC_RESUME_DELAY_MS = 2_500;
    private static final long   EVAL_DEBOUNCE_MS     = 50;     // coalesce bursts de onDataChanged numa única avaliação
    private static final long   IPTABLES_REFRESH_MS  = 60_000; // re-assert da regra iptables (idempotente, antes 15s)
    private static final long   BOOTSTRAP_BACKOFF_MAX_MS = 30_000;
    // Teto e passo da espera pelo binder depois que o starter mata um shizuku_server orfao.
    private static final long   OLD_SERVER_KILLED_WAIT_MS = 5_000;
    private static final long   OLD_SERVER_KILLED_POLL_MS = 100;
    // Medido em campo (log de 23/08): em boot frio o binder do Shizuku leva 4,3s e
    // 6,3s para chegar. O timeout antigo de 10s deixava 37% de margem — um boot mais
    // lento caia em restart(), que na pratica so recomeca a mesma espera. Esperar mais
    // e de graca: se o binder chega, seguimos na hora.
    private static final long   SHIZUKU_BINDER_TIMEOUT_MS    = 30_000;
    // Depois de subir o servidor via telnet o restart TEM valor (refaz o bootstrap),
    // então aqui a espera fica curta de proposito — mas nao tao curta quanto 5s.
    private static final long   SHIZUKU_BOOTSTRAP_TIMEOUT_MS = 15_000;

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

    /** true enquanto o AUTO esta desligado pelo turbo e cabe a nos religa-lo. */
    private boolean turboActive     = false;
    /** O motorista religou o AUTO no meio do turbo: nao entra de novo ate a cabine
     *  esfriar (limiar - TURBO_MIN_DROP) ou o carro dar nova partida. */
    private boolean turboOverridden = false;
    private long    turboWriteMs    = 0;      // elapsedRealtime da ultima escrita do turbo

    private boolean  isHvacSuspended    = false;
    private Runnable resumeHvacRunnable = null;

    // Temperatura Externa Real (UI) — injeção Frida no SystemUI
    private volatile boolean realTempEnabled = false;
    private String  injectedSystemUiPid      = "";

    // Card na Home (MediaCenter) — injeção Frida
    private volatile boolean homeCardEnabled    = false;
    private volatile boolean homeCardBootstrapped = false;
    private volatile boolean homeCardInjected     = false;
    private String  injectedMediaCenterPid      = "";
    /** pid onde a injecao do card ja falhou por falta das classes; "" se nenhum. */
    private String  homeCardWrongPid            = "";
    /**
     * elapsedRealtime do ULTIMO attach que disparamos na MediaCenter (applyHomeCard ou
     * watchdog). Serve so para o diagnostico: quando o pid da MediaCenter muda, medimos
     * o Δt desde aqui — se ela morre sempre poucos ms/s depois do nosso attach, o dedo e
     * nosso; se morre longe de qualquer attach, e a MediaCenter/OEM reiniciando sozinho.
     */
    private long    lastMediaCenterInjectMs     = 0;
    /** Ultimo pid para o qual ja logamos "adiado por idade"; evita repetir a cada tick. */
    private String  deferredAgePid              = "";

    // Faixa de clima fixa — overlay criado e removido so na main thread.
    private ClimateStripOverlay climateStrip;
    private volatile boolean climateStripBootstrapped = false;

    // Watchdog unico dos dois alvos Frida, com intervalo adaptativo.
    private final Runnable injectionWatchdogRunnable = this::injectionWatchdogTick;
    private long watchdogIntervalMs = WATCHDOG_MIN_MS;
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
            // O carro aceitou ou devolveu o valor antigo? E a pergunta que nenhum log
            // respondeu ate agora para a ventilacao do banco.
            if (CarProps.DRIVER_SEAT_VENT.equals(key) || CarProps.PASSENGER_SEAT_VENT.equals(key)) {
                PersistentLog.w(TAG, "carro reportou " + key + " = " + value);
            }
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
                            telnetClient.disconnect();
                            if (Pattern.compile("killed \\d+ \\(shizuku_server\\)").matcher(result).find()) {
                                // O starter matou um shizuku_server antigo. Acontece a cada
                                // soft reboot do framework: o server (root, processo proprio)
                                // sobrevive a morte do system_server, mas fica orfao e inutil.
                                // Antes havia um Thread.sleep(5000) fixo aqui, e no log de
                                // 2026-09-02 o binder chegou "apos 1ms" do fim da espera — ou
                                // seja, ja estava pronto e a Home ficou 5s a mais sem o card.
                                // Espera o binder novo de verdade, no maximo esse mesmo teto.
                                long t0 = SystemClock.elapsedRealtime();
                                while (!ShizukuUtils.isAvailable()
                                        && SystemClock.elapsedRealtime() - t0 < OLD_SERVER_KILLED_WAIT_MS) {
                                    Thread.sleep(OLD_SERVER_KILLED_POLL_MS);
                                }
                                PersistentLog.w(TAG, "shizuku_server antigo morto pelo starter — binder novo "
                                        + (ShizukuUtils.isAvailable() ? "pronto" : "ainda nao chegou")
                                        + " apos " + (SystemClock.elapsedRealtime() - t0) + "ms");
                            }

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
        // Mesma logica: a faixa (ou a limpeza do overscan que sobrou) so depende do Shizuku.
        backgroundHandler.post(this::bootstrapClimateStrip);

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
            controlService.addListenerKey(getPackageName(), CarProps.ALL);
            controlService.registerDataChangedListener(getPackageName(), vehicleDataListener);

            String[] values = controlService.fetchDatas(CarProps.ALL);
            if (values != null) {
                for (int i = 0; i < CarProps.ALL.length && i < values.length; i++) {
                    if (values[i] != null) dataCache.put(CarProps.ALL[i], values[i]);
                }
            }

            Log.w(TAG, "Connected to vehicle service — auto=" + dataCache.get(CarProps.AUTO_ENABLE)
                    + " inside=" + dataCache.get(CarProps.INSIDE_TEMP)
                    + " set=" + dataCache.get(CarProps.DRIVER_TEMP)
                    + " power=" + dataCache.get(CarProps.POWER_MODE));

            // O callback chega da UI. Escrita duplicada e descartada: o backgroundHandler
            // e UM so e a escrita entra na fila atras do watchdog, do iptables e do
            // suspend/resume do HVAC. No log de 04/09 a primeira escrita saiu em 210ms e
            // as seguintes levaram 1,4s e 2,1s — a tela nao trocava de estado nesse meio,
            // o usuario clicava de novo, e entraram DOZE escritas do mesmo cycle_mode=2
            // numa rajada de 7,4s. Com o guarda, mash de botao vira uma escrita.
            ClimateStateHolder.INSTANCE.commandCallback = (key, value) -> {
                // O descarte de "mesmo valor ja em voo" saiu daqui. Ele nunca chegou a
                // disparar nos casos reais — as escritas terminavam antes do clique
                // seguinte — e era mais um caminho que sumia com o comando sem deixar
                // rastro, justamente o que atrapalhou o diagnostico do botao do banco.
                PersistentLog.w(TAG, "comando recebido da UI: " + key + " = " + value);
                backgroundHandler.post(() -> {
                    try {
                        sendHvacCommand(key, value);
                        dataCache.put(key, value);
                        Log.w(TAG, "Command sent: " + key + " = " + value);
                        pushState(true, null);
                    } catch (Exception e) {
                        PersistentLog.e(TAG, "falha ao escrever " + key + " = " + value + ": " + e);
                    }
                });
            };

            ClimateStateHolder.INSTANCE.setOnUiVisibilityChange(visible ->
                    backgroundHandler.post(() -> applyUiVisibility(visible)));

            // Aplica o estado ATUAL, e nao so as mudancas: a Activity pode ter chamado
            // setUiVisible(true) antes deste callback existir — o binder do Shizuku leva
            // segundos no boot — e ai o servico acharia que a tela esta protegida sem que
            // ninguem tenha desabilitado o pacote.
            backgroundHandler.post(() -> {
                if (ClimateStateHolder.INSTANCE.getUiVisible()) {
                    applyUiVisibility(true);
                } else if (!isHvacSuspended) {
                    // Rede de seguranca: se o app morreu com o pacote desabilitado, o HVAC
                    // do OEM ficaria inutilizavel para sempre. `pm enable` e idempotente,
                    // custa um shell no start e limpa disable orfao de sessao anterior.
                    ShizukuUtils.runCommandAndGetOutput(
                            new String[]{"pm", "enable", HVAC_PACKAGE_NAME});
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

            // Modo turbo: mesma razao — a avaliacao roda com a tela fechada, entao o
            // espelho tem que vir da pref, nao do default do ClimateStateHolder.
            try {
                SharedPreferences uiPrefs = App.getContext()
                        .getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE);
                ClimateStateHolder.INSTANCE.setTurboEnabled(
                        uiPrefs.getBoolean(KEY_TURBO_ENABLED, true));
                ClimateStateHolder.INSTANCE.setTurboThreshold(uiPrefs.getInt(
                        KEY_TURBO_THRESHOLD, ClimateStateHolder.TURBO_THRESHOLD_DEFAULT));
            } catch (Exception e) {
                Log.w(TAG, "Falha lendo prefs do turbo: " + e.getMessage());
            }
            turboActive = App.getDeviceProtectedContext()
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(KEY_TURBO_ACTIVE, false);
            if (turboActive) {
                PersistentLog.w(TAG, "turbo estava ativo quando o servico caiu — retomando");
            }
            ClimateStateHolder.INSTANCE.setOnEvaluateRequest(() -> {
                backgroundHandler.removeCallbacks(evalRunnable);
                backgroundHandler.post(evalRunnable);
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
            if (!ClimateStateHolder.INSTANCE.getAutoControlEnabled()) {
                // O turbo e parte do controle automatico: desligado o controle, o AUTO
                // que o turbo tirou tem que voltar — senao o HVAC fica preso no maximo.
                String log = null;
                if (turboActive && "1".equals(dataCache.get(CarProps.POWER_MODE))) {
                    log = exitTurbo("controle automático do app desligado");
                }
                pushState(true, log);
                return;
            }

            String insideTempStr = dataCache.get(CarProps.INSIDE_TEMP);
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
            boolean carJustStarted = insideTempWasOffline;
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
                String acEnableStr = dataCache.get(CarProps.AC_ENABLE);
                if (!"1".equals(acEnableStr) && "1".equals(dataCache.get(CarProps.AUTO_ENABLE))) {
                    sendHvacCommand(CarProps.AC_ENABLE, "1");
                    dataCache.put(CarProps.AC_ENABLE, "1");
                    acOffTimestamp = 0;
                    backgroundHandler.removeCallbacks(acOffCheckRunnable);
                    logEntry = timeFormat.format(new Date()) + "  AC ligado — partida do carro (30s protegido)";
                }
            }

            // Bloco T — Modo turbo. Antes do bloco A: ao entrar ele desliga o AUTO no
            // dataCache, e o bloco A (que exige AUTO) deixa de mexer no AC e na curva.
            String turboLog = evaluateTurbo(insideTemp, carJustStarted);
            if (logEntry == null) logEntry = turboLog;

            // Bloco A — AC + comfort curve (requer modo auto do HVAC ligado)
            String autoEnable = dataCache.get(CarProps.AUTO_ENABLE);
            if ("1".equals(autoEnable)) {
                String driverTempStr = dataCache.get(CarProps.DRIVER_TEMP);
                String acEnableStr   = dataCache.get(CarProps.AC_ENABLE);
                if (driverTempStr != null && acEnableStr != null) {
                    float setTemp  = Float.parseFloat(driverTempStr);
                    boolean isAcOn = "1".equals(acEnableStr);

                    // Histerese variável conforme temperatura externa
                    float hysteresis = 0.5f;
                    String outsideTempStr = dataCache.get(CarProps.OUTSIDE_TEMP);
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
                        sendHvacCommand(CarProps.AC_ENABLE, "0");
                        dataCache.put(CarProps.AC_ENABLE, "0");
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
                        sendHvacCommand(CarProps.AC_ENABLE, "1");
                        dataCache.put(CarProps.AC_ENABLE, "1");
                        acOffTimestamp = 0;
                        backgroundHandler.removeCallbacks(acOffCheckRunnable);
                        logEntry = timeFormat.format(new Date()) + "  " + msg;
                    }

                    String currentCurve = dataCache.get(CarProps.COMFORT_CURVE);
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
                                String oStr = dataCache.get(CarProps.OUTSIDE_TEMP);
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
                            sendHvacCommand(CarProps.COMFORT_CURVE, desiredCurve);
                            dataCache.put(CarProps.COMFORT_CURVE, desiredCurve);
                            if (logEntry == null) logEntry = timeFormat.format(new Date()) + "  " + msg;
                        }
                    }
                }
            }

            // Bloco B — Ventilação dos bancos (independente do modo auto do HVAC)
            boolean seatVentAutoNow = ClimateStateHolder.INSTANCE.getSeatVentAutoEnabled();
            if (seatVentAutoNow) {
                String currentDriverVent    = dataCache.get(CarProps.DRIVER_SEAT_VENT);
                String currentPassengerVent = dataCache.get(CarProps.PASSENGER_SEAT_VENT);

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
                        sendHvacCommand(CarProps.DRIVER_SEAT_VENT, desiredVentLevel);
                        dataCache.put(CarProps.DRIVER_SEAT_VENT, desiredVentLevel);
                    }
                    if (!desiredVentLevel.equals(currentPassengerVent)) {
                        lastSentPassengerVent = desiredVentLevel;
                        sendHvacCommand(CarProps.PASSENGER_SEAT_VENT, desiredVentLevel);
                        dataCache.put(CarProps.PASSENGER_SEAT_VENT, desiredVentLevel);
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
            String outsideTempForHeating = dataCache.get(CarProps.OUTSIDE_TEMP);
            if (outsideTempForHeating != null) {
                try {
                    float outsideTemp = Float.parseFloat(outsideTempForHeating);
                    String desiredHeating = outsideTemp < 20f ? "1" : "0";
                    String currentHeating = dataCache.get(CarProps.HEATING);
                    if (!desiredHeating.equals(currentHeating)) {
                        String msg = String.format(Locale.getDefault(),
                                "Aquecimento → %s — externa %.1f°C",
                                "1".equals(desiredHeating) ? "ligado" : "desligado", outsideTemp);
                        Log.w(TAG, msg);
                        sendHvacCommand(CarProps.HEATING, desiredHeating);
                        dataCache.put(CarProps.HEATING, desiredHeating);
                        if (logEntry == null) logEntry = timeFormat.format(new Date()) + "  " + msg;
                    }
                } catch (NumberFormatException ignored) {}
            }

            pushState(true, logEntry);
        } catch (Exception e) {
            Log.e(TAG, "Error evaluating climate control: " + e.getMessage(), e);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Modo turbo
    // ─────────────────────────────────────────────────────────────

    /**
     * Cabine acima do limiar → sai do AUTO e poe o ventilador no maximo; cabine no alvo →
     * devolve o AUTO. Roda no backgroundHandler, dentro de evaluateClimateControl.
     *
     * So entra a partir do AUTO: com o HVAC em manual o ventilador ja e escolha do
     * motorista. E nao briga com ele: o maximo e escrito uma vez na entrada; se o
     * motorista baixar o ventilador, fica como ele deixou ate a saida. Religar o AUTO,
     * por outro lado, cancela o turbo.
     *
     * @return a linha de log da transicao, ou null se nada mudou.
     */
    private String evaluateTurbo(float insideTemp, boolean carJustStarted) throws Exception {
        // HVAC desligado: nem entra nem sai. Escrever auto_enable com ele desligado pode
        // religa-lo, e o dever de devolver o AUTO continua de pe para quando o motorista
        // ligar de novo.
        if (!"1".equals(dataCache.get(CarProps.POWER_MODE))) return null;

        ClimateStateHolder s = ClimateStateHolder.INSTANCE;
        int threshold = s.getTurboThreshold();

        if (!turboActive) {
            if (turboOverridden && (carJustStarted || insideTemp <= threshold - TURBO_MIN_DROP)) {
                turboOverridden = false;
            }
            if (!s.getTurboEnabled() || turboOverridden) return null;
            if (insideTemp <= threshold) return null;
            if (!"1".equals(dataCache.get(CarProps.AUTO_ENABLE))) return null;
            return enterTurbo(String.format(Locale.getDefault(),
                    "Turbo ligado — interna %.1f°C > %d°C", insideTemp, threshold));
        }

        if (!s.getTurboEnabled()) return exitTurbo("desligado nas configurações");

        float exitTemp = turboExitTemp(threshold);
        if (insideTemp <= exitTemp) {
            return exitTurbo(String.format(Locale.getDefault(),
                    "interna %.1f°C ≤ alvo %.1f°C", insideTemp, exitTemp));
        }

        // Na partida o firmware pode ter voltado ao AUTO e ao ventilador dele por conta
        // propria — isso nao e o motorista, entao reaplica em vez de cancelar.
        if (carJustStarted) {
            return enterTurbo(String.format(Locale.getDefault(),
                    "Turbo reaplicado na partida — interna %.1f°C", insideTemp));
        }

        boolean pastGrace = SystemClock.elapsedRealtime() - turboWriteMs > TURBO_GRACE_MS;
        if (pastGrace && "1".equals(dataCache.get(CarProps.AUTO_ENABLE))) {
            setTurboActive(false);
            turboOverridden = true;
            return logTurbo("Turbo cancelado — AUTO religado manualmente");
        }
        return null;
    }

    /** Alvo de saida: a temperatura ajustada do motorista, mas sempre pelo menos
     *  TURBO_MIN_DROP abaixo do limiar. */
    private float turboExitTemp(int threshold) {
        float exitTemp = threshold - TURBO_MIN_DROP;
        try {
            exitTemp = Math.min(exitTemp, Float.parseFloat(dataCache.get(CarProps.DRIVER_TEMP)));
        } catch (RuntimeException ignored) {}   // null ou "LO"/"HI": fica o limiar - folga
        return exitTemp;
    }

    private String enterTurbo(String reason) throws Exception {
        String fanMax = String.valueOf(fanSpeedMax());
        // AUTO primeiro, para o ventilador ja ser escrito com o HVAC em manual.
        sendHvacCommand(CarProps.AUTO_ENABLE, "0");
        dataCache.put(CarProps.AUTO_ENABLE, "0");
        sendHvacCommand(CarProps.FAN_SPEED, fanMax);
        dataCache.put(CarProps.FAN_SPEED, fanMax);
        // Fora do AUTO o bloco A para de cuidar do compressor; ventilador no maximo sem
        // ele so sopra o ar quente da propria cabine.
        if (!"1".equals(dataCache.get(CarProps.AC_ENABLE))) {
            sendHvacCommand(CarProps.AC_ENABLE, "1");
            dataCache.put(CarProps.AC_ENABLE, "1");
            acOffTimestamp = 0;
            backgroundHandler.removeCallbacks(acOffCheckRunnable);
        }
        turboWriteMs = SystemClock.elapsedRealtime();
        setTurboActive(true);
        return logTurbo(reason + " → AUTO desligado, ventilador " + fanMax);
    }

    private String exitTurbo(String reason) throws Exception {
        sendHvacCommand(CarProps.AUTO_ENABLE, "1");
        dataCache.put(CarProps.AUTO_ENABLE, "1");
        setTurboActive(false);
        return logTurbo("Turbo desligado — " + reason + " → AUTO religado");
    }

    private void setTurboActive(boolean active) {
        turboActive = active;
        App.getDeviceProtectedContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_TURBO_ACTIVE, active).apply();
    }

    /** Transicoes do turbo vao para o log persistente mesmo quando outra linha ganha o
     *  unico slot de log da avaliacao — sao elas que explicam um ventilador no maximo. */
    private String logTurbo(String msg) {
        PersistentLog.w(TAG, msg);
        return timeFormat.format(new Date()) + "  " + msg;
    }

    /** Mesma regra da tela (intOf em OemClimateScreen): ultimo numero de fan_speed_range. */
    private int fanSpeedMax() {
        String raw = dataCache.get(CarProps.FAN_SPEED_RANGE);
        if (raw != null) {
            Matcher m = Pattern.compile("\\d+").matcher(raw);
            String last = null;
            while (m.find()) last = m.group();
            try {
                if (last != null && Integer.parseInt(last) >= 1) return Integer.parseInt(last);
            } catch (NumberFormatException ignored) {}
        }
        return FAN_SPEED_MAX_FALLBACK;
    }

    private void pushState(boolean connected, String logEntry) {
        // Copia defensiva: dataCache continua sendo mutado na backgroundHandler enquanto
        // o post abaixo ainda nao rodou na main. A associacao passou a ser por chave
        // porque com 31 propriedades a lista posicional so criava bug silencioso.
        final Map<String, String> snapshot = new HashMap<>(dataCache);
        final String finalLog = logEntry;
        final boolean turbo = turboActive;

        mainHandler.post(() -> {
            ClimateStateHolder.INSTANCE.updateFromCache(connected, snapshot);
            ClimateStateHolder.INSTANCE.setTurboActive(turbo);
            if (finalLog != null) {
                ClimateStateHolder.INSTANCE.addLog(finalLog);
            }
        });
    }

    /**
     * Escreve uma propriedade no veiculo.
     *
     * Por que existe supressao aqui: a barra de status do OEM reage a QUALQUER mudanca de
     * propriedade abrindo o app de clima da central (`BeanStatusBarManager.handleValue` ->
     * `AppUtils.startApp`), e ele roubaria o foco. Desabilitar o pacote faz esse
     * startActivity falhar — os stack traces de `checkStartActivityResult` no logcat sao a
     * protecao funcionando, nao um bug.
     *
     * Enquanto a NOSSA tela esta na frente o pacote ja fica desabilitado pela sessao toda
     * (ver {@link #applyUiVisibility}), e aqui nao se paga nada. O ciclo por escrita ficou
     * so para o caminho automatico — histerese, curva de conforto, ventilacao dos bancos —
     * que roda com a tela fora e onde o app do OEM pularia na cara do motorista.
     */
    private void sendHvacCommand(String key, String value) throws Exception {
        boolean uiInFront = ClimateStateHolder.INSTANCE.getUiVisible();
        if (!uiInFront) ensureHvacSuspended(key);
        controlService.request("cmd.common.request.set", key, value);
        if (!uiInFront) scheduleHvacResumption();
    }

    /**
     * Liga/desliga a supressao do HVAC do OEM conforme a nossa Activity aparece e sai.
     *
     * Um `pm disable-user` + `am force-stop` por sessao no lugar de um por escrita. No log
     * de 04/09 foram 5 ciclos em 7s de interacao, cada um custando um `dumpsys activity`,
     * duas operacoes de `pm` (que reescrevem estado e disparam PACKAGE_CHANGED para todo
     * mundo) e 150ms de sleep. Roda no backgroundHandler.
     */
    private void applyUiVisibility(boolean visible) {
        if (visible) {
            // Sem o `dumpsys` do ensureHvacSuspended: se a NOSSA Activity acabou de entrar
            // em foco, o app do OEM nao pode estar em foreground.
            if (resumeHvacRunnable != null) {
                backgroundHandler.removeCallbacks(resumeHvacRunnable);
                resumeHvacRunnable = null;
            }
            if (!isHvacSuspended) {
                Log.w(TAG, "Tela do app em foco — suspendendo HVAC do OEM pela sessao");
                suspendHvacNow();
            }
        } else if (isHvacSuspended) {
            Log.w(TAG, "Tela do app saiu — reabilitando HVAC do OEM");
            resumeHvacNow();
        }
    }

    private void suspendHvacNow() {
        ShizukuUtils.runCommandAndGetOutput(new String[]{"pm", "disable-user", "--user", "0", HVAC_PACKAGE_NAME});
        ShizukuUtils.runCommandAndGetOutput(new String[]{"am", "force-stop", HVAC_PACKAGE_NAME});
        isHvacSuspended = true;
        SystemClock.sleep(150);
    }

    private void resumeHvacNow() {
        if (resumeHvacRunnable != null) {
            backgroundHandler.removeCallbacks(resumeHvacRunnable);
            resumeHvacRunnable = null;
        }
        ShizukuUtils.runCommandAndGetOutput(new String[]{"pm", "enable", HVAC_PACKAGE_NAME});
        isHvacSuspended = false;
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
            suspendHvacNow();
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
            // A tela pode ter entrado em foco no meio da janela: ai a supressao passa a
            // ser da sessao e nao ha o que reabilitar.
            if (ClimateStateHolder.INSTANCE.getUiVisible()) {
                resumeHvacRunnable = null;
                return;
            }
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
        if (enabled) {
            // Desativa o serviço nativo de previsão do tempo da central.
            ShizukuUtils.runCommandAndGetOutput(new String[]{"pm", "disable-user", "--user", "0", WEATHER_PACKAGE_NAME});
            ShizukuUtils.runCommandAndGetOutput(new String[]{"am", "force-stop", WEATHER_PACKAGE_NAME});
            // Injeta o hook que lê o sensor real e repinta a barra.
            String msg = FridaUtils.startAndInject();
            injectedSystemUiPid = FridaUtils.systemUiPid();
            Log.w(TAG, "[realtemp] ativado: " + msg + " (systemui pid=" + injectedSystemUiPid + ")");
        } else {
            // Para a injeção e reativa o serviço nativo de previsão do tempo.
            String msg = FridaUtils.stop();
            injectedSystemUiPid = "";
            ShizukuUtils.runCommandAndGetOutput(new String[]{"pm", "enable", WEATHER_PACKAGE_NAME});
            Log.w(TAG, "[realtemp] desativado: " + msg);
        }
        // O watchdog e compartilhado: reagenda no minimo (ou para, se ninguem mais quer).
        rearmInjectionWatchdog(WATCHDOG_MIN_MS);
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

        // Desfaz, uma vez, o country code que a 1.25.0 possa ter deixado gravado.
        restoreCountryCodeIfNeeded();

        boolean wanted = false;
        try {
            wanted = App.getContext()
                    .getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(KEY_HOME_CARD, false);
        } catch (Exception e) {
            Log.w(TAG, "Falha lendo pref do card: " + e.getMessage());
        }
        if (wanted) {
            ClimateStateHolder.INSTANCE.setHomeCardEnabled(true);
            applyHomeCard(true);
        }
    }

    // ─────────────────────────────
    // Faixa de clima fixa — overlay + wm overscan
    // ─────────────────────────────

    /**
     * Registra o callback da UI e reconcilia o overscan com a pref. Roda uma vez por
     * instancia do servico, no backgroundHandler, assim que o Shizuku esta utilizavel.
     *
     * Com a pref desligada e a limpeza do boot: o overscan sobrevive ao reboot, entao uma
     * faixa que ficou reservada (app que caiu, versao anterior, pref desligada com o
     * servico morto) deixaria uma tarja preta em todas as telas ate alguem desfazer.
     */
    private void bootstrapClimateStrip() {
        if (climateStripBootstrapped) return;
        climateStripBootstrapped = true;

        ClimateStateHolder.INSTANCE.setOnClimateStripToggle(enabled ->
                backgroundHandler.post(() -> applyClimateStrip(enabled)));

        boolean wanted = App.getDeviceProtectedContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_CLIMATE_STRIP, false);
        if (wanted) {
            applyClimateStrip(true);
        } else {
            OverscanUtils.clearIfOurs("boot com a faixa desligada");
        }
    }

    /** (Des)ativa a faixa. Roda no backgroundHandler; a janela vai para a main. */
    private void applyClimateStrip(boolean enabled) {
        if (enabled) {
            // A central nao tem tela de "sobrepor a outros apps"; o appop pelo shell do
            // Shizuku e o que o Settings faria. Idempotente.
            ShizukuUtils.ShellResult r = ShizukuUtils.run(new String[]{
                    "appops", "set", getPackageName(), "SYSTEM_ALERT_WINDOW", "allow"});
            if (!r.ok()) {
                PersistentLog.e(TAG, "[faixa] appop SYSTEM_ALERT_WINDOW falhou: " + r.describeFailure());
            }
            // Overscan ANTES da janela: o overlay mede a propria posicao logo depois de
            // entrar, e precisa medir com o frame ja encolhido.
            OverscanUtils.applyBottom(ClimateStripOverlay.STRIP_HEIGHT_PX);
            mainHandler.post(() -> {
                if (!isServiceRunning) return;
                if (climateStrip == null) climateStrip = new ClimateStripOverlay(this);
                climateStrip.show();
            });
        } else {
            mainHandler.post(() -> {
                if (climateStrip != null) climateStrip.hide();
            });
            OverscanUtils.clearIfOurs("faixa desligada");
        }
    }

    /**
     * Restaura, uma unica vez, o persist.bean.country.code que a 1.25.0 sobrescreveu para
     * esconder a fileira online. Roda so se ha um valor salvo (KEY_SAVED_COUNTRY_CODE);
     * repoe o original e limpa a marca. Sem force-stop: o hook Frida ja esconde a fileira,
     * e o proprio OEM reescreve a property no boot de qualquer jeito — nao vale reiniciar
     * a Home so por isso.
     */
    private void restoreCountryCodeIfNeeded() {
        try {
            SharedPreferences sp = App.getContext()
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            if (!sp.contains(KEY_SAVED_COUNTRY_CODE)) return;
            String original = sp.getString(KEY_SAVED_COUNTRY_CODE, COUNTRY_CODE_DEFAULT);
            String current  = ShizukuUtils.runCommandAndGetOutput(
                    new String[]{"getprop", COUNTRY_CODE_PROP}).trim();
            if (!original.equals(current)) {
                ShizukuUtils.runCommandAndGetOutput(new String[]{"sh", "-c", "setenforce 0"});
                ShizukuUtils.runCommandAndGetOutput(
                        new String[]{"setprop", COUNTRY_CODE_PROP, original});
            }
            sp.edit().remove(KEY_SAVED_COUNTRY_CODE).apply();
            PersistentLog.w(TAG, "[online-row] experimento revertido: " + COUNTRY_CODE_PROP
                    + " restaurado para " + original + " (estava " + current + ")");
        } catch (Exception e) {
            Log.w(TAG, "Falha revertendo country code: " + e.getMessage());
        }
    }

    /** (Des)ativa o card de clima na tela principal da MediaCenter.
     *  Roda no backgroundHandler. */
    private void applyHomeCard(boolean enabled) {
        homeCardEnabled = enabled;
        if (enabled) {
            // Portao anti-reinicio: se o processo da MediaCenter ainda e muito novo, nao
            // ataca agora — o watchdog reintenta no ritmo rapido ate ele amadurecer.
            if (shouldDeferMediaCenterAttach(FridaUtils.mediaCenterPid())) {
                homeCardInjected = false;
                rearmInjectionWatchdog(HOME_CARD_RETRY_MS);
                return;
            }
            lastMediaCenterInjectMs = SystemClock.elapsedRealtime();
            String msg = FridaUtils.startHomeCard();
            injectedMediaCenterPid = FridaUtils.mediaCenterPid();
            homeCardInjected = !injectedMediaCenterPid.isEmpty()
                    && FridaUtils.isHomeCardInjectionAlive();
            // Em disco (nao so logcat): o upload de diagnostico nao le logcat, entao sem
            // isto o attach que reinicia a MediaCenter fica invisivel. uptime do device
            // separa "attach no boot" (alvo fragil) de "attach com a central estavel".
            PersistentLog.w(TAG, "[homecard] attach: " + msg + " (mediacenter pid="
                    + injectedMediaCenterPid + ", injetado=" + homeCardInjected
                    + ", uptime " + (SystemClock.elapsedRealtime() / 1000) + "s)");
            if (!homeCardInjected) logMediaCenterInjectorTail("attach nao pegou");
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
        // Se a MediaCenter ainda nao subiu (pid vazio no boot), reintenta em 1s em vez
        // de esperar o ciclo normal: cada segundo aqui e um segundo de fileira do OEM.
        rearmInjectionWatchdog(homeCardEnabled && !homeCardInjected
                ? HOME_CARD_RETRY_MS : WATCHDOG_MIN_MS);
    }

    // -----------------------------
    // Watchdog unico das injecoes Frida
    // -----------------------------

    /**
     * Colhe, em disco, a causa da morte da MediaCenter quando o pid dela muda. Responde
     * a pergunta central do diagnostico: fomos nos que a derrubamos (attach num processo
     * ainda subindo -> SIGSEGV nativo) ou o proprio OEM a reiniciou (kill por memoria,
     * sequencia de boot)?
     *
     * Le so os buffers do logcat, que o shell do Shizuku enxerga sem root:
     *   - events: am_proc_died / am_kill / am_proc_start da MediaCenter — diz se foi kill
     *     (e o motivo textual) ou restart limpo;
     *   - crash: se ela crashou de verdade, o sinal/abort aparece aqui.
     * Tudo com `-d` (dump e sai) e `-t` (so a cauda), para nunca bloquear o watchdog.
     * Roda no backgroundHandler, como o resto do tick.
     */
    private void captureMediaCenterDeath(String oldPid, String newPid) {
        try {
            String cmd =
                "echo '-- events (am_proc_died/kill/start) --';"
              + " logcat -b events -d -t 500 2>/dev/null"
              + "  | grep -iE 'am_proc_died|am_kill|am_crash|am_anr|am_proc_start'"
              + "  | grep -i 'beantechs.mediacenter' | tail -12;"
              + " echo '-- crash --';"
              + " logcat -b crash -d -t 400 2>/dev/null"
              + "  | grep -iE 'beantechs.mediacenter|DEBUG|signal|libc|tombstone'"
              + "  | tail -20";
            String out = ShizukuUtils.runCommandAndGetOutput(new String[]{"sh", "-c", cmd});
            if (out == null) out = "";
            out = out.trim();
            PersistentLog.write(TAG, "[homecard] causa da morte da MediaCenter (pid " + oldPid
                    + " -> " + newPid + "):\n"
                    + (out.isEmpty() ? "(logcat vazio/indisponivel para o shell)" : out));
        } catch (Exception e) {
            PersistentLog.write(TAG, "[homecard] falha colhendo causa da morte: " + e);
        }
    }

    /**
     * true se devemos ADIAR o attach na MediaCenter porque o processo dela ainda e muito
     * novo (portao anti-reinicio no boot, {@link #MIN_MEDIACENTER_INJECT_AGE_MS}).
     *
     * Conservador de proposito: idade desconhecida (-1) NAO adia — so adiamos quando temos
     * certeza de que o processo e novo. Loga uma vez por pid para nao poluir o tick rapido.
     */
    private boolean shouldDeferMediaCenterAttach(String pid) {
        if (MIN_MEDIACENTER_INJECT_AGE_MS <= 0) return false;
        if (pid == null || pid.isEmpty()) return false;   // sem processo: o retry normal cuida
        long age = FridaUtils.processAgeMs(pid);
        if (age < 0) return false;                         // nao sei a idade: nao bloqueia
        if (age >= MIN_MEDIACENTER_INJECT_AGE_MS) return false;
        if (!pid.equals(deferredAgePid)) {
            deferredAgePid = pid;
            PersistentLog.w(TAG, "[homecard] MediaCenter pid " + pid + " tem " + age
                    + "ms (< " + MIN_MEDIACENTER_INJECT_AGE_MS + "ms) — adiando attach"
                    + " (uptime " + (SystemClock.elapsedRealtime() / 1000) + "s)");
        }
        return true;
    }

    /** Cauda do .log do injetor do card em disco — o porque de um attach nao ter pego. */
    private void logMediaCenterInjectorTail(String motivo) {
        try {
            String log = FridaUtils.TARGET_MEDIA_CENTER.logPath();
            String out = ShizukuUtils.runCommandAndGetOutput(
                    new String[]{"sh", "-c", "tail -c 1500 " + log + " 2>/dev/null"});
            if (out == null) out = "";
            out = out.trim();
            PersistentLog.write(TAG, "[homecard] " + motivo + " — cauda de " + log + ":\n"
                    + (out.isEmpty() ? "(log do injetor vazio/ausente)" : out));
        } catch (Exception e) {
            PersistentLog.write(TAG, "[homecard] falha lendo log do injetor: " + e);
        }
    }

    /**
     * (Re)agenda o watchdog compartilhado, ou o encerra se nenhum alvo esta ligado.
     * Sempre passar por aqui: agendar direto no handler deixaria dois ticks vivos.
     */
    private void rearmInjectionWatchdog(long delayMs) {
        backgroundHandler.removeCallbacks(injectionWatchdogRunnable);
        watchdogIntervalMs = delayMs;
        if (!realTempEnabled && !homeCardEnabled) return;
        backgroundHandler.postDelayed(injectionWatchdogRunnable, delayMs);
    }

    /**
     * Re-injeta o que caiu, num unico shell para os dois alvos.
     *
     * O intervalo e adaptativo: dobra ate WATCHDOG_MAX_MS enquanto nada muda e volta a
     * WATCHDOG_MIN_MS a cada re-injecao ou troca de pid. Numa sessao longa parada isso
     * derruba a taxa de fork no shizuku_server de ~24/min para ~2/min — que e o ponto,
     * ja que foi o acumulo desses forks que estourou o heap do server em 29/08/2026.
     */
    private void injectionWatchdogTick() {
        if (!realTempEnabled && !homeCardEnabled) return;

        long next = watchdogIntervalMs;
        try {
            List<FridaUtils.Target> want = new ArrayList<>(2);
            if (realTempEnabled) want.add(FridaUtils.TARGET_SYSTEM_UI);
            if (homeCardEnabled) want.add(FridaUtils.TARGET_MEDIA_CENTER);
            FridaUtils.Target[] targets = want.toArray(new FridaUtils.Target[0]);

            FridaUtils.TargetStatus[] status = FridaUtils.probe(targets);
            if (status == null) {
                // Estado desconhecido (o shell falhou). Re-injetar as cegas reiniciaria
                // o fridaserver a toa e piscaria a tela — melhor so olhar de novo logo.
                next = WATCHDOG_MIN_MS;
                return;
            }

            boolean changed = false;
            for (int i = 0; i < targets.length; i++) {
                FridaUtils.TargetStatus st = status[i];
                if (targets[i] == FridaUtils.TARGET_SYSTEM_UI) {
                    boolean restarted = !st.pid.isEmpty() && !st.pid.equals(injectedSystemUiPid);
                    if (restarted || !st.injectorAlive) {
                        Log.w(TAG, "[realtemp] watchdog re-injetando (restart=" + restarted + ")");
                        FridaUtils.startAndInject();
                        injectedSystemUiPid = FridaUtils.systemUiPid();
                        changed = true;
                    }
                } else {
                    boolean restarted = !st.pid.isEmpty() && !st.pid.equals(injectedMediaCenterPid);
                    // Injetor vivo no processo errado nao e saude: em 2026-09-02 o card
                    // sumiu de vez porque o injetor foi para o com.beantechs.mediacenter
                    // .h5.core e ninguem percebeu. Reinjetar so faz sentido se o pid
                    // mudou desde a tentativa furada — no mesmo pid daria o mesmo erro
                    // a cada tick, para sempre.
                    boolean wrongPidIsNew = st.wrongTarget && !st.pid.equals(homeCardWrongPid);
                    // A troca de pid E o reinicio da Home. Registra em disco com o Δt desde
                    // o nosso ultimo attach e colhe a causa da morte (crash x kill x boot),
                    // que e o que decide se fomos nos. So quando ja tinhamos um pid: a
                    // primeira aparicao no boot (pid vazio -> pid) nao e reinicio.
                    if (restarted && !injectedMediaCenterPid.isEmpty()) {
                        long dt = lastMediaCenterInjectMs == 0 ? -1
                                : SystemClock.elapsedRealtime() - lastMediaCenterInjectMs;
                        PersistentLog.w(TAG, "[homecard] MediaCenter reiniciou: pid "
                                + injectedMediaCenterPid + " -> " + st.pid
                                + " (uptime " + (SystemClock.elapsedRealtime() / 1000)
                                + "s, Δt desde nosso attach = "
                                + (dt < 0 ? "n/a" : dt + "ms") + ")");
                        captureMediaCenterDeath(injectedMediaCenterPid, st.pid);
                    }
                    boolean wantInject = restarted || !st.injectorAlive || wrongPidIsNew;
                    if (wantInject && shouldDeferMediaCenterAttach(st.pid)) {
                        // Processo ainda novo: adia. O next-calc abaixo ja volta ao ritmo
                        // rapido porque homeCardInjected fica false.
                        homeCardInjected = false;
                    } else if (wantInject) {
                        PersistentLog.w(TAG, "[homecard] watchdog re-injetando (restart=" + restarted
                                + " injetorVivo=" + st.injectorAlive
                                + " alvoErrado=" + st.wrongTarget + ")");
                        lastMediaCenterInjectMs = SystemClock.elapsedRealtime();
                        FridaUtils.startHomeCard();
                        injectedMediaCenterPid = FridaUtils.mediaCenterPid();
                        // Sem uma segunda ida ao shell so para confirmar: se a injecao
                        // nao pegou, o proximo tick (em 1s) descobre e tenta de novo.
                        homeCardInjected = !injectedMediaCenterPid.isEmpty();
                        homeCardWrongPid = "";
                        changed = true;
                    } else if (st.wrongTarget) {
                        // Ja tentamos neste pid e o processo nao tem as classes. Fica no
                        // ritmo lento e no log como NAO injetado, que e a verdade.
                        if (!st.pid.equals(homeCardWrongPid)) {
                            homeCardWrongPid = st.pid;
                            PersistentLog.w(TAG, "[homecard] injecao caiu em processo sem as"
                                    + " classes do card (pid " + st.pid + ") — ver ALVO ERRADO em "
                                    + FridaUtils.TARGET_MEDIA_CENTER.logPath());
                        }
                        homeCardInjected = false;
                    } else {
                        homeCardInjected = true;
                        homeCardWrongPid = "";
                    }
                }
            }

            next = changed ? WATCHDOG_MIN_MS
                           : Math.min(WATCHDOG_MAX_MS, watchdogIntervalMs * 2);
            // MediaCenter ainda nao subiu: volta ao ritmo rapido do boot.
            if (homeCardEnabled && !homeCardInjected) next = HOME_CARD_RETRY_MS;

        } catch (Exception e) {
            Log.e(TAG, "[watchdog] erro: " + e.getMessage(), e);
            next = WATCHDOG_MIN_MS;
        } finally {
            rearmInjectionWatchdog(next);
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
        backgroundHandler.removeCallbacks(injectionWatchdogRunnable);
        ClimateStateHolder.INSTANCE.setOnRealOutsideTempToggle(null);
        ClimateStateHolder.INSTANCE.setOnClimateStripToggle(null);
        // Remove so a janela; o overscan fica. onDestroy vem quase sempre de restart(), e
        // a instancia nova reaplica em ~1s — limpar aqui faria todo app se rediagramar
        // duas vezes. Se o servico nao voltar, a limpeza do proximo boot resolve.
        if (climateStrip != null) climateStrip.hide();
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
            ClimateStateHolder.INSTANCE.clearVehicleData();
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
        mainHandler.post(ClimateStateHolder.INSTANCE::clearVehicleData);
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
