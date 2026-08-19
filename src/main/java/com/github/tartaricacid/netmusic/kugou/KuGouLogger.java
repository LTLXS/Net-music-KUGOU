package com.github.tartaricacid.netmusic.kugou;

import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 模组独立日志系统。
 * <p>
 * 日志文件存储在 {@code config/NETMUSICCANNEEDKUGOU/} 目录下，
 * 最多保留 6 个日志文件，按时间排序，超出自动删除最旧的。
 * <p>
 * 同时保留 SLF4J Logger 输出到游戏主日志（latest.log），
 * 独立日志文件提供更清晰的模组专属日志视图。
 */
public final class KuGouLogger {
    private static final String MOD_ID = "netmusic_kugou";
    private static final String LOG_DIR_NAME = "NETMUSICCANNEEDKUGOU";
    private static final int MAX_LOG_FILES = 6;
    private static final DateTimeFormatter FILE_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final DateTimeFormatter LINE_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** SLF4J Logger，同时输出到游戏主日志 */
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** 日志目录：config/NETMUSICCANNEEDKUGOU/ */
    public static final Path LOG_DIR = FMLPaths.CONFIGDIR.get().resolve(LOG_DIR_NAME);

    /** 当前日志文件路径 */
    private static Path currentLogFile;

    /** 日志缓冲区（异步写入） */
    private static final ConcurrentLinkedQueue<String> logQueue = new ConcurrentLinkedQueue<>();

    /** 异步写入调度器 */
    private static final ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "KuGouLogger-Writer");
        t.setDaemon(true);
        return t;
    });

    private KuGouLogger() {}

    /**
     * 初始化日志系统。在模组构造时调用一次。
     * <ul>
     *   <li>创建日志目录</li>
     *   <li>清理超出上限的旧日志</li>
     *   <li>创建当前会话的日志文件</li>
     *   <li>启动异步写入调度器</li>
     * </ul>
     */
    public static void init() {
        try {
            Files.createDirectories(LOG_DIR);
            cleanOldLogs();
            String timestamp = LocalDateTime.now().format(FILE_DATE_FORMAT);
            currentLogFile = LOG_DIR.resolve("kugou_" + timestamp + ".log");
            Files.writeString(currentLogFile, "", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            // 每 2 秒刷新一次缓冲区到文件
            writer.scheduleAtFixedRate(KuGouLogger::flush, 2, 2, TimeUnit.SECONDS);
            LOGGER.info("[KuGouLogger] Log system initialized. Log dir: {}", LOG_DIR);
        } catch (IOException e) {
            LOGGER.error("[KuGouLogger] Failed to init log system", e);
        }
    }

    /**
     * 关闭日志系统。在模组停止时调用。
     * 刷新剩余日志并关闭调度器。
     */
    public static void shutdown() {
        flush();
        writer.shutdown();
        try {
            if (!writer.awaitTermination(3, TimeUnit.SECONDS)) {
                writer.shutdownNow();
            }
        } catch (InterruptedException e) {
            writer.shutdownNow();
            Thread.currentThread().interrupt();
        }
        // 最后再刷一次确保所有日志都写入
        flush();
    }

    // ===== 日志方法 =====

    public static void info(String msg) {
        LOGGER.info(msg);
        enqueue("INFO", msg);
    }

    public static void info(String format, Object... args) {
        LOGGER.info(format, args);
        enqueue("INFO", formatMessage(format, args));
    }

    public static void warn(String msg) {
        LOGGER.warn(msg);
        enqueue("WARN", msg);
    }

    public static void warn(String format, Object... args) {
        LOGGER.warn(format, args);
        enqueue("WARN", formatMessage(format, args));
    }

    public static void error(String msg) {
        LOGGER.error(msg);
        enqueue("ERROR", msg);
    }

    public static void error(String format, Object... args) {
        LOGGER.error(format, args);
        enqueue("ERROR", formatMessage(format, args));
    }

    public static void error(String msg, Throwable t) {
        LOGGER.error(msg, t);
        enqueue("ERROR", msg + " | " + t.getClass().getSimpleName() + ": " + t.getMessage());
    }

    public static void debug(String msg) {
        LOGGER.debug(msg);
        enqueue("DEBUG", msg);
    }

    public static void debug(String format, Object... args) {
        LOGGER.debug(format, args);
        enqueue("DEBUG", formatMessage(format, args));
    }

    // ===== 内部方法 =====

    private static void enqueue(String level, String msg) {
        if (currentLogFile == null) return;
        String timestamp = LocalDateTime.now().format(LINE_DATE_FORMAT);
        String threadName = Thread.currentThread().getName();
        logQueue.add(String.format("[%s] [%s] [%s] %s%n", timestamp, level, threadName, msg));
    }

    /**
     * 将缓冲区中的日志刷新到文件。
     */
    private static void flush() {
        if (currentLogFile == null || logQueue.isEmpty()) return;
        try {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = logQueue.poll()) != null) {
                sb.append(line);
            }
            if (sb.length() > 0) {
                Files.writeString(currentLogFile, sb.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            LOGGER.error("[KuGouLogger] Failed to flush log", e);
        }
    }

    /**
     * 清理旧日志文件，只保留最新的 MAX_LOG_FILES 个。
     */
    private static void cleanOldLogs() {
        try {
            List<Path> logFiles = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(LOG_DIR, "kugou_*.log")) {
                for (Path p : stream) {
                    logFiles.add(p);
                }
            }
            if (logFiles.size() <= MAX_LOG_FILES) return;

            // 按修改时间排序，最旧的在前
            logFiles.sort(Comparator.comparingLong(p -> {
                try {
                    return Files.getLastModifiedTime(p).toMillis();
                } catch (IOException e) {
                    return 0L;
                }
            }));

            int toDelete = logFiles.size() - MAX_LOG_FILES;
            for (int i = 0; i < toDelete; i++) {
                Files.deleteIfExists(logFiles.get(i));
                LOGGER.info("[KuGouLogger] Deleted old log: {}", logFiles.get(i).getFileName());
            }
        } catch (IOException e) {
            LOGGER.error("[KuGouLogger] Failed to clean old logs", e);
        }
    }

    /**
     * 简单的格式化方法，支持 {} 占位符。
     */
    private static String formatMessage(String format, Object... args) {
        if (args == null || args.length == 0) return format;
        String result = format;
        for (Object arg : args) {
            int idx = result.indexOf("{}");
            if (idx < 0) break;
            String replacement = (arg instanceof Throwable)
                    ? arg.getClass().getSimpleName() + ": " + ((Throwable) arg).getMessage()
                    : String.valueOf(arg);
            result = result.substring(0, idx) + replacement + result.substring(idx + 2);
        }
        return result;
    }
}
