package com.ocpilot.app.core;

import java.net.IDN;
import java.net.URI;
import java.util.Locale;

/** Address parsing only: this class never resolves a host or contacts a network. */
public final class UrlUtil {
    /** Read only the opaque before parameter; never navigate to a Link header's host. */
    public static String cursorFromLink(String link) {
        if (link == null) return "";
        try {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("<([^>]+)>\\s*;[^,]*rel=\"?next\"?").matcher(link);
            if (!matcher.find()) return "";
            String query = new java.net.URI(matcher.group(1)).getRawQuery(); if (query == null) return "";
            for (String parameter : query.split("&")) {
                int split = parameter.indexOf('=');
                if (split > 0 && java.net.URLDecoder.decode(parameter.substring(0, split), "UTF-8").equals("before")) return java.net.URLDecoder.decode(parameter.substring(split + 1), "UTF-8");
            }
        } catch (Exception ex) { return ""; }
        return "";
    }
    private UrlUtil() { }
    public static String normalize(String input) {
        if (input == null || input.trim().isEmpty()) throw invalid();
        String value = input.trim();
        if (value.matches(".*[\\s\\\\].*")) throw invalid();
        boolean suppliedScheme = value.contains("://");
        if (!suppliedScheme) value = "https://" + value;
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) throw invalid();
            String authority = uri.getRawAuthority();
            if (authority == null || authority.isEmpty() || authority.contains("@") || authority.contains("%")) throw invalid();
            String host;
            String portText = null;
            if (authority.startsWith("[")) {
                int end = authority.indexOf(']');
                if (end < 0) throw invalid();
                host = authority.substring(0, end + 1).toLowerCase(Locale.ROOT);
                // URI validates IPv6 syntax without DNS.
                if (uri.getHost() == null || host.indexOf(':') < 0) throw invalid();
                String rest = authority.substring(end + 1);
                if (!rest.isEmpty()) {
                    if (!rest.startsWith(":")) throw invalid();
                    portText = rest.substring(1);
                }
            } else {
                int colon = authority.indexOf(':');
                if (colon >= 0) {
                    if (authority.indexOf(':', colon + 1) >= 0) throw invalid();
                    portText = authority.substring(colon + 1);
                    authority = authority.substring(0, colon);
                }
                host = IDN.toASCII(authority, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
                if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
                if (host.isEmpty() || host.length() > 253) throw invalid();
                for (String label : host.split("\\.", -1)) {
                    if (label.isEmpty() || label.length() > 63 || !label.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")) throw invalid();
                }
                if (host.matches("[0-9.]+") && !isIpv4(host)) throw invalid();
            }
            String port = "";
            if (portText != null) {
                if (!portText.matches("[0-9]{1,5}")) throw invalid();
                int p = Integer.parseInt(portText);
                if (p < 1 || p > 65535) throw invalid();
                port = ":" + p;
            }
            if (!suppliedScheme) scheme = (isIpv4(host) || host.startsWith("[") || portText != null) ? "http" : "https";
            return scheme + "://" + host + port;
        } catch (Exception ex) { throw invalid(); }
    }
    private static boolean isIpv4(String host) {
        String[] pieces = host.split("\\.", -1);
        if (pieces.length != 4) return false;
        for (String piece : pieces) {
            if (!piece.matches("[0-9]{1,3}")) return false;
            if (Integer.parseInt(piece) > 255) return false;
        }
        return true;
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("invalid_address"); }
}
