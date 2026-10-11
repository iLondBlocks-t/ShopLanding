package com.ocpilot.app.core;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import static org.junit.Assert.*;

public class HashingTest {
    private String hash(String text) throws Exception { return Hashing.sha256(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))); }
    @Test public void emptyVector() throws Exception { assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",hash("")); }
    @Test public void abcVector() throws Exception { assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",hash("abc")); }
    @Test public void millionAsAcrossChunkSizes() throws Exception {
        byte[] bytes = new byte[1000000]; Arrays.fill(bytes,(byte)'a');
        for (int chunk : new int[]{1,7,127,1024,32768}) {
            ByteArrayInputStream in = new ByteArrayInputStream(bytes) { @Override public synchronized int read(byte[] b,int off,int len) { return super.read(b,off,Math.min(len,chunk)); } };
            assertEquals("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",Hashing.sha256(in));
        }
    }
    @Test public void hexHandlesSignedBytes() { assertEquals("00ff80ab",Hashing.hex(new byte[]{0,-1,-128,(byte)0xab})); }
}
