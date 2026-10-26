package utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

public class CMDHelper {
    private static final Logger logger = LoggerFactory.getLogger(CMDHelper.class);
    private static final long DEFAULT_TIMEOUT_SECONDS = 120;

    public static BufferedReader execute(String[] cmd) {
        return execute(cmd, DEFAULT_TIMEOUT_SECONDS);
    }

    /**
     * Runs a command and returns its combined stdout/stderr.
     * Output goes to a temporary file instead of a pipe, so a command that prints a lot can never block
     * (the original version waited for the process before reading the pipe), and a hung command is killed after the timeout.
     */
    public static BufferedReader execute(String[] cmd, long timeoutSeconds) {
        File out = null;
        try {
            out = File.createTempFile("adge-cmd", ".log");
            ProcessBuilder processBuilder = new ProcessBuilder(cmd);
            processBuilder.redirectErrorStream(true);
            processBuilder.redirectOutput(out);
            Process process = processBuilder.start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                logger.warn("command timed out after " + timeoutSeconds + " s: " + String.join(" ", cmd));
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            } else {
                logger.debug("Exit Code: " + process.exitValue());
            }
            String text = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);
            return new BufferedReader(new StringReader(text));
        } catch (IOException e) {
            e.printStackTrace();
            return new BufferedReader(new StringReader(""));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new BufferedReader(new StringReader(""));
        } finally {
            if (out != null) {
                out.delete();
            }
        }
    }

    /** Runs a command and returns its combined output as one string. */
    public static String run(String[] cmd, long timeoutSeconds) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = execute(cmd, timeoutSeconds)) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return sb.toString();
    }
}
