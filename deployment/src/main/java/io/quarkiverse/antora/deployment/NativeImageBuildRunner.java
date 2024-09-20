package io.quarkiverse.antora.deployment;

import static io.quarkiverse.antora.deployment.LinuxIDUtil.getLinuxID;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Assertions;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.deployment.util.ContainerRuntimeUtil;
import io.quarkus.deployment.util.FileUtil;

public class NativeImageBuildRunner {

    private static final Logger log = Logger.getLogger(NativeImageBuildRunner.class);

    private final ContainerRuntimeUtil.ContainerRuntime containerRuntime;

    private final String containerName;

    public NativeImageBuildRunner() {
        containerRuntime = ContainerRuntimeUtil.detectContainerRuntime();
        containerName = "antora-" + RandomStringUtils.random(5, true, false);
    }

    public void build(String antoraImageName, Path outputDir, Path antoraPlaybookPath)
            throws InterruptedException, IOException {

        final List<String> cmd = new ArrayList<>();
        cmd.add(containerRuntime.getExecutableName());
        cmd.add("run");

        cmd.add("--rm");

        if (SystemUtils.IS_OS_LINUX || SystemUtils.IS_OS_MAC) {
            if (containerRuntime.isInWindowsWSL()) {
                cmd.add("--interactive");
            }
            if (containerRuntime.isDocker() && containerRuntime.isRootless()) {
                Collections.addAll(cmd, "--user", String.valueOf(0));
            } else {
                String uid = getLinuxID("-ur");
                String gid = getLinuxID("-gr");
                if (uid != null && gid != null && !uid.isEmpty() && !gid.isEmpty()) {
                    Collections.addAll(cmd, "--user", uid + ":" + gid);
                    if (containerRuntime.isPodman() && containerRuntime.isRootless()) {
                        // Needed to avoid AccessDeniedExceptions
                        cmd.add("--userns=keep-id");
                    }
                }
            }
        }

        String volumeOutputPath = outputDir.toAbsolutePath().toString();
        if (SystemUtils.IS_OS_WINDOWS) {
            volumeOutputPath = FileUtil.translateToVolumePath(volumeOutputPath);
        }

        final String selinuxBindOption;
        if (SystemUtils.IS_OS_MAC && containerRuntime.isPodman()) {
            selinuxBindOption = "";
        } else {
            selinuxBindOption = ":z";
        }
        cmd.add("-v");
        cmd.add(volumeOutputPath + ":/antora" + selinuxBindOption);

        cmd.add("--name");
        cmd.add(containerName);

        cmd.add(antoraImageName);

        cmd.add("--cache-dir=./antora-cache");
        cmd.add(antoraPlaybookPath.toString());

        final String[] buildCommand = cmd.toArray(new String[0]);

        log.infof("Running Antora with %s:", containerRuntime.getExecutableName());
        log.info(String.join(" ", buildCommand).replace("$", "\\$"));
        final Process process = new ProcessBuilder(buildCommand)
                .directory(outputDir.toFile())
                .redirectErrorStream(true)
                .start();
        addShutdownHook(process);

        final OutputSlurper output = new OutputSlurper(containerName, process.getInputStream(), System.out);
        final int exitCode = process.waitFor();

        output.assertNoErrors(1000);

        if (exitCode != 0) {
            throw new IllegalStateException("Antora exited with " + exitCode);
        }
    }

