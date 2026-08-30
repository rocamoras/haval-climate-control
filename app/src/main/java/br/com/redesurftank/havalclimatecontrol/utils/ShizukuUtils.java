package br.com.redesurftank.havalclimatecontrol.utils;

import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.atomic.AtomicLong;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

public class ShizukuUtils {

    private static final String TAG = "ShizukuUtils";

    /**
     * Quantos newProcess() ja passamos pelo shizuku_server desde que o processo subiu.
     *
     * Vai para o log de diagnostico: cada um deixa no server um RemoteProcessHolder +
     * um java.lang.Process + 3 pipes, liberados so quando o proxy binder deste lado e
     * coletado. Em 29/08/2026 o server morreu de OutOfMemoryError depois de 5h43 de
     * sessao; a conta (~9.000 forks para 96MB) so fecha se a taxa for medida, entao
     * aqui esta o contador.
     */
    private static final AtomicLong NEW_PROCESS_COUNT = new AtomicLong();

    /** Chamado por quem invoca newProcess() fora daqui (ex.: IPTablesUtils). */
    public static void countNewProcess() {
        NEW_PROCESS_COUNT.incrementAndGet();
    }

    /** Total de newProcess() desde o start do processo. */
    public static long newProcessCount() {
        return NEW_PROCESS_COUNT.get();
    }

    /**
     * Resultado completo de um comando: exit code, stdout, stderr e a exceção que
     * impediu a execução, se houve.
     *
     * Motivo de existir: {@link #runCommandAndGetOutput} devolve "" tanto quando o
     * Shizuku está fora quanto quando o comando existe mas falhou — e foi
     * exatamente essa ambiguidade que deixou a seção de logcat dos uploads de
     * diagnóstico com "(vazio ou Shizuku indisponivel)" sem dizer qual dos dois era.
     */
    public static final class ShellResult {
        /** Exit code do processo; {@link #NOT_RUN} se nem chegou a executar. */
        public static final int NOT_RUN = Integer.MIN_VALUE;

        public final int    exitCode;
        public final String stdout;
        public final String stderr;
        /** Mensagem da exceção quando o processo não pôde ser criado/aguardado. */
        public final String error;

        ShellResult(int exitCode, String stdout, String stderr, String error) {
            this.exitCode = exitCode;
            this.stdout   = stdout;
            this.stderr   = stderr;
            this.error    = error;
        }

        public boolean ok() {
            return error == null && exitCode == 0;
        }

        public boolean ran() {
            return exitCode != NOT_RUN;
        }

        /** Uma linha explicando a falha, pronta pra ir num log de diagnóstico. */
        public String describeFailure() {
            if (ok()) return "ok";
            if (error != null) return "nao executou: " + error;
            String tail = stderr.isEmpty() ? "(stderr vazio)" : stderr.trim();
            return "exit " + exitCode + " — " + tail;
        }
    }

    /** True se o binder do Shizuku está de pé agora. */
    public static boolean isAvailable() {
        try {
            return Shizuku.getBinder() != null && Shizuku.pingBinder();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Roda o comando e devolve stdout, ou "" em qualquer falha.
     * Mantido para os chamadores que só querem a saída; para diagnóstico use
     * {@link #run(String[])}, que preserva exit code e stderr.
     */
    public static String runCommandAndGetOutput(String[] command) {
        return run(command).stdout;
    }

    /** Roda o comando por Shizuku capturando exit code, stdout e stderr. */
    public static ShellResult run(String[] command) {
        if (Shizuku.getBinder() == null) {
            return new ShellResult(ShellResult.NOT_RUN, "", "", "binder do Shizuku indisponivel");
        }

        IShizukuService shizukuService = IShizukuService.Stub.asInterface(Shizuku.getBinder());
        IRemoteProcess process = null;
        try {
            NEW_PROCESS_COUNT.incrementAndGet();
            process = shizukuService.newProcess(command, null, null);
            if (process == null) {
                throw new Exception("newProcess devolveu null");
            }

            // stderr numa thread separada: se o comando escrever mais que o buffer do
            // pipe enquanto ninguém drena, ele bloqueia e o waitFor() abaixo nunca volta.
            final StringBuilder errOut = new StringBuilder();
            Thread errPump = pump(process.getErrorStream(), errOut);

            StringBuilder output = new StringBuilder();
            drain(process.getInputStream(), output);

            if (errPump != null) {
                try { errPump.join(2000); } catch (InterruptedException ignored) {}
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                Log.e(TAG, "Command exited with code " + exitCode + ": " + String.join(" ", command)
                        + (errOut.length() > 0 ? " — " + errOut.toString().trim() : ""));
            }

            return new ShellResult(exitCode, output.toString().trim(), errOut.toString().trim(), null);

        } catch (Exception e) {
            Log.e(TAG, "Error running command: " + String.join(" ", command), e);
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new ShellResult(ShellResult.NOT_RUN, "", "", msg);
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                } catch (Exception e) {
                    // ignore
                }
            }
        }
    }

    private static Thread pump(ParcelFileDescriptor pfd, StringBuilder sink) {
        if (pfd == null) return null;
        Thread t = new Thread(() -> drain(pfd, sink), "shizuku-stderr");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void drain(ParcelFileDescriptor pfd, StringBuilder sink) {
        if (pfd == null) return;
        // AutoCloseInputStream, nao FileInputStream(pfd.getFileDescriptor()): o segundo
        // fecha o fd cru e deixa o ParcelFileDescriptor achando que continua aberto —
        // o CloseGuard reclama e o fd so e devolvido no finalize. Assim o PFD fecha
        // junto com o reader, como o IPTablesUtils ja fazia com closeStreams().
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new ParcelFileDescriptor.AutoCloseInputStream(pfd)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (sink) {
                    sink.append(line).append("\n");
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Falha lendo saida do processo: " + e.getMessage());
        }
    }
}
