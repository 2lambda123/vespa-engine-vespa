// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.jdisc.http;

import org.eclipse.jetty.server.CookieCutter;
import org.eclipse.jetty.server.Response;

import java.net.HttpCookie;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * A RFC 6265 compliant cookie.
 *
 * Note: RFC 2109 and RFC 2965 is no longer supported. All fields that are not part of RFC 6265 are deprecated.
 *
 * @author Einar M R Rosenvinge
 * @author bjorncs
 */
public class Cookie {

    private final Set<Integer> ports = new HashSet<>();
    private String name;
    private String value;
    private String domain;
    private String path;
    private long maxAgeSeconds = Integer.MIN_VALUE;
    private boolean secure;
    private boolean httpOnly;
    private boolean discard;

    public Cookie() {
    }

    public Cookie(Cookie cookie) {
        ports.addAll(cookie.ports);
        name = cookie.name;
        value = cookie.value;
        domain = cookie.domain;
        path = cookie.path;
        maxAgeSeconds = cookie.maxAgeSeconds;
        secure = cookie.secure;
        httpOnly = cookie.httpOnly;
        discard = cookie.discard;
    }

    public Cookie(String name, String value) {
        this.name = name;
        this.value = value;
    }

    public String getName() {
        return name;
    }

    public Cookie setName(String name) {
        this.name = name;
        return this;
    }

    public String getValue() {
        return value;
    }

    public Cookie setValue(String value) {
        this.value = value;
        return this;
    }

    public String getDomain() {
        return domain;
    }

    public Cookie setDomain(String domain) {
        this.domain = domain;
        return this;
    }

    public String getPath() {
        return path;
    }

    public Cookie setPath(String path) {
        this.path = path;
        return this;
    }

    public int getMaxAge(TimeUnit unit) {
        return (int)unit.convert(maxAgeSeconds, TimeUnit.SECONDS);
    }

    public Cookie setMaxAge(int maxAge, TimeUnit unit) {
        this.maxAgeSeconds = maxAge >= 0 ? unit.toSeconds(maxAge) : Integer.MIN_VALUE;
        return this;
    }

    public boolean isSecure() {
        return secure;
    }

    public Cookie setSecure(boolean secure) {
        this.secure = secure;
        return this;
    }

    public boolean isHttpOnly() {
        return httpOnly;
    }

    public Cookie setHttpOnly(boolean httpOnly) {
        this.httpOnly = httpOnly;
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Cookie cookie = (Cookie) o;
        return maxAgeSeconds == cookie.maxAgeSeconds &&
                secure == cookie.secure &&
                httpOnly == cookie.httpOnly &&
                discard == cookie.discard &&
                Objects.equals(ports, cookie.ports) &&
                Objects.equals(name, cookie.name) &&
                Objects.equals(value, cookie.value) &&
                Objects.equals(domain, cookie.domain) &&
                Objects.equals(path, cookie.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ports, name, value, domain, path, maxAgeSeconds, secure, httpOnly, discard);
    }

    @Override
    public String toString() {
        StringBuilder ret = new StringBuilder();
        ret.append(name).append("=").append(value);
        return ret.toString();
    }
    // NOTE cookie encoding and decoding:
    //      The implementation uses Jetty for server-side (encoding of Set-Cookie and decoding of Cookie header),
    //      and java.net.HttpCookie for client-side (encoding of Cookie and decoding of Set-Cookie header).
    //
    // Implementation is RFC-6265 compliant.

    public static String toCookieHeader(Iterable<? extends Cookie> cookies) {
        return StreamSupport.stream(cookies.spliterator(), false)
                .map(cookie -> {
                    HttpCookie httpCookie = new HttpCookie(cookie.getName(), cookie.getValue());
                    httpCookie.setDomain(cookie.getDomain());
                    httpCookie.setHttpOnly(cookie.isHttpOnly());
                    httpCookie.setMaxAge(cookie.getMaxAge(TimeUnit.SECONDS));
                    httpCookie.setPath(cookie.getPath());
                    httpCookie.setSecure(cookie.isSecure());
                    httpCookie.setVersion(0);
                    return httpCookie.toString();
                })
                .collect(Collectors.joining(";"));
    }

    public static List<Cookie> fromCookieHeader(String headerVal) {
        CookieCutter cookieCutter = new CookieCutter();
        cookieCutter.addCookieField(headerVal);
        return Arrays.stream(cookieCutter.getCookies())
                .map(servletCookie -> {
                    Cookie cookie = new Cookie();
                    cookie.setName(servletCookie.getName());
                    cookie.setValue(servletCookie.getValue());
                    cookie.setPath(servletCookie.getPath());
                    cookie.setDomain(servletCookie.getDomain());
                    cookie.setMaxAge(servletCookie.getMaxAge(), TimeUnit.SECONDS);
                    cookie.setSecure(servletCookie.getSecure());
                    cookie.setHttpOnly(servletCookie.isHttpOnly());
                    return cookie;
                })
                .collect(Collectors.toList());
    }

    public static List<String> toSetCookieHeaders(Iterable<? extends Cookie> cookies) {
        // Ugly, bot Jetty does not provide a dedicated cookie parser (will be included in Jetty 10)
        Response response = new Response(null, null);
        for (Cookie cookie : cookies) {
            response.addSetRFC6265Cookie(
                    cookie.getName(),
                    cookie.getValue(),
                    cookie.getDomain(),
                    cookie.getPath(),
                    cookie.getMaxAge(TimeUnit.SECONDS),
                    cookie.isSecure(),
                    cookie.isHttpOnly());
        }
        return new ArrayList<>(response.getHeaders("Set-Cookie"));
    }

    @Deprecated // TODO Vespa 8 Remove
    public static List<String> toSetCookieHeaderAll(Iterable<? extends Cookie> cookies) {
        return toSetCookieHeaders(cookies);
    }

    public static Cookie fromSetCookieHeader(String headerVal) {
        return HttpCookie.parse(headerVal).stream()
                .map(httpCookie -> {
                    Cookie cookie = new Cookie();
                    cookie.setName(httpCookie.getName());
                    cookie.setValue(httpCookie.getValue());
                    cookie.setDomain(httpCookie.getDomain());
                    cookie.setHttpOnly(httpCookie.isHttpOnly());
                    cookie.setMaxAge((int) httpCookie.getMaxAge(), TimeUnit.SECONDS);
                    cookie.setPath(httpCookie.getPath());
                    cookie.setSecure(httpCookie.getSecure());
                    return cookie;
                })
                .findFirst().get();
    }

}