    void addShutdownHook(Process process) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (process.isAlive()) {
                try {
                    Process removeProcess = new ProcessBuilder(
                            List.of(containerRuntime.getExecutableName(), "rm", "-f", containerName))
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .redirectError(ProcessBuilder.Redirect.DISCARD)
                            .start();
                    removeProcess.waitFor(2, TimeUnit.SECONDS);
                } catch (IOException | InterruptedException e) {
                    log.debug("Unable to stop running container", e);
                }
            }
        }));
    }

    private static final class OutputSlurper {

        private final AntoraFrameConsumer frameConsumer = new AntoraFrameConsumer();
        private final CountDownLatch finished = new CountDownLatch(1);

        private OutputSlurper(String containerName, final InputStream processStream, final PrintStream consumer) {
            final Thread t = new Thread(() -> {
                try (final BufferedReader reader = new BufferedReader(
                        new InputStreamReader(processStream, StandardCharsets.UTF_8))) {
                    String line = null;
                    while ((line = reader.readLine()) != null) {
                        frameConsumer.accept(line);
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Could not read stdout of container " + containerName, e);
                }
                finished.countDown();
            });
            t.setName(containerName + ":stdout");
            t.start();
        }

        public void assertNoErrors(long timeout) throws InterruptedException {
            finished.await(timeout, TimeUnit.MILLISECONDS);
            frameConsumer.assertNoErrors();
        }
    }

    private static class AntoraFrameConsumer {

        private final ObjectMapper mapper;
        private final List<AntoraFrame> frames = new ArrayList<>();
        private final Map<String, JsonProcessingException> exceptions = new LinkedHashMap<>();

        public AntoraFrameConsumer() {
            mapper = new ObjectMapper();
        }

        public void accept(String rawFrame) {
            if (!rawFrame.startsWith("{")) {
                log.info(rawFrame);
                return;
            }
            try {
                final AntoraFrame frame = mapper.readValue(rawFrame, AntoraFrame.class);
                synchronized (frames) {
                    frames.add(frame);
                }
                switch (frame.getLevel()) {
                    case "info":
                        log.info(frame.toString());
                        break;
                    case "warn":
                        log.warn(frame.toString());
                        break;
                    case "error":
                        log.error(frame.toString());
                        break;
                    case "fatal":
                        log.fatal(frame.toString());
                        break;
                    default:
                        throw new IllegalStateException("Unexpected AntoraFrame.level " + frame.getLevel());
                }
            } catch (JsonProcessingException e) {
                synchronized (exceptions) {
                    exceptions.put(rawFrame, e);
                }
            }
        }

        public void assertNoErrors() {
            synchronized (exceptions) {
                if (!exceptions.isEmpty()) {
                    Entry<String, JsonProcessingException> e = exceptions.entrySet().iterator().next();
                    throw new RuntimeException("Could not parse AntoraFrame " + e.getKey(), e.getValue());
                }
            }

            synchronized (frames) {
                String errors = frames.stream()
                        .filter(f -> f.getLevel().equals("error") || f.getLevel().equals("fatal"))
                        .map(AntoraFrame::toString)
                        .collect(Collectors.joining("\n"));
                if (errors != null && !errors.isEmpty()) {
                    Assertions.fail(errors);
                }
            }
        }

    }

    static class AntoraFrame {
        String level;
        long time;
        String name;
        AntoraFile file;
        AntoraSource source;
        String msg;
        String hint;
        List<AntoraStackFrame> stack;

        public String getLevel() {
            return level;
        }

        public long getTime() {
            return time;
        }

        public String getName() {
            return name;
        }

        public AntoraFile getFile() {
            return file;
        }

        public AntoraSource getSource() {
            return source;
        }

        public String getMsg() {
            return msg;
        }

        public List<AntoraStackFrame> getStack() {
            return stack;
        }

        public String getHint() {
            return hint;
        }

        @Override
        public String toString() {
            return file + ": " + msg
                    + (hint != null ? (" " + hint) : "")
                    + (stack != null && !stack.isEmpty()
                            ? ("\n    at " + stack.stream()
                                    .map(AntoraStackFrame::toString)
                                    .collect(Collectors.joining("\n    at ")))
                            : "");
        }

        static class AntoraSource {
            String url;
            String worktree;
            String refname;
            String startPath;

            public String getUrl() {
                return url;
            }

            public String getWorktree() {
                return worktree;
            }

            public String getRefname() {
                return refname;
            }

            public String getStartPath() {
                return startPath;
            }
        }

        static class AntoraFile {
            String path;
            int line;

            @Override
            public String toString() {
                return path + ":" + line;
            }

            public String getPath() {
                return path;
            }

            public int getLine() {
                return line;
            }
        }

        static class AntoraStackFrame {
            AntoraFile file;
            AntoraSource source;

            public AntoraFile getFile() {
                return file;
            }

            public AntoraSource getSource() {
                return source;
            }

            @Override
            public String toString() {
                return file != null ? file.toString() : "";
            }
        }
    }
}
