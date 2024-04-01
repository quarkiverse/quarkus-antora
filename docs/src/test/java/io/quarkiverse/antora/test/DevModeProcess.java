package io.quarkiverse.antora.test;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class DevModeProcess implements Closeable {
    //private static final Logger log = Logger.getLogger(DevModeProcess.class);
    private Process process;
    private Thread outputSlurper;
    private volatile boolean stopped = false;
    private final CountDownLatch startedLatch = new CountDownLatch(1);

    public DevModeProcess(Path baseDir) throws InterruptedException {
        baseDir = baseDir.toAbsolutePath().normalize();
        final Path multiModuleProjectDir = multiModuleProjectDir(baseDir);

        final List<String> cmd = cmd(multiModuleProjectDir, baseDir);
        System.out.println("Starting quarkus-cxf-test-ws-rm-server: " + cmd.stream().collect(Collectors.joining(" ")));
        try {
            process = new ProcessBuilder()
                    .command(cmd)
                    .redirectErrorStream(true)
                    .start();

            /* Unless we slurp the process output, the server app will eventually freeze on Windows */
            outputSlurper = new Thread(() -> {
                try (BufferedReader in = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while (!stopped && (line = in.readLine()) != null) {
                        System.out.println("server: " + line);
                        if (line.contains("Installed features: [")) {
                            startedLatch.countDown();
                        }
                    }
                } catch (IOException e) {
                    if (!"Stream closed".equals(e.getMessage())) {
                        e.printStackTrace();
                    }
                }
            });
            outputSlurper.start();
        } catch (IOException e) {
            throw new RuntimeException(cmd.stream().collect(Collectors.joining(" ")), e);
        }

        startedLatch.await(15, TimeUnit.SECONDS);
    }

    private Path multiModuleProjectDir(Path baseDir) {
        Path multiModuleProjectDir = baseDir.toAbsolutePath().normalize();
        while (!Files.exists(multiModuleProjectDir.resolve(".mvn"))) {
            multiModuleProjectDir = multiModuleProjectDir.getParent();
            if (multiModuleProjectDir == null) {
                throw new IllegalStateException(
                        "Could not find .mvn repository root for " + baseDir);
            }
        }
        return multiModuleProjectDir;
    }

    private List<String> cmd(Path multiModuleProjectDir, Path baseDir) {
        final Path javaHome = Path.of(System.getProperty("java.home"));
        final List<String> cmd = List.of(
                javaHome.resolve("bin/java" + (System.getProperty("os.name").toLowerCase().contains("win") ? ".exe" : ""))
                        .toString(),
                "-classpath", multiModuleProjectDir.resolve(".mvn/wrapper/maven-wrapper.jar").toString(),
                "-Dmaven.multiModuleProjectDirectory=" + multiModuleProjectDir.toString(),
                "org.apache.maven.wrapper.MavenWrapperMain",
                "quarkus:dev");
        return cmd;
    }

    @Override
    public void close() {
        process.destroy();
        stopped = true;
        if (outputSlurper != null) {
            try {
                outputSlurper.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
