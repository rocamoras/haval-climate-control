package br.com.redesurftank.havalclimatecontrol.utils;

import android.util.Log;

import org.apache.commons.net.telnet.TelnetClient;

import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import br.com.redesurftank.havalclimatecontrol.App;

/**
 * Shell root pelo busybox-telnetd da central (127.0.0.1:23), para o que nao pode esperar
 * o binder do Shizuku. No boot o telnet responde em 2,4s de uptime e o binder so chega
 * uns 4-5s depois de o shizuku_server subir (medido em 2026-11-01).
 *
 * O script vai num arquivo do proprio app, e nao na linha digitada: o terminal do
 * telnetd quebra a linha em 80 colunas e devolve o eco embaralhado. Os marcadores da
 * saida vem partidos com "" na linha digitada — o eco traz {@code @@HAVAL_R""C=}, e so a
 * saida de verdade traz {@code @@HAVAL_RC=}. Como no {@link TelnetClientWrapper}, so
 * funciona com uid <= 10999 (o firewall por uid barra o loopback:23 acima disso).
 */
public final class TelnetShell {

    private static final String TAG = "TelnetShell";

    private static final String BEGIN = "@@HAVAL_BEGIN@@";
    private static final Pattern RC   = Pattern.compile("@@HAVAL_RC=(\\d+)@@");

    private TelnetShell() {}

    /** Roda o script como root; stderr sai misturado no stdout. */
    public static ShizukuUtils.ShellResult run(String script, long timeoutMs) {
        File file = null;
        TelnetClient tc = new TelnetClient();
        try {
            file = File.createTempFile("tsh", ".sh", App.getDeviceProtectedContext().getCacheDir());
            try (FileWriter w = new FileWriter(file)) {
                w.write(script);
            }
            //noinspection ResultOfMethodCallIgnored
            file.setReadable(true, false);

            tc.setConnectTimeout(1000);
            tc.connect("127.0.0.1", 23);
            tc.setSoTimeout(250);
            OutputStream out = tc.getOutputStream();
            out.write(("echo @@HAVAL_BEG\"\"IN@@; sh " + file.getAbsolutePath()
                    + " 2>&1; echo @@HAVAL_R\"\"C=$?@@; exit\r\n").getBytes());
            out.flush();

            InputStream in = tc.getInputStream();
            StringBuilder buf = new StringBuilder();
            byte[] chunk = new byte[4096];
            long deadline = System.currentTimeMillis() + timeoutMs;
            Matcher m = null;
            while (System.currentTimeMillis() < deadline) {
                int n;
                try {
                    n = in.read(chunk);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                if (n < 0) break;
                buf.append(new String(chunk, 0, n));
                m = RC.matcher(buf);
                if (m.find()) break;
                m = null;
            }

            String text = buf.toString().replace("\r", "");
            if (m == null) {
                return new ShizukuUtils.ShellResult(ShizukuUtils.ShellResult.NOT_RUN, "", "",
                        "telnet sem resposta em " + timeoutMs + "ms");
            }
            int begin = text.indexOf(BEGIN);
            int end   = text.indexOf("@@HAVAL_RC=");
            String stdout = begin < 0 || end < begin ? ""
                    : text.substring(begin + BEGIN.length(), end).trim();
            int exit = Integer.parseInt(m.group(1));
            return new ShizukuUtils.ShellResult(exit, stdout, exit == 0 ? "" : stdout, null);
        } catch (Exception e) {
            Log.w(TAG, "telnet falhou: " + e);
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new ShizukuUtils.ShellResult(ShizukuUtils.ShellResult.NOT_RUN, "", "", "telnet: " + msg);
        } finally {
            try { if (tc.isConnected()) tc.disconnect(); } catch (Exception ignored) {}
            //noinspection ResultOfMethodCallIgnored
            if (file != null) file.delete();
        }
    }
}
