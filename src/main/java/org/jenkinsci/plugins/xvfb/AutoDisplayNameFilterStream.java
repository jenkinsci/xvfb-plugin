/**
 * Copyright © 2012, Zoran Regvart
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * The views and conclusions contained in the software and documentation are those
 * of the authors and should not be interpreted as representing official policies,
 * either expressed or implied, of the FreeBSD Project.
 */
package org.jenkinsci.plugins.xvfb;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class AutoDisplayNameFilterStream extends FilterOutputStream {

    private final Semaphore received = new Semaphore(1);

    private final char[] lastLine = new char[1024];

    private int idx;

    private int displayNumber;

    private final long waitTime;

    protected AutoDisplayNameFilterStream(final OutputStream decorated) {
        this(decorated, 10);
    }

    protected AutoDisplayNameFilterStream(final OutputStream decorated, final int waitTime) {
        super(decorated);

        this.waitTime = waitTime;

        try {
            received.acquire();
        } catch (final InterruptedException e) {
            throw new IllegalStateException("Unable to acquire semaphore upon creation", e);
        }

    }

    @Override
    public void close() throws IOException {
        received.release();
        super.close();
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
}
