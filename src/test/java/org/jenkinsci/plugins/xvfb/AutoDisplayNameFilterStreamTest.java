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
