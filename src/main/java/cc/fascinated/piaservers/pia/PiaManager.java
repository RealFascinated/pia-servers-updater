package cc.fascinated.piaservers.pia;

import cc.fascinated.piaservers.Main;
import cc.fascinated.piaservers.common.Config;
import cc.fascinated.piaservers.common.GitUtils;
import cc.fascinated.piaservers.model.PiaServer;
import cc.fascinated.piaservers.readme.ReadMeManager;
import com.google.gson.reflect.TypeToken;
import lombok.SneakyThrows;
import org.codehaus.plexus.archiver.zip.ZipUnArchiver;
import org.xbill.DNS.Record;
import org.xbill.DNS.*;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;

public class PiaManager {
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
    private static final String PIA_OPENVPN_CONFIGS_URL = "https://www.privateinternetaccess.com/openvpn/openvpn.zip";
    private static final long REMOVAL_THRESHOLD = TimeUnit.DAYS.toMillis(14); // 2 weeks
    public static Set<PiaServer> SERVERS = new HashSet<>();
    private static Path README_PATH;

    /** Used by CommitJob to access current paths. */
    static volatile Path commitReadmePath;
    static volatile Path commitServersPath;

    @SneakyThrows
    public PiaManager() {
        GitUtils.cloneRepo(); // Clone the repository (brings servers.json from repo if present)

        File serversFile = new File("servers.json");
        if (!serversFile.exists()) {
            System.out.println("The servers file doesn't exist, creating it...");
            serversFile.createNewFile();
        }
        // Load the serversFile from the file
        SERVERS = Main.GSON.fromJson(Files.readString(serversFile.toPath()), new TypeToken<Set<PiaServer>>() {}.getType());
        if (SERVERS == null) {
            SERVERS = new HashSet<>();
        }
        System.out.printf("Loaded %s servers from the file%n", SERVERS.size());

        // Set the DNS resolver to Cloudflare
        Lookup.setDefaultResolver(new SimpleResolver("1.1.1.1"));

        commitServersPath = serversFile.toPath();

        // Update the servers every 5 minutes
        new Timer().scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                updateServers(serversFile); // Update the servers
                README_PATH = ReadMeManager.updateReadme(); // Update the README.md
                commitReadmePath = README_PATH;
            }
        }, 0, TimeUnit.MINUTES.toMillis(5));

        // Commit on cron schedule (e.g. every hour via Config.COMMIT_CRON)
        try {
            Scheduler scheduler = StdSchedulerFactory.getDefaultScheduler();
            scheduler.start();
            JobDetail job = JobBuilder.newJob(CommitJob.class).withIdentity("commit-job").build();
            CronTrigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity("commit-trigger")
                    .withSchedule(CronScheduleBuilder.cronSchedule(Config.COMMIT_CRON))
                    .build();
            scheduler.scheduleJob(job, trigger);
        } catch (SchedulerException e) {
            throw new RuntimeException("Failed to schedule commit job", e);
        }
    }

    /** Quartz job that runs git commit on the cron schedule. */
    public static class CommitJob implements Job {
        @Override
        public void execute(JobExecutionContext context) {
            Path readme = commitReadmePath;
            if (readme != null && commitServersPath != null) {
                GitUtils.commitFiles("Scheduled update", commitServersPath, readme);
            }
        }
    }

    @SneakyThrows
    public static void updateServers(File serversFile) {
        List<PiaServer> servers = getPiaServers();

        // Remove the servers that haven't been active in 2 weeks
        int before = SERVERS.size();
        SERVERS.removeIf(server -> System.currentTimeMillis() - server.getLastSeen().getTime() > REMOVAL_THRESHOLD);
        System.out.printf("Removed %s servers that haven't been active in 2 weeks%n", before - SERVERS.size());

        // Add the new servers to the list
        int newServers = 0;
        for (PiaServer piaServer : servers) {
            boolean newServer = SERVERS.stream().noneMatch(server -> server.getIp().equals(piaServer.getIp()));
            if (newServer) {
                newServers++;
            }

            // Add the server to the list
            SERVERS.add(piaServer);
        }

        // Save the servers to the file
        Files.writeString(serversFile.toPath(), Main.GSON.toJson(SERVERS));
        System.out.printf("Wrote %s servers to the file (+%s new)%n", SERVERS.size(), newServers);
    }

    private static final int MAX_HTTP_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 2_000;

    @SneakyThrows
    private static List<PiaServer> getPiaServers() {
        long start = System.currentTimeMillis();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(PIA_OPENVPN_CONFIGS_URL))
                .GET()
                .build();
        HttpResponse<Path> response = null;
        IOException lastException = null;
        for (int attempt = 1; attempt <= MAX_HTTP_RETRIES; attempt++) {
            try {
                response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofFile(Files.createTempFile("openvpn", ".zip")));
                if (response.statusCode() == 200) {
                    break;
                }
                lastException = new IOException("Failed to get the PIA OpenVPN configs, status code: " + response.statusCode());
            } catch (IOException e) {
                lastException = e;
                if (attempt < MAX_HTTP_RETRIES) {
                    System.err.printf("Attempt %d/%d failed (%s), retrying in %dms...%n", attempt, MAX_HTTP_RETRIES, e.getMessage(), RETRY_DELAY_MS * attempt);
                    Thread.sleep(RETRY_DELAY_MS * attempt);
                } else {
                    throw e;
                }
            }
        }
        if (response == null || response.statusCode() != 200) {
            throw lastException != null ? lastException : new IOException("Failed to get the PIA OpenVPN configs");
        }
        System.out.printf("Downloaded the OpenVPN configs in %sms%n", System.currentTimeMillis() - start);
        Path downloadedFile = response.body();
        File tempDir = Files.createTempDirectory("openvpn").toFile();
        ZipUnArchiver unArchiver = new ZipUnArchiver();
        unArchiver.setSourceFile(downloadedFile.toFile());
        unArchiver.setDestDirectory(tempDir);
        unArchiver.extract();

        File[] files = tempDir.listFiles();
        if (files == null || files.length == 0) {
            throw new IOException("Failed to extract the OpenVPN configs");
        }

        List<PiaServer> servers = new ArrayList<>();
        for (File file : files) {
            if (file.isDirectory() || !file.getName().endsWith(".ovpn")) {
                continue;
            }
            List<String> lines = Files.readAllLines(file.toPath());
            for (String line : lines) {
                if (line.startsWith("remote ")) {
                    String[] parts = line.split(" ");
                    String hostname = parts[1];
                    String region = file.getName().split("\\.")[0];
                    Record[] records = new Lookup(hostname, Type.A).run();
                    if (records != null) {
                        for (Record record : records) {
                            ARecord aRecord = (ARecord) record;
                            servers.add(new PiaServer(aRecord.getAddress().getHostAddress(), region, new Date()));
                        }
                    }
                    break;
                }
            }
        }
        return servers;
    }
}
