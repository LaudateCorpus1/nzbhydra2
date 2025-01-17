package org.nzbhydra.debuginfos;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.apache.commons.io.IOUtils;
import org.javers.core.JaversBuilder;
import org.javers.core.diff.Diff;
import org.nzbhydra.Jackson;
import org.nzbhydra.NzbHydra;
import org.nzbhydra.config.BaseConfig;
import org.nzbhydra.config.ConfigProvider;
import org.nzbhydra.config.ConfigReaderWriter;
import org.nzbhydra.config.category.CategoriesConfig;
import org.nzbhydra.config.category.Category;
import org.nzbhydra.logging.LogAnonymizer;
import org.nzbhydra.logging.LogContentProvider;
import org.nzbhydra.logging.LoggingMarkers;
import org.nzbhydra.problemdetection.OutdatedWrapperDetector;
import org.nzbhydra.update.UpdateManager;
import org.nzbhydra.webaccess.HydraOkHttp3ClientHttpRequestFactory;
import org.nzbhydra.webaccess.Ssl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.management.ThreadDumpEndpoint;
import org.springframework.boot.actuate.metrics.MetricsEndpoint;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.DecimalFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Component
public class DebugInfosProvider {

    private static final Logger logger = LoggerFactory.getLogger(DebugInfosProvider.class);
    private static final int LOG_METRICS_EVERY_SECONDS = 5;

    @Autowired
    private LogAnonymizer logAnonymizer;
    @Autowired
    private ConfigProvider configProvider;
    @Autowired
    private UpdateManager updateManager;
    @Autowired
    private LogContentProvider logContentProvider;
    @Autowired
    private HydraOkHttp3ClientHttpRequestFactory requestFactory;
    @Autowired
    private OutdatedWrapperDetector outdatedWrapperDetector;
    @Autowired
    private MetricsEndpoint metricsEndpoint;
    @Autowired
    private ThreadDumpEndpoint threadDumpEndpoint;
    @PersistenceContext
    private EntityManager entityManager;
    @Autowired
    private OutdatedWrapperDetector wrapperDetector;
    @Autowired
    private Ssl ssl;

    @Value("spring.datasource.url")
    private String datasourceUrl;

    private final List<TimeAndThreadCpuUsages> timeAndThreadCpuUsagesList = new ArrayList<>();
    private final Map<String, Long> lastThreadCpuTimes = new HashMap<>();

