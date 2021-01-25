// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.container.logging;

import com.yahoo.compress.ZstdCompressor;
import com.yahoo.container.logging.LogFileHandler.Compression;
import com.yahoo.io.IOUtils;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.function.BiFunction;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.zip.GZIPInputStream;

import static com.yahoo.yolean.Exceptions.uncheck;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Bob Travis
 * @author bjorncs
 */
public class LogFileHandlerTestCase {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void testIt() throws IOException {
        File root = temporaryFolder.newFolder("logfilehandlertest");

        String pattern = root.getAbsolutePath() + "/logfilehandlertest.%Y%m%d%H%M%S";
        long[] rTimes = {1000, 2000, 10000};
        Formatter formatter = new Formatter() {
            public String format(LogRecord r) {
                DateFormat df = new SimpleDateFormat("yyyy.MM.dd:HH:mm:ss.SSS");
                String timeStamp = df.format(new Date(r.getMillis()));
                return ("["+timeStamp+"]" + " " + formatMessage(r) + "\n");
            }
        };
        LogFileHandler<String> h = new LogFileHandler<>(Compression.NONE, pattern, rTimes, null, new StringLogWriter());
        long now = System.currentTimeMillis();
        long millisPerDay = 60*60*24*1000;
        long tomorrowDays = (now / millisPerDay) +1;
        long tomorrowMillis = tomorrowDays * millisPerDay;
        assertThat(tomorrowMillis+1000).isEqualTo(h.getNextRotationTime(tomorrowMillis));
        assertThat(tomorrowMillis+10000).isEqualTo(h.getNextRotationTime(tomorrowMillis+3000));
        String message = "test";
        h.publish(message);
        h.publish( "another test");
        h.rotateNow();
        h.publish(message);
        h.flush();
        h.shutdown();
    }

    @Test
    public void testSimpleLogging() throws IOException {
        File logFile = temporaryFolder.newFile("testLogFileG1.txt");

      //create logfilehandler
      LogFileHandler<String> h = new LogFileHandler<>(Compression.NONE, logFile.getAbsolutePath(), "0 5 ...", null, new StringLogWriter());

      //write log
      h.publish("testDeleteFileFirst1");
      h.flush();
      h.shutdown();
    }

    @Test
    public void testDeleteFileDuringLogging() throws IOException {
      File logFile = temporaryFolder.newFile("testLogFileG2.txt");

      //create logfilehandler
       LogFileHandler<String> h = new LogFileHandler<>(Compression.NONE, logFile.getAbsolutePath(), "0 5 ...", null, new StringLogWriter());

      //write log
      h.publish("testDeleteFileDuringLogging1");
      h.flush();

      //delete log file
        logFile.delete();

      //write log again
      h.publish("testDeleteFileDuringLogging2");
      h.flush();
      h.shutdown();
    }

    @Test(timeout = /*5 minutes*/300_000)
    public void testSymlink() throws IOException, InterruptedException {
        File root = temporaryFolder.newFolder("testlogforsymlinkchecking");
        Formatter formatter = new Formatter() {
            public String format(LogRecord r) {
                DateFormat df = new SimpleDateFormat("yyyy.MM.dd:HH:mm:ss.SSS");
                String timeStamp = df.format(new Date(r.getMillis()));
                return ("[" + timeStamp + "]" + " " + formatMessage(r));
            }
        };
        LogFileHandler<String> handler = new LogFileHandler<>(
                Compression.NONE, root.getAbsolutePath() + "/logfilehandlertest.%Y%m%d%H%M%S%s", new long[]{0}, "symlink", new StringLogWriter());

        String message = formatter.format(new LogRecord(Level.INFO, "test"));
        handler.publish(message);
        String firstFile;
        do {
             Thread.sleep(1);
             firstFile = handler.getFileName();
        } while (firstFile == null);
        handler.rotateNow();
        String secondFileName;
        do {
            Thread.sleep(1);
            secondFileName = handler.getFileName();
        } while (firstFile.equals(secondFileName));

        String longMessage = formatter.format(new LogRecord(Level.INFO, "string which is way longer than the word test"));
        handler.publish(longMessage);
        handler.flush();
        assertThat(Files.size(Paths.get(firstFile))).isEqualTo(31);
        final long expectedSecondFileLength = 72;
        long secondFileLength;
        do {
            Thread.sleep(1);
            secondFileLength = Files.size(Paths.get(secondFileName));
        } while (secondFileLength != expectedSecondFileLength);

        long symlinkFileLength = Files.size(root.toPath().resolve("symlink"));
        assertThat(symlinkFileLength).isEqualTo(expectedSecondFileLength);
        handler.shutdown();
    }

    @Test
    public void testcompression_gzip() throws InterruptedException, IOException {
        testcompression(
                Compression.GZIP, "gz",
                (compressedFile, __) -> uncheck(() -> new String(new GZIPInputStream(Files.newInputStream(compressedFile)).readAllBytes())));
    }

    @Test
    public void testcompression_zstd() throws InterruptedException, IOException {
        testcompression(
                Compression.ZSTD, "zst",
                (compressedFile, uncompressedSize) -> uncheck(() -> {
                    ZstdCompressor zstdCompressor = new ZstdCompressor();
                    byte[] uncompressedBytes = new byte[uncompressedSize];
                    byte[] compressedBytes = Files.readAllBytes(compressedFile);
                    zstdCompressor.decompress(compressedBytes, 0, compressedBytes.length, uncompressedBytes, 0, uncompressedBytes.length);
                    return new String(uncompressedBytes);
                }));
    }

    private void testcompression(Compression compression,
                                 String fileExtension,
                                 BiFunction<Path, Integer, String> decompressor) throws IOException, InterruptedException {
        File root = temporaryFolder.newFolder("testcompression" + compression.name());

        LogFileHandler<String> h = new LogFileHandler<>(
                compression, root.getAbsolutePath() + "/logfilehandlertest.%Y%m%d%H%M%S%s", new long[]{0}, null, new StringLogWriter());
        int logEntries = 10000;
        for (int i = 0; i < logEntries; i++) {
            h.publish("test");
        }
        h.flush();
        String f1 = h.getFileName();
        assertThat(f1).startsWith(root.getAbsolutePath() + "/logfilehandlertest.");
        File uncompressed = new File(f1);
        File compressed = new File(f1 + "." + fileExtension);
        assertThat(uncompressed).exists();
        assertThat(compressed).doesNotExist();
        String content = IOUtils.readFile(uncompressed);
        assertThat(content).hasLineCount(logEntries);
        h.rotateNow();
        while (uncompressed.exists()) {
            Thread.sleep(1);
        }
        assertThat(compressed).exists();
        String uncompressedContent = decompressor.apply(compressed.toPath(), content.getBytes().length);
        assertThat(uncompressedContent).isEqualTo(content);
        h.shutdown();
    }

    static class StringLogWriter implements LogWriter<String> {

        @Override
        public void write(String record, OutputStream outputStream) throws IOException {
            outputStream.write(record.getBytes(StandardCharsets.UTF_8));
        }
    }
}
