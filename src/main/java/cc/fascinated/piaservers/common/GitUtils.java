package cc.fascinated.piaservers.common;

import lombok.SneakyThrows;

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
            runCommand("git", "push", "https://fascinated-helper:%s@git.fascinated.cc/Fascinated/PIA-Servers".formatted(System.getenv("AUTH_TOKEN")));
        }
    }

    /**
     * Clone the repository
     */
    @SneakyThrows
    public static void cloneRepo() {
        if (Config.isProduction()) {
            System.out.println("Cloning repository");
            runCommand("git", "clone", "https://git.fascinated.cc/Fascinated/PIA-Servers.git");
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
}
