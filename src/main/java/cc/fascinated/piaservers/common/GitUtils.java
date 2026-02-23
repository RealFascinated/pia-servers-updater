package cc.fascinated.piaservers.common;

import lombok.SneakyThrows;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class GitUtils {

    /**
     * Commit files to git
     *
     * @param message The commit message
     * @param files The files to commit
     */
    public static void commitFiles(String message, Path... files) {
        System.out.println("Committing files");
        if (Config.isProduction()) {
            runCommand("git", "config", "--global", "user.email", "fascinated-helper@fascinated.cc");
            runCommand("git", "config", "--global", "user.name", "Fascinated's Helper");
            for (Path file : files) {
                runCommand("git", "add", file.toAbsolutePath().toString());
            }
            runCommand("git", "commit", "-m", message);
            runCommand("git", "push", "https://realfascinated:%s@github.com/RealFascinated/PIA-Servers.git".formatted(System.getenv("AUTH_TOKEN")));
        }
    }

    /**
     * Clone the repository
     */
    @SneakyThrows
    public static void cloneRepo() {
        if (Config.isProduction()) {
            System.out.println("Cloning repository");
            runCommandWithOutput("git", "clone", "--depth", "1", "https://github.com/RealFascinated/PIA-Servers.git");
            runCommand("mv", "PIA-Servers/.git", ".");
            Path cloneServersJson = Path.of("PIA-Servers", "servers.json");
            if (Files.exists(cloneServersJson)) {
                Files.copy(cloneServersJson, Path.of("servers.json"), StandardCopyOption.REPLACE_EXISTING);
            }
            runCommand("rm", "-rf", "PIA-Servers");
            runCommand("git", "pull"); // Pull the latest changes
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
}
