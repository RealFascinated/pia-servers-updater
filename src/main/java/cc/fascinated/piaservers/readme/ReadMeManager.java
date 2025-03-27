package cc.fascinated.piaservers.readme;

import cc.fascinated.piaservers.Main;
import cc.fascinated.piaservers.model.PiaServer;
import cc.fascinated.piaservers.pia.PiaManager;
import cc.fascinated.piaservers.utils.Strings;
import lombok.SneakyThrows;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class ReadMeManager {
    private final DecimalFormat decimalFormat = new DecimalFormat("#,###");
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private final SimpleDateFormat badgeDateFormat = new SimpleDateFormat("yyyy-MM-dd_HH:mm:ss");

    @SneakyThrows
    public ReadMeManager() {
        InputStream readmeStream = Main.class.getResourceAsStream("/README.md");
        if (readmeStream == null) {
            System.out.println("Failed to find README.md");
            return;
        }
        File readmeFile = new File("README.md");
        if (!readmeFile.exists()) { // Create the file if it doesn't exist
            readmeFile.createNewFile();
        }
        // Get the contents of the README.md
        String contents = new String(readmeStream.readAllBytes());

        Map<String, Integer> regionCounts = new HashMap<>();
        for (PiaServer server : PiaManager.SERVERS) {
            String region = server.getRegion();
            regionCounts.put(region, regionCounts.getOrDefault(region, 0) + 1);
        }

        Date now = new Date();
        // Replace the placeholders in the README.md file
        contents = contents.replace("{server_count}", decimalFormat.format(PiaManager.SERVERS.size()));
        contents = contents.replace("{last_update}", dateFormat.format(now));
        contents = contents.replace("{last_update_badge}", badgeDateFormat.format(now));
        contents = contents.replace("{region_count}", decimalFormat.format(regionCounts.size()));

        // Write total servers per-region with improved formatting
        contents = contents.replace("{server_table}", regionCounts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue())) // Sort from highest to lowest
                .map(entry -> String.format("| %-30s | %-12s |", 
                    Strings.formatRegion(entry.getKey()), 
                    decimalFormat.format(entry.getValue())))
                .reduce((a, b) -> a + "\n" + b).orElse("")); // Reduce the entries to a single string

        Files.write(readmeFile.toPath(), contents.getBytes());
    }
}
