package com.junxiex.mikuchat.core.util;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 数据文件的安全写入工具。
 * <p>
 * {@link YamlConfiguration#save(File)} 直接以截断方式打开目标文件：若进程在写入过程中退出
 * （服务器被 kill、磁盘写满等），目标文件会停留在半截内容上，下次读取即解析失败。
 * 这里改为「先写同目录临时文件，再原子替换」，保证任何时刻目标文件要么是旧的完整内容、
 * 要么是新的完整内容。
 */
public final class AtomicFiles {

    private AtomicFiles() {
    }

    /**
     * 原子地写入 YAML 配置。
     *
     * @throws Exception 写入或替换失败时抛出，交由调用方记录日志
     */
    public static void saveYaml(YamlConfiguration yaml, File target) throws Exception {
        Path path = target.toPath().toAbsolutePath();
        Path parent = path.getParent();
        Files.createDirectories(parent);
        // 临时文件必须与目标同目录，跨文件系统的 move 无法保证原子性
        Path temp = Files.createTempFile(parent, prefixOf(path.getFileName().toString()), ".tmp");
        try {
            yaml.save(temp.toFile());
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                // 少数文件系统不支持原子 move，退化为普通替换（仍优于直接截断写入）
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.FileSystemException e) {
                // Windows 上目标文件被其它句柄短暂占用时，ATOMIC_MOVE 会抛 FileSystemException
                // 而不是 AtomicMoveNotSupportedException；此时退化为普通替换，避免整次写入失败。
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** {@code Files.createTempFile} 要求前缀至少 3 个字符，文件名过短时补齐。 */
    private static String prefixOf(String fileName) {
        return fileName.length() >= 3 ? fileName : (fileName + "___").substring(0, 3);
    }
}