    @PostConstruct
    public void logMetrics() {
        if (!configProvider.getBaseConfig().getMain().getLogging().getMarkersToLog().contains(LoggingMarkers.PERFORMANCE.getName())) {
            return;
        }
        logger.debug(LoggingMarkers.PERFORMANCE, "Will log performance metrics every {} seconds", LOG_METRICS_EVERY_SECONDS);
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
        executor.scheduleAtFixedRate(() -> {
            try {
                final String cpuMetric = "process.cpu.usage";
                String message = "Process CPU usage: " + formatSample(cpuMetric, metricsEndpoint.metric(cpuMetric, null).getMeasurements().get(0).getValue());
                logger.debug(LoggingMarkers.PERFORMANCE, message);
            } catch (Exception e) {
                logger.debug(LoggingMarkers.PERFORMANCE, "Error while logging CPU usage", e);
            }
            try {
                final String memoryMetric = "jvm.memory.used";
                String message = "Process memory usage: " + formatSample(memoryMetric, metricsEndpoint.metric(memoryMetric, null).getMeasurements().get(0).getValue());
                logger.debug(LoggingMarkers.PERFORMANCE, message);
            } catch (Exception e) {
                logger.debug(LoggingMarkers.PERFORMANCE, "Error while logging memory usage", e);
            }
            ThreadMXBean threadMxBean = ManagementFactory.getThreadMXBean();
            final ThreadInfo[] threadInfos = threadMxBean.dumpAllThreads(true, true);

        }, 0, LOG_METRICS_EVERY_SECONDS, TimeUnit.SECONDS);

        int cpuCount = metricsEndpoint.metric("system.cpu.count", null).getMeasurements().get(0).getValue().intValue();
        ThreadMXBean threadMxBean = ManagementFactory.getThreadMXBean();
        final double[] previousUptime = {getUpTimeInMiliseconds()};
        ScheduledExecutorService executor2 = Executors.newScheduledThreadPool(1);

        executor2.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                final double upTime = getUpTimeInMiliseconds();
                double elapsedTime = upTime - previousUptime[0];

                final ThreadInfo[] threadInfos = threadMxBean.dumpAllThreads(true, true);
                TimeAndThreadCpuUsages timeAndThreadCpuUsages = new TimeAndThreadCpuUsages(Instant.now());
                for (ThreadInfo threadInfo : threadInfos) {
                    final String threadName = threadInfo.getThreadName();
                    final long threadCpuTime = threadMxBean.getThreadCpuTime(threadInfo.getThreadId());
                    if (!lastThreadCpuTimes.containsKey(threadName)) {
                        lastThreadCpuTimes.put(threadName, threadCpuTime);
                        continue;
                    }
                    final Long lastThreadCpuTime = lastThreadCpuTimes.get(threadName);
                    long elapsedThreadCpuTime = threadCpuTime - lastThreadCpuTime;
                    if (elapsedThreadCpuTime < 0) {
                        //Not sure why but this happens with some threads
                        continue;
                    }
                    float cpuUsage = Math.min(99F, elapsedThreadCpuTime / (float) (elapsedTime * 1000 * cpuCount));
                    if (cpuUsage < 0) {
                        cpuUsage = 0;
                    }
                    if (cpuUsage > 5F) {
                        logger.debug(LoggingMarkers.PERFORMANCE, "CPU usage of thread {}: {}", threadName, cpuUsage);
                    }
                    timeAndThreadCpuUsages.getThreadCpuUsages().add(new ThreadCpuUsage(threadName, (long) cpuUsage));

                    lastThreadCpuTimes.put(threadName, threadCpuTime);
                }
                timeAndThreadCpuUsagesList.add(timeAndThreadCpuUsages);
                previousUptime[0] = upTime;
                if (timeAndThreadCpuUsagesList.size() == 50) {
                    timeAndThreadCpuUsagesList.remove(0);
                }
            }

        }, 0, LOG_METRICS_EVERY_SECONDS, TimeUnit.SECONDS);

    }

    public List<TimeAndThreadCpuUsages> getThreadCpuUsageChartData() {
        return timeAndThreadCpuUsagesList;
    }

    private double getUpTimeInMiliseconds() {
        return metricsEndpoint.metric("process.uptime", null).getMeasurements().get(0).getValue() * 1000;
    }

    public byte[] getDebugInfosAsZip() throws IOException {
        File tempFile = createDebugInfosZipFile();
        return Files.readAllBytes(tempFile.toPath());
    }

    public File createDebugInfosZipFile() throws IOException {
        logger.info("Creating debug infos");
        logger.info("NZBHydra2 version: {}", updateManager.getCurrentVersionString());
        logger.info("Java command line: {}", System.getProperty("sun.java.command"));
        logger.info("Java runtime name: {}", System.getProperty("java.runtime.name"));
        logger.info("Java runtime version: {}", System.getProperty("java.runtime.version"));
        logger.info("OS name: {}", System.getProperty("os.name"));
        logger.info("OS architecture: {}", System.getProperty("os.arch"));
        logger.info("User country: {}", System.getProperty("user.country"));
        logger.info("File encoding: {}", System.getProperty("file.encoding"));
        logger.info("Datasource URL: {}", datasourceUrl);
        logger.info("Ciphers:");
        logger.info(ssl.getSupportedCiphers());
        outdatedWrapperDetector.executeCheck();
        logNumberOfTableRows("SEARCH");
        logNumberOfTableRows("SEARCHRESULT");
        logNumberOfTableRows("INDEXERSEARCH");
        logNumberOfTableRows("INDEXERAPIACCESS");
        logNumberOfTableRows("INDEXERAPIACCESS_SHORT");
        logNumberOfTableRows("INDEXERNZBDOWNLOAD");
        logDatabaseFolderSize();
        if (isRunInDocker()) {
            logger.info("Apparently run in docker");
            logger.info("Container info: {}", updateManager.getPackageInfo());
        } else {
            logger.info("Apparently not run in docker");
        }

        String anonymizedConfig = getAnonymizedConfig();
        logConfigChanges(anonymizedConfig);

        logger.info("Metrics:");
        final Set<String> metricsNames = metricsEndpoint.listNames().getNames();
        for (String metric : metricsNames) {
            final MetricsEndpoint.MetricResponse response = metricsEndpoint.metric(metric, null);
            logger.info(metric + ": " + response.getMeasurements().stream()
                    .map(x -> x.getStatistic().name() + ": " + formatSample(metric, x.getValue()))
                    .collect(Collectors.joining(", ")));
        }


        String anonymizedLog = logAnonymizer.getAnonymizedLog(logContentProvider.getLog());
        File tempFile = File.createTempFile("nzbhydradebuginfos", "zip");
        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            try (ZipOutputStream zos = new ZipOutputStream(fos)) {
                writeStringToZip(zos, "nzbhydra2.log", anonymizedLog.getBytes(StandardCharsets.UTF_8));
                writeStringToZip(zos, "nzbhydra2-config.yaml", anonymizedConfig.getBytes(StandardCharsets.UTF_8));
                writeFileIfExists(zos, new File(NzbHydra.getDataFolder(), "database"), "nzbhydra.trace.db");
                File logsFolder = new File(NzbHydra.getDataFolder(), "logs");
                //Write all GC logs
                File[] files = logsFolder.listFiles((dir, name) -> name.startsWith("gclog"));
                if (files != null) {
                    for (File file : files) {
                        writeFileToZip(zos, file.getName(), file);
                    }
                }
                writeFileIfExists(zos, logsFolder, "wrapper.log");
                writeFileIfExists(zos, logsFolder, "system.err.log");
                writeFileIfExists(zos, logsFolder, "system.out.log");

                File servLogFile = new File(logsFolder, "nzbhydra2.serv.log");
                if (servLogFile.exists()) {
                    writeStringToZip(zos, "nzbhydra2.serv.log", logAnonymizer.getAnonymizedLog(IOUtils.toString(new FileReader(servLogFile))).getBytes());
                }
            }
        }
        logger.debug("Finished creating debug infos ZIP");
        return tempFile;
    }

    private void logConfigChanges(String anonymizedConfig) throws IOException {
        final ConfigReaderWriter configReaderWriter = new ConfigReaderWriter();

        final BaseConfig originalConfig = configReaderWriter.originalConfig();
        originalConfig.setCategoriesConfig(new DiffableCategoriesConfig(originalConfig.getCategoriesConfig()));

        final BaseConfig userConfig = Jackson.YAML_MAPPER.readValue(anonymizedConfig, BaseConfig.class);
        userConfig.setCategoriesConfig(new DiffableCategoriesConfig(userConfig.getCategoriesConfig()));

        final Diff configDiff = JaversBuilder.javers()
                .build()
                .compare(originalConfig, userConfig);
        logger.info("Difference in config:\n{}", configDiff.prettyPrint());
    }

    public void logThreadDump() {
        logger.debug(threadDumpEndpoint.textThreadDump());
    }

    private String formatSample(String name, Double value) {
        String suffix = "";
        if (value == 0) {
            return "0";
        }
        if (name.contains("memory")) {
            value = value / (1024 * 1024);
            suffix = "MB";
        }
        String pattern;
        if (value % 1 == 0) {
            pattern = "#,###";
        } else {
            pattern = "#,###.00";
        }
        if (name.contains("cpu")) {
            value = 100 * value;
            suffix = "%";
            pattern = "##";
        }

        return new DecimalFormat(pattern).format(value) + suffix;
    }

    private void writeFileIfExists(ZipOutputStream zos, File logsFolder, String filename) throws IOException {
        File file = new File(logsFolder, filename);
        if (file.exists()) {
            writeFileToZip(zos, filename, file);
        }
    }

    protected void logDatabaseFolderSize() {
        File databaseFolder = new File(NzbHydra.getDataFolder(), "database");
        if (!databaseFolder.exists()) {
            logger.warn("Database folder not found");
            return;
        }
        File[] databaseFiles = databaseFolder.listFiles();
        if (databaseFiles == null) {
            logger.warn("No database files found");
            return;
        }
        long databaseFolderSize = Stream.of(databaseFiles).mapToLong(File::length).sum();
        logger.info("Size of database folder: {}MB", databaseFolderSize / (1024 * 1024));
    }

    protected void logNumberOfTableRows(final String tableName) {
        try {
            logger.info("Number of rows in table " + tableName + ": " + entityManager.createNativeQuery("select count(*) from " + tableName).getSingleResult());
        } catch (Exception e) {
            logger.error("Unable to get number of rows in table " + tableName, e);
        }
    }

    public static boolean isRunInDocker() {
        return new File("/.dockerenv").exists();
    }

    @Transactional
    public String executeSqlQuery(String sql) throws IOException {
        logger.info("Executing SQL query \"{}\" and returning as CSV", sql);
        File tempFile = File.createTempFile("nzbhydra", "csv");
        String path = tempFile.getAbsolutePath().replace("\\", "/");
        entityManager.createNativeQuery(String.format("CALL CSVWRITE('%s', '%s')", path, sql.replace("'", "''"))).executeUpdate();
        return new String(Files.readAllBytes(tempFile.toPath()));
    }

    @Transactional
    public String executeSqlUpdate(String sql) {
        logger.info("Executing SQL query \"{}\"", sql);

        int affectedRows = entityManager.createNativeQuery(sql).executeUpdate();
        return String.valueOf(affectedRows);
    }

    private void writeStringToZip(ZipOutputStream zos, String name, byte[] bytes) throws IOException {
        ZipEntry zipEntry = new ZipEntry(name);
        zipEntry.setSize(bytes.length);
        zos.putNextEntry(zipEntry);
        zos.write(bytes);
        zos.closeEntry();
    }

    private void writeFileToZip(ZipOutputStream zos, String name, File file) throws IOException {
        byte[] bytes = Files.readAllBytes(file.toPath());
        writeStringToZip(zos, name, bytes);
    }


    private String getAnonymizedConfig() throws JsonProcessingException {
        return Jackson.SENSITIVE_YAML_MAPPER.writeValueAsString(configProvider.getBaseConfig());
    }

    @Data
    public static class TimeAndThreadCpuUsages {
        private final Instant time;
        private final List<ThreadCpuUsage> threadCpuUsages = new ArrayList<>();

        public TimeAndThreadCpuUsages(Instant time) {
            this.time = time;
        }
    }

    @Data
    @AllArgsConstructor
    public static class ThreadCpuUsage {
        private final String threadName;
        private final long cpuUsage;
    }

    @Data
    public static class DiffableCategoriesConfig extends CategoriesConfig {
        private Map<String, Category> categoriesMap = new HashMap<>();

        public DiffableCategoriesConfig(CategoriesConfig categoriesConfig) {
            categoriesConfig.getCategories().forEach(x -> {
                categoriesMap.put(x.getName(), x);
            });
        }


    }


}
