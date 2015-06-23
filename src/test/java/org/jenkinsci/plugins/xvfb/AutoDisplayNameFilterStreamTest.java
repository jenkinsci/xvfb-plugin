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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import junit.framework.Assert;

import org.junit.Test;
import org.junit.internal.runners.statements.Fail;

public class AutoDisplayNameFilterStreamTest {

    @Test
    public void shouldParseDisplayNumber() throws InterruptedException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final AutoDisplayNameFilterStream stream = new AutoDisplayNameFilterStream(out);

        final PrintStream printer = new PrintStream(stream);

        printer.println("hello world");
        printer.println("the display number is next");
        printer.println("1234");
        printer.println("that was the display number");

        printer.flush();
        printer.close();

        Assert.assertEquals("display number should be parsed", 1234, stream.getDisplayNumber());
        Assert.assertTrue("should contain all that is written",
                new String(out.toByteArray()).matches("hello world[\\n\\r]the display number is next[\\n\\r]1234[\\n\\r]that was the display number[\\n\\r]"));
    }

    @Test(expected = IllegalStateException.class)
    public void shouldWaitForDisplayNumber() throws InterruptedException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final AutoDisplayNameFilterStream stream = new AutoDisplayNameFilterStream(out, 1);

        final PrintStream printer = new PrintStream(stream);

        printer.println("hello world");
        printer.println("the display number is next");
        printer.println("that was the display number");

        stream.getDisplayNumber();

        printer.flush();
        printer.close();
    }

    @Test
    public void shouldWaitAndReceiveDisplayNumber() throws InterruptedException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final AutoDisplayNameFilterStream stream = new AutoDisplayNameFilterStream(out, 1);

        final PrintStream printer = new PrintStream(stream);

        printer.println("hello world");
        printer.println("the display number is next");
        printer.println("that was the display number");

        try {
            stream.getDisplayNumber();
            Assert.fail("No IllegalStateException received!");
        } catch (IllegalStateException ignore) {
        }

        printer.println("42");

        Assert.assertEquals("display number should be 42", 42, stream.getDisplayNumber());

        printer.flush();
        printer.close();
    }
}
