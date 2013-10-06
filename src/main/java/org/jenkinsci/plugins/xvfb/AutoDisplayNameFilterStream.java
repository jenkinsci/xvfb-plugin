package org.jenkinsci.plugins.xvfb;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class AutoDisplayNameFilterStream extends FilterOutputStream {

    private final Semaphore received = new Semaphore(1);

    private final char[]    lastLine = new char[1024];

    private int             idx;

    private int             displayNumber;

    private long            waitTime;

    protected AutoDisplayNameFilterStream(final OutputStream decorated) {
        this(decorated, 10);
    }

    protected AutoDisplayNameFilterStream(final OutputStream decorated, int waitTime) {
        super(decorated);

        this.waitTime = waitTime;

        try {
            received.acquire();
        } catch (InterruptedException e) {
            throw new IllegalStateException("Unable to acquire semaphore upon creation", e);
        }

    }

    public int getDisplayNumber() throws InterruptedException {
        if (received.tryAcquire(waitTime, TimeUnit.SECONDS)) {
            return displayNumber;
        }

        throw new IllegalStateException("No display name received from Xvfb within " + waitTime + " seconds");
    }

    @Override
    public void write(final int ch) throws IOException {
        if (ch == '\n' || ch == '\r') {
            if (idx != 0) {
                final String line = new String(lastLine, 0, idx);
                if (line.matches("\\d+")) {
                    displayNumber = Integer.valueOf(line);
                    received.release();
                }
            }

            idx = 0;
        }
        else if (idx < lastLine.length - 1) {
            lastLine[idx++] = (char) ch;
        }

        super.write(ch);
    }

    @Override
    public void close() throws IOException {
        received.release();
        super.close();
    }
}
