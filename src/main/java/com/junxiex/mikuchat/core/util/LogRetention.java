package com.junxiex.mikuchat.core.util;

import java.io.File;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.logging.Logger;

/**
 * 日志文件保留策略：按文件名中的日期删除过期日志，避免无限增长。
 */
public final class LogRetention {

    private LogRetention() {
    }

    /**
     * @param directory     日志目录
     * @param retentionDays 保留天数，&lt;=0 表示永久保留
     * @param dateFormat    文件名日期格式（需能解析为 {@link LocalDate}）
     */
    public static void purge(Path directory, int retentionDays, DateTimeFormatter dateFormat, Logger logger, String label) {
        if (retentionDays <= 0) {
            return;
        }
        File[] files = directory.toFile().listFiles((dir, name) -> name.endsWith(".log"));
        if (files == null || files.length == 0) {
            return;
        }
        LocalDate cutoff = LocalDate.now().minusDays(retentionDays);
        int removed = 0;
        for (File file : files) {
            String name = file.getName();
            String base = name.substring(0, name.length() - 4);
            try {
                if (LocalDate.parse(base, dateFormat).isBefore(cutoff) && file.delete()) {
                    removed++;
                }
            } catch (Exception ignored) {
                // 文件名不符合日期格式，跳过
            }
        }
        if (removed > 0) {
            logger.info("已清理 " + removed + " 个过期" + label + "（保留 " + retentionDays + " 天）。");
        }
    }
}
