package cc.fascinated.piaservers.common;

import lombok.SneakyThrows;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

public class GitUtils {

    /** Only one git operation at a time to avoid index.lock conflicts (e.g. cron commit vs clone/pull). */
    private static final ReentrantLock GIT_LOCK = new ReentrantLock();

    /** Remove .git/index.lock if it exists and is older than this (stale from crashed process). */
    private static final long LOCK_STALE_MS = TimeUnit.MINUTES.toMillis(2);

    /**
     * Commit files to git only if there are changes. Skips commit/push when nothing is staged.
     *
     * @param message The commit message
     * @param files The files to commit
     */
    public static void commitFiles(String message, Path... files) {
        if (!Config.isProduction()) {
            return;
        }
        if (files == null || files.length == 0) {
            System.err.println("commitFiles: no files to commit");
            return;
        }
        Path repoRoot = files[0].toAbsolutePath().getParent();
        if (repoRoot == null || !Files.isDirectory(repoRoot.resolve(".git"))) {
            System.err.println("commitFiles: repo root not found or no .git: " + repoRoot);
            return;
        }
        String authToken = System.getenv("AUTH_TOKEN");
        if (authToken == null || authToken.isBlank()) {
            System.err.println("commitFiles: AUTH_TOKEN not set, skipping push");
            return;
        }
        GIT_LOCK.lock();
        try {
            removeStaleIndexLock(repoRoot);
            if (runCommandWithExitCode(repoRoot, "git", "config", "--global", "user.email", "fascinated-helper@fascinated.cc") != 0) {
                System.err.println("commitFiles: git config user.email failed");
                return;
            }
            if (runCommandWithExitCode(repoRoot, "git", "config", "--global", "user.name", "Fascinated's Helper") != 0) {
                System.err.println("commitFiles: git config user.name failed");
                return;
            }
            for (Path file : files) {
                if (runCommandWithExitCode(repoRoot, "git", "add", file.toAbsolutePath().toString()) != 0) {
                    System.err.println("commitFiles: git add failed for " + file);
                    return;
                }
            }
            // Only commit and push if there are staged changes (diff --staged --quiet exits 0 when no changes)
            if (runCommandWithExitCode(repoRoot, "git", "diff", "--staged", "--quiet") != 0) {
                System.out.println("Committing files");
                if (runCommandWithExitCode(repoRoot, "git", "commit", "-m", message) != 0) {
                    System.err.println("commitFiles: git commit failed");
                    return;
                }
                String pushUrl = "https://realfascinated:%s@github.com/RealFascinated/PIA-Servers.git".formatted(authToken);
                if (runCommandWithExitCode(repoRoot, "git", "push", pushUrl) != 0) {
                    System.err.println("commitFiles: git push failed (check AUTH_TOKEN and network)");
                } else {
                    System.out.println("Pushed to GitHub");
                }
            } else {
                System.out.println("No staged changes, skipping commit/push");
            }
        } finally {
            GIT_LOCK.unlock();
        }
    }

    /**
     * Clone the repository. If .git already exists (e.g. from a previous run), only copies servers.json
     * from the fresh clone and runs git pull instead of moving .git (avoids "Directory not empty").
     */
    @SneakyThrows
    public static void cloneRepo() {
        if (!Config.isProduction()) {
            return;
        }
        Path cwd = Path.of(".").toAbsolutePath().normalize();
        GIT_LOCK.lock();
        try {
            removeStaleIndexLock(cwd);
            System.out.println("Cloning repository");
            runCommandWithOutput(cwd, "git", "clone", "--depth", "1", "https://github.com/RealFascinated/PIA-Servers.git");
            Path dotGit = cwd.resolve(".git");
            Path cloneDir = cwd.resolve("PIA-Servers");
            Path cloneServersJson = cloneDir.resolve("servers.json");

            if (Files.exists(dotGit)) {
                // Already have a repo (e.g. restarted container); just refresh from clone and pull
                if (Files.exists(cloneServersJson)) {
                    Files.copy(cloneServersJson, cwd.resolve("servers.json"), StandardCopyOption.REPLACE_EXISTING);
                }
                deleteRecursively(cloneDir);
                runCommand(cwd, "git", "pull");
            } else {
                // First run: move clone's .git here and take servers.json
                runCommand(cwd, "mv", "PIA-Servers/.git", ".");
                if (Files.exists(cloneServersJson)) {
                    Files.copy(cloneServersJson, cwd.resolve("servers.json"), StandardCopyOption.REPLACE_EXISTING);
                }
                deleteRecursively(cloneDir);
                runCommand(cwd, "git", "pull");
            }
        } finally {
            GIT_LOCK.unlock();
        }
    }

    /** Remove .git/index.lock if it exists and is older than LOCK_STALE_MS (e.g. from a crashed run). */
    private static void removeStaleIndexLock(Path repoRoot) {
        Path lockFile = repoRoot.resolve(".git").resolve("index.lock");
        if (!Files.exists(lockFile)) {
            return;
        }
        try {
            long ageMs = System.currentTimeMillis() - Files.getLastModifiedTime(lockFile).toMillis();
            if (ageMs >= LOCK_STALE_MS) {
                Files.delete(lockFile);
                System.out.println("Removed stale .git/index.lock from previous run");
            }
        } catch (Exception e) {
            // ignore; git will report the lock error
        }
    }

    @SneakyThrows
    private static void deleteRecursively(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.comparingInt((Path p) -> p.getNameCount()).reversed()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    /**
     * Run a system command in the given working directory.
     */
    private static void runCommand(Path workingDir, String... args) {
        ProcessBuilder processBuilder = new ProcessBuilder(args);
        processBuilder.directory(workingDir.toFile());
        processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);
        try {
            Process process = processBuilder.start();
            process.waitFor();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Run a command in the given working directory and consume stdout/stderr so output appears in order.
     */
    private static void runCommandWithOutput(Path workingDir, String... args) {
        ProcessBuilder processBuilder = new ProcessBuilder(args);
        processBuilder.directory(workingDir.toFile());
        processBuilder.redirectErrorStream(true);
        try {
            Process process = processBuilder.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                }
            }
            process.waitFor();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    /** Run a command in the given working directory and return the process exit code. */
    private static int runCommandWithExitCode(Path workingDir, String... args) {
        ProcessBuilder processBuilder = new ProcessBuilder(args);
        processBuilder.directory(workingDir.toFile());
        processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);
        try {
            Process process = processBuilder.start();
            return process.waitFor();
        } catch (Exception ex) {
            ex.printStackTrace();
            return -1;
        }
    }
}
