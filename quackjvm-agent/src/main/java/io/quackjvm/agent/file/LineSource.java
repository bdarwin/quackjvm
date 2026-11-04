package io.quackjvm.agent.file;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;

/**
 * The lines of a text file as rows, for a {@link FileTable}: a log, or a CSV after its header.
 *
 * <p>It keeps where each line starts, found once by scanning the file and then only for what was
 * added since. A line counts once it ends with {@code \n}, so a line still being written waits;
 * {@link #lastLineEnds(boolean)} counts an unterminated last line too, for a file that is complete.
 * {@code \r\n} endings lose the {@code \r}. Reads are positional, so several threads read at once.
 * When the file shrinks or is a different file - rotated - the index starts again.</p>
 */
public final class LineSource implements RowSource {

    private final Path file;
    private final Charset charset;
    private final int skip;
    private final boolean lastLineEnds;
    /** Where each line starts; starts[count] is where the next would. */
    private long[] starts = new long[1024];
    private int lines;
    private long scanned;
    private Object fileKey;
    private Object identity = new Object();

    private LineSource(Path file, Charset charset, int skip, boolean lastLineEnds) {
        this.file = file;
        this.charset = charset;
        this.skip = skip;
        this.lastLineEnds = lastLineEnds;
    }

    /** A file's lines, read as UTF-8. */
    public static LineSource of(Path file) {
        return new LineSource(file, StandardCharsets.UTF_8, 0, false);
    }

    public LineSource charset(Charset charset) {
        return new LineSource(file, charset, skip, lastLineEnds);
    }

    /** Leaves out the first lines - a CSV's header. */
    public LineSource skip(int lines) {
        if (lines < 0) {
            throw new IllegalArgumentException("Cannot skip " + lines + " lines");
        }
        return new LineSource(file, charset, lines, lastLineEnds);
    }

    /** Counts a last line without a {@code \n} as whole, for a file nothing is writing to. */
    public LineSource lastLineEnds(boolean ends) {
        return new LineSource(file, charset, skip, ends);
    }

    public Path getFile() {
        return file;
    }

    @Override
    public synchronized long rowCount() {
        try {
            refresh();
            long whole = lines;
            if (lastLineEnds && trailing() > 0) {
                whole++;
            }
            return Math.max(0, whole - skip);
        }
        catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + file, e);
        }
    }

    @Override
    public void read(long first, int n, String[] into) {
        long[] index;
        int known;
        synchronized (this) {
            index = starts;
            known = lines;
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long from = first + skip;
            if (from + n > known + (lastLineEnds ? 1 : 0)) {
                throw new IllegalArgumentException("Rows " + first + " to " + (first + n - 1) + " are not all there yet");
            }
            long start = index[(int) from];
            // The last line, if it has no \n yet, runs to the end of the file.
            long stop = from + n <= known ? index[(int) (from + n)] : channel.size();
            ByteBuffer bytes = ByteBuffer.allocate((int) (stop - start));
            while (bytes.hasRemaining() && channel.read(bytes, start + bytes.position()) >= 0) {
                // until full
            }
            byte[] array = bytes.array();
            for (int i = 0; i < n; i++) {
                long lineStart = index[(int) (from + i)] - start;
                long lineEnd = from + i + 1 <= known ? index[(int) (from + i + 1)] - start : array.length;
                int length = (int) (lineEnd - lineStart);
                if (length > 0 && array[(int) lineStart + length - 1] == '\n') {
                    length--;
                }
                if (length > 0 && array[(int) lineStart + length - 1] == '\r') {
                    length--;
                }
                into[i] = new String(array, (int) lineStart, length, charset);
            }
        }
        catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + file, e);
        }
    }

    @Override
    public synchronized Object identity() {
        return identity;
    }

    private long trailing() throws IOException {
        return Files.size(file) - scanned;
    }

    /** Finds the lines added since the last look; starts again for a file that shrank or was replaced. */
    private void refresh() throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
        Object key = attributes.fileKey() != null ? attributes.fileKey() : attributes.creationTime();
        long size = attributes.size();
        if (fileKey != null && (!fileKey.equals(key) || size < scanned)) {
            starts = new long[1024];
            lines = 0;
            scanned = 0;
            identity = new Object();
        }
        fileKey = key;
        if (size == scanned) {
            return;
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.allocate(1 << 20);
            long position = scanned;
            long lastLineEnd = scanned;
            while (position < size) {
                buffer.clear();
                int read = channel.read(buffer, position);
                if (read <= 0) {
                    break;
                }
                byte[] array = buffer.array();
                for (int i = 0; i < read; i++) {
                    if (array[i] == '\n') {
                        lastLineEnd = position + i + 1;
                        if (lines + 1 >= starts.length) {
                            starts = Arrays.copyOf(starts, starts.length * 2);
                        }
                        lines++;
                        starts[lines] = lastLineEnd;
                    }
                }
                position += read;
            }
            // Only up to the last whole line: the rest is scanned again next time, once it ends.
            scanned = lastLineEnd;
        }
    }

    @Override
    public String toString() {
        return "LineSource[" + file + "]";
    }
}
