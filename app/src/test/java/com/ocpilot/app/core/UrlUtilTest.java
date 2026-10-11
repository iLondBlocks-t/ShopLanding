package com.ocpilot.app.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class UrlUtilTest {
    @Test public void bareIpWithPort() { assertEquals("http://1.2.3.4:25565", UrlUtil.normalize("1.2.3.4:25565")); }
    @Test public void hostnameDefaultsToHttps() { assertEquals("https://host.example.com", UrlUtil.normalize("host.example.com")); }
    @Test public void explicitHttp() { assertEquals("http://1.2.3.4:25565", UrlUtil.normalize("http://1.2.3.4:25565")); }
    @Test public void trimsAndRemovesSlash() { assertEquals("https://host", UrlUtil.normalize("  https://host/  ")); }
    @Test public void discardsAllPathsQueriesAndFragments() { assertEquals("https://example.com", UrlUtil.normalize("https://example.com/a/b?key=x#anchor")); }
    @Test public void hostnameWithExplicitPortDefaultsToHttp() { assertEquals("http://example.com:4096", UrlUtil.normalize("example.com:4096")); }
    @Test public void bareIpDefaultsToHttp() { assertEquals("http://127.0.0.1", UrlUtil.normalize("127.0.0.1")); }
    @Test public void explicitHttpsIsPreservedForIp() { assertEquals("https://1.2.3.4:443", UrlUtil.normalize("https://1.2.3.4:443/")); }
    @Test public void lowercasesHostAndScheme() { assertEquals("https://example.com", UrlUtil.normalize("HTTPS://EXAMPLE.COM/")); }
    @Test public void bracketedIpv6WithPort() { assertEquals("http://[2001:db8::1]:25565", UrlUtil.normalize("[2001:db8::1]:25565")); }
    @Test public void idnHostname() { assertEquals("https://xn--mnich-kva.example", UrlUtil.normalize("münich.example")); }
    @Test public void headerCursorIsOpaqueAndDecodedExactlyOnce() {
        assertEquals("a%2Fb==",UrlUtil.cursorFromLink("<https://private.invalid/session/ses_main/message?limit=400&before=a%252Fb%3D%3D>; rel=\"next\""));
        assertEquals("",UrlUtil.cursorFromLink("<https://private.invalid/message?before=x>; rel=\"prev\""));
        assertEquals("",UrlUtil.cursorFromLink(null));
    }
    @Test public void invalidInputsRejected() {
        for (String value : new String[]{null,"","  ","http://","ftp://host","https:///host","a b.com","1.2.3.999","host:abc","host:0","host:65536","host:","https://user:pass@host","http://-host","foo..bar","[::::]","http://%65xample.com","file:///tmp/a","1:2:3:4"}) {
            try { UrlUtil.normalize(value); fail("Accepted invalid address: " + value); } catch (IllegalArgumentException expected) { }
        }
    }
}
