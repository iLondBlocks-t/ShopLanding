package com.ocpilot.app.core;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class SseParserTest {
    private List<SseParser.Frame> parse(String value) {
        List<SseParser.Frame> frames = new ArrayList<>(); SseParser parser = new SseParser(frames::add);
        parser.feed(value.getBytes(StandardCharsets.UTF_8)); parser.end(); return frames;
    }
    @Test public void lf() { assertEquals("hello", parse("data: hello\n\n").get(0).data); }
    @Test public void crlf() { assertEquals("hello", parse("data: hello\r\n\r\n").get(0).data); }
    @Test public void cr() { assertEquals("hello", parse("data: hello\r\r").get(0).data); }
    @Test public void multipleDataLines() { assertEquals("one\ntwo", parse("data: one\ndata:two\n\n").get(0).data); }
    @Test public void idEventAndRetry() {
        SseParser.Frame f = parse("event: update\nid: 42\nretry: 1500\ndata: hello\n\n").get(0);
        assertEquals("update", f.event); assertEquals("42", f.id); assertEquals(1500, f.retry);
    }
    @Test public void commentsAndUnknownFieldsIgnored() {
        List<SseParser.Frame> frames = parse(": ping\nunused: x\n\ndata:ok\n: ignored\n\n"); assertEquals(1, frames.size()); assertEquals("ok", frames.get(0).data);
    }
    @Test public void emptyDataIsIgnored() { assertTrue(parse("data:\n\n: ping\n\nevent: x\n\n").isEmpty()); }
    @Test public void everyByteBoundaryIncludingUtf8AndCrLf() {
        byte[] sample = "\uFEFFid: x\r\nevent: token\r\ndata: مرحبًا 🌍\r\ndata: second\r\n\r\ndata: end\n\n".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i <= sample.length; i++) {
            List<SseParser.Frame> frames = new ArrayList<>(); SseParser parser = new SseParser(frames::add);
            parser.feed(sample, 0, i); parser.feed(sample, i, sample.length - i); parser.end();
            assertEquals("split " + i, 2, frames.size()); assertEquals("مرحبًا 🌍\nsecond", frames.get(0).data); assertEquals("x", frames.get(1).id);
        }
    }
    @Test public void byteAtATime() {
        List<SseParser.Frame> frames = new ArrayList<>(); SseParser parser = new SseParser(frames::add);
        for (byte b : "data:🙂العربية\r\n\r\n".getBytes(StandardCharsets.UTF_8)) parser.feed(new byte[]{b});
        parser.end(); assertEquals("🙂العربية", frames.get(0).data);
    }
    @Test public void idOnlyAndRetryOnlyHaveNoDispatchButPersist() {
        List<SseParser.Frame> frames = new ArrayList<>(); SseParser parser = new SseParser(frames::add);
        parser.feed("id: 9\nretry: 2000\n\n".getBytes(StandardCharsets.UTF_8)); assertTrue(frames.isEmpty()); assertEquals("9", parser.lastId()); assertEquals(2000, parser.retryMillis());
        parser.feed("data: ok\n\n".getBytes(StandardCharsets.UTF_8)); assertEquals("9", frames.get(0).id);
    }
    @Test public void invalidIdAndRetryIgnored() {
        SseParser.Frame f = parse("id: valid\nid:bad\0id\nretry: -1\nretry: text\ndata:ok\n\n").get(0); assertEquals("valid", f.id); assertEquals(-1, f.retry);
    }
    @Test public void unfinishedFrameNotDispatchedAtEof() { assertTrue(parse("data: not-terminated").isEmpty()); }
    @Test public void oneLeadingSpaceOnlyIsRemoved() { assertEquals(" padded", parse("data:  padded\n\n").get(0).data); }
    @Test public void reconnectPreservesIdUnlessExplicitlyReset() {
        List<SseParser.Frame> frames = new ArrayList<>(); SseParser parser = new SseParser(frames::add,"previous");
        parser.feed("data: resume\n\nid:\n\ndata: reset\n\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("previous",frames.get(0).id); assertEquals("",frames.get(1).id);
    }
    @Test public void realSpecEventDecodesAsData() throws Exception {
        String event = Fixtures.object("oc-text-start.json").toString(); assertEquals(event, parse("data: " + event + "\n\n").get(0).data);
    }
}
