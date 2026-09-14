package dev.everyagent.worker;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 测试临时目录注册与批量递归删除。
 * 各测试类在 {@code @DynamicPropertySource} 或静态块中通过 {@link #register(Path)} 登记目录,
 * 在 {@code @AfterAll} 中调用 {@link #deleteAll()} 统一清理。
 */
public final class TestCleanup {

    private static final List<Path> DIRS = new CopyOnWriteArrayList<>();

    private TestCleanup() {
    }

    /** 登记待清理目录,返回原路径便于链式赋值。 */
    public static Path register(Path dir) {
        DIRS.add(dir);
        return dir;
    }

    /** 递归删除所有已登记目录,忽略不存在的目录与删除失败(文件句柄占用等)。 */
    public static void deleteAll() {
        for (Path dir : DIRS) {
            deleteRecursively(dir);
        }
        DIRS.clear();
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    Files.delete(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // 清理失败不阻塞测试退出
        }
    }
}
