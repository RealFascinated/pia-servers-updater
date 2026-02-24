package cc.fascinated.piaservers.common;

import lombok.SneakyThrows;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;

public class GitUtils {

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
        runCommand("git", "config", "--global", "user.email", "fascinated-helper@fascinated.cc");
        runCommand("git", "config", "--global", "user.name", "Fascinated's Helper");
        for (Path file : files) {
            runCommand("git", "add", file.toAbsolutePath().toString());
        }
        // Only commit and push if there are staged changes
        if (runCommandWithExitCode("git", "diff", "--staged", "--quiet") != 0) {
            System.out.println("Committing files");
            runCommand("git", "commit", "-m", message);
            runCommand("git", "push", "https://realfascinated:%s@github.com/RealFascinated/PIA-Servers.git".formatted(System.getenv("AUTH_TOKEN")));
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
        System.out.println("Cloning repository");
        runCommandWithOutput("git", "clone", "--depth", "1", "https://github.com/RealFascinated/PIA-Servers.git");
        Path dotGit = Path.of(".git");
        Path cloneDir = Path.of("PIA-Servers");
        Path cloneServersJson = cloneDir.resolve("servers.json");

        if (Files.exists(dotGit)) {
            // Already have a repo (e.g. restarted container); just refresh from clone and pull
            if (Files.exists(cloneServersJson)) {
                Files.copy(cloneServersJson, Path.of("servers.json"), StandardCopyOption.REPLACE_EXISTING);
            }
            deleteRecursively(cloneDir);
            runCommand("git", "pull");
        } else {
            // First run: move clone's .git here and take servers.json
            runCommand("mv", "PIA-Servers/.git", ".");
            if (Files.exists(cloneServersJson)) {
                Files.copy(cloneServersJson, Path.of("servers.json"), StandardCopyOption.REPLACE_EXISTING);
            }
            deleteRecursively(cloneDir);
            runCommand("git", "pull");
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
     * Run a system command
     *
     * @param args The command to run (with arguments)
     */
    private static void runCommand(String... args) {
        ProcessBuilder processBuilder = new ProcessBuilder(args);
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
     * Run a command and consume stdout/stderr so output appears in order (avoids buffered subprocess output appearing later).
     */
    private static void runCommandWithOutput(String... args) {
        ProcessBuilder processBuilder = new ProcessBuilder(args);
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

    /** Run a command and return the process exit code. */
    private static int runCommandWithExitCode(String... args) {
        ProcessBuilder processBuilder = new ProcessBuilder(args);
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
