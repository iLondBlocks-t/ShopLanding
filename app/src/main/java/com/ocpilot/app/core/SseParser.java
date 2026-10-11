package com.ocpilot.app.core;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Incremental UTF-8 SSE decoder. Thread-confined; no Android dependencies. */
public final class SseParser {
    public interface Listener { void event(Frame frame); }
    public static final class Frame {
        public final String event, data, id;
        public final long retry;
        Frame(String event, String data, String id, long retry) {
            this.event = event; this.data = data; this.id = id; this.retry = retry;
        }
    }
    private final Listener listener;
    private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
    private ByteBuffer bytes = ByteBuffer.allocate(8192);
    private final CharBuffer chars = CharBuffer.allocate(4096);
    private final StringBuilder line = new StringBuilder(), data = new StringBuilder();
    private String event = "", id = "";
    private long retry = -1;
    private boolean afterCr, first = true;
    private static final int MAX_FRAME = 2 * 1024 * 1024;
    public SseParser(Listener listener) { this(listener, ""); }
    public SseParser(Listener listener, String initialId) { this.listener = listener; this.id = initialId == null ? "" : initialId; }
    public String lastId() { return id; }
    public long retryMillis() { return retry; }
    public void feed(byte[] chunk) { feed(chunk, 0, chunk.length); }
    public void feed(byte[] chunk, int offset, int length) {
        while (length > 0) {
            int count = Math.min(length, bytes.remaining());
            bytes.put(chunk, offset, count);
            offset += count; length -= count;
            decode(false);
        }
    }
    public void end() { decode(true); }
    private void decode(boolean end) {
        ((java.nio.Buffer) bytes).flip();
        while (true) {
            java.nio.charset.CoderResult result = decoder.decode(bytes, chars, end);
            consumeChars();
            if (!result.isOverflow()) break;
        }
        bytes.compact();
        if (end) {
            while (decoder.flush(chars).isOverflow()) consumeChars();
            consumeChars();
            // EOF is not a blank line: an incomplete frame is intentionally not dispatched.
        }
    }
    private void consumeChars() {
        ((java.nio.Buffer) chars).flip();
        while (chars.hasRemaining()) accept(chars.get());
        ((java.nio.Buffer) chars).clear();
    }
    private void accept(char c) {
        if (first) { first = false; if (c == '\uFEFF') return; }
        if (afterCr) { afterCr = false; if (c == '\n') return; }
        if (c == '\r') { finishLine(); afterCr = true; }
        else if (c == '\n') finishLine();
        else {
            if (line.length() + data.length() >= MAX_FRAME) throw new IllegalArgumentException("sse_frame_too_large");
            line.append(c);
        }
    }
    private void finishLine() {
        String value = line.toString(); line.setLength(0);
        if (value.isEmpty()) {
            if (data.length() > 0) {
                data.setLength(data.length() - 1);
                if (data.length() > 0) listener.event(new Frame(event.isEmpty() ? "message" : event, data.toString(), id, retry));
            }
            data.setLength(0); event = "";
            return;
        }
        if (value.charAt(0) == ':') return;
        int colon = value.indexOf(':');
        String field = colon < 0 ? value : value.substring(0, colon);
        String content = colon < 0 ? "" : value.substring(colon + 1);
        if (content.startsWith(" ")) content = content.substring(1);
        switch (field) {
            case "data": data.append(content).append('\n'); break;
            case "event": event = content; break;
            case "id": if (content.indexOf('\0') < 0) id = content; break;
            case "retry":
                if (content.matches("[0-9]+")) {
                    try { retry = Long.parseLong(content); } catch (NumberFormatException ignored) { }
                }
                break;
            default: break;
        }
    }
}
