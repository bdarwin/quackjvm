package io.quackjvm.agent.file;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/** A text file's lines as rows. */
public class LineSourceTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static List<String> read(LineSource source, long first, int n) {
        String[] into = new String[n];
        source.read(first, n, into);
        return Arrays.asList(into);
    }

    @Test
    public void wholeLinesOnlyUntilTheLastOneEnds() throws Exception {
        Path file = folder.newFile("a.log").toPath();
        Files.writeString(file, "one\ntwo\r\nthr");
        LineSource source = LineSource.of(file);
        assertEquals(2, source.rowCount());
        assertEquals(List.of("one", "two"), read(source, 0, 2));
        Files.writeString(file, "ee\nfour\n", StandardOpenOption.APPEND);
        assertEquals(4, source.rowCount());
        assertEquals(List.of("three", "four"), read(source, 2, 2));
    }

    @Test
    public void aCompleteFilesLastLineAndAHeaderSkipped() throws Exception {
        Path file = folder.newFile("a.csv").toPath();
        Files.writeString(file, "h1,h2\n1,2\n3,4");
        LineSource source = LineSource.of(file).skip(1).lastLineEnds(true);
        assertEquals(2, source.rowCount());
        assertEquals(List.of("1,2", "3,4"), read(source, 0, 2));
    }

    @Test
    public void aReplacedFileStartsAgainWithANewIdentity() throws Exception {
        Path file = folder.newFile("a.log").toPath();
        Files.writeString(file, "a\nb\nc\n");
        LineSource source = LineSource.of(file);
        assertEquals(3, source.rowCount());
        Object before = source.identity();
        Path rotated = folder.getRoot().toPath().resolve("new.log");
        Files.writeString(rotated, "x\n");
        Files.move(rotated, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertEquals(1, source.rowCount());
        assertNotEquals(before, source.identity());
        assertEquals(List.of("x"), read(source, 0, 1));
    }

    @Test
    public void aTruncatedFileStartsAgain() throws Exception {
        Path file = folder.newFile("a.log").toPath();
        Files.writeString(file, "a\nb\nc\n");
        LineSource source = LineSource.of(file);
        assertEquals(3, source.rowCount());
        Object before = source.identity();
        Files.writeString(file, "z\n");
        assertEquals(1, source.rowCount());
        assertNotEquals(before, source.identity());
        assertEquals(List.of("z"), read(source, 0, 1));
    }

    @Test
    public void manyLinesAcrossTheScanBuffer() throws Exception {
        Path file = folder.newFile("big.log").toPath();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 200_000; i++) {
            text.append("line ").append(i).append(" é\n");
        }
        Files.writeString(file, text);
        LineSource source = LineSource.of(file);
        assertEquals(200_000, source.rowCount());
        assertEquals(List.of("line 131071 é", "line 131072 é"), read(source, 131_071, 2));
        assertEquals(List.of("line 199999 é"), read(source, 199_999, 1));
    }
}
