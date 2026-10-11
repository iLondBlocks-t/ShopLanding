package com.ocpilot.app.core;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

final class Fixtures {
    private Fixtures() { }
    static String text(String name) throws Exception {
        try (InputStream in = Fixtures.class.getResourceAsStream("/fixtures/" + name); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new IllegalArgumentException("missing_fixture:" + name);
            byte[] buffer = new byte[4096]; int n; while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
    static JSONObject object(String name) throws Exception { return new JSONObject(text(name)); }
    static JSONArray array(String name) throws Exception { return new JSONArray(text(name)); }
}
