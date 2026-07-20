package me.pewa.util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class Logger {
    private static final String LOG_DIR  = "C:\\pewa\\logs";
    private static final String LOG_FILE = "C:\\pewa\\logs\\pewa.log";
    private static final String KA_LOG   = "C:\\pewa\\logs\\killaura.log";

    private static final DateTimeFormatter formatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    // UTF-8 stdout — JVM başlarken bir kez set edilir
    private static final PrintStream OUT;

    static {
        new File(LOG_DIR).mkdirs();
        PrintStream utf8Out;
        try {
            utf8Out = new PrintStream(System.out, true, "UTF-8");
        } catch (Exception e) {
            utf8Out = System.out;
        }
        OUT = utf8Out;
        System.setOut(OUT);
    }

    // ── genel loglar ──────────────────────────────────────────────────────────

    public static void info(String message)  { log(LOG_FILE, "[INFO] ", message); }
    public static void warn(String message)  { log(LOG_FILE, "[WARN] ", message); }
    public static void error(String message) { log(LOG_FILE, "[ERROR]", message); }
    public static void debug(String message) { log(LOG_FILE, "[DEBUG]", message); }

    // ── KillAura'ya özel log ──────────────────────────────────────────────────

    public static void kaInfo(String message)   { log(KA_LOG, "[KA-INFO] ", message); }
    public static void kaDebug(String message)  { log(KA_LOG, "[KA-DEBUG]", message); }
    public static void kaWarn(String message)   { log(KA_LOG, "[KA-WARN] ", message); }
    public static void kaAttack(String message) { log(KA_LOG, "[KA-ATTACK]", message); }

    // ── iç yardımcı ──────────────────────────────────────────────────────────

    private static void log(String file, String level, String message) {
        String timestamp = LocalDateTime.now().format(formatter);
        String line = timestamp + " " + level + " " + message;

        OUT.println(line);

        try (Writer writer = new OutputStreamWriter(
                new FileOutputStream(file, true), StandardCharsets.UTF_8)) {
            writer.write(line + "\n");
        } catch (IOException e) {
            e.printStackTrace(OUT);
        }
    }
}