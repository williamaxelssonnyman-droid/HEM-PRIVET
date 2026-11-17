package se.william.hem;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A tiny HTTP server on port 8765 so a phone on the same wifi can be used as a remote.
 * Open http://<tv-ip>:8765 in Safari, "Add to Home Screen", pair once with the code shown on the TV.
 *
 * It lives inside the accessibility service, so it keeps running while YouTube, F1 TV etc.
 * are in front. Connections are kept alive so each button press is a single small write.
 */
public class RemoteServer {

    public static final int PORT = 8765;

    public interface Handler {
        /** Returns a JSON body, or null for "not found". */
        Response handle(String method, String path, Map<String, String> query, byte[] body);
    }

    public static class Response {
        final int status;
        final String type;
        final byte[] data;

        Response(int status, String type, byte[] data) {
            this.status = status;
            this.type = type;
            this.data = data;
        }

        static Response json(String s) {
            return new Response(200, "application/json; charset=utf-8", s.getBytes(StandardCharsets.UTF_8));
        }

        static Response of(String type, byte[] data) {
            return new Response(200, type, data);
        }

        static Response error(int status, String msg) {
            return new Response(status, "application/json; charset=utf-8",
                    ("{\"error\":\"" + msg + "\"}").getBytes(StandardCharsets.UTF_8));
        }
    }

    private final Handler handler;
    private final SharedPreferences prefs;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final SecureRandom random = new SecureRandom();
    private volatile ServerSocket server;
    private volatile boolean running;

    /** The 4-digit code currently shown on the TV, and when it stops being valid. */
    private volatile String pairCode;
    private volatile long pairCodeUntil;
    private int failedPairs;

    public RemoteServer(Context ctx, Handler handler) {
        this.handler = handler;
        this.prefs = ctx.getSharedPreferences("remote", Context.MODE_PRIVATE);
    }

    public void start() {
        if (running) return;
        running = true;
        Thread t = new Thread(() -> {
            while (running) {
                try (ServerSocket ss = new ServerSocket()) {
                    ss.setReuseAddress(true);
                    ss.bind(new InetSocketAddress(PORT));
                    server = ss;
                    while (running) {
                        Socket s = ss.accept();
                        pool.execute(() -> serve(s));
                    }
                } catch (IOException e) {
                    if (!running) return;
                    try {
                        Thread.sleep(2000); // port busy right after an update, try again
                    } catch (InterruptedException ignored) {
                        return;
                    }
                }
            }
        }, "hem-remote");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        pool.shutdownNow();
    }

    // ---------------------------------------------------------------- pairing

    /** Makes a new code; the caller shows it on the TV. */
    public synchronized String newPairCode() {
        pairCode = String.format("%04d", random.nextInt(10000));
        pairCodeUntil = System.currentTimeMillis() + 120_000;
        failedPairs = 0;
        return pairCode;
    }

    private synchronized String tryPair(String code) {
        if (pairCode == null || System.currentTimeMillis() > pairCodeUntil) return null;
        if (!pairCode.equals(code)) {
            if (++failedPairs >= 5) pairCode = null; // no brute forcing
            return null;
        }
        pairCode = null;
        byte[] b = new byte[18];
        random.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        String token = sb.toString();
        Set<String> tokens = new HashSet<>(prefs.getStringSet("tokens", new HashSet<>()));
        tokens.add(token);
        prefs.edit().putStringSet("tokens", tokens).apply();
        return token;
    }

    private boolean authorized(String token) {
        if (token == null || token.isEmpty()) return false;
        return prefs.getStringSet("tokens", new HashSet<>()).contains(token);
    }

    public void forgetAllPhones() {
        prefs.edit().remove("tokens").apply();
    }

    // ---------------------------------------------------------------- HTTP

    private void serve(Socket s) {
        try (Socket sock = s) {
            sock.setTcpNoDelay(true);
            sock.setSoTimeout(60_000);
            InputStream in = new BufferedInputStream(sock.getInputStream());
            OutputStream out = sock.getOutputStream();
            while (running) {
                String requestLine = readLine(in);
                if (requestLine == null || requestLine.isEmpty()) return;
                Map<String, String> headers = new HashMap<>();
                String line;
                while ((line = readLine(in)) != null && !line.isEmpty()) {
                    int c = line.indexOf(':');
                    if (c > 0) headers.put(line.substring(0, c).trim().toLowerCase(), line.substring(c + 1).trim());
                }
                String[] parts = requestLine.split(" ");
                if (parts.length < 2) return;
                String method = parts[0];
                String target = parts[1];

                int len = 0;
                try {
                    len = Integer.parseInt(headers.getOrDefault("content-length", "0"));
                } catch (NumberFormatException ignored) {
                }
                if (len < 0 || len > 64_000) return;
                byte[] body = new byte[len];
                int read = 0;
                while (read < len) {
                    int n = in.read(body, read, len - read);
                    if (n < 0) return;
                    read += n;
                }

                String path = target;
                Map<String, String> query = new HashMap<>();
                int q = target.indexOf('?');
                if (q >= 0) {
                    path = target.substring(0, q);
                    for (String kv : target.substring(q + 1).split("&")) {
                        int e = kv.indexOf('=');
                        if (e > 0) query.put(dec(kv.substring(0, e)), dec(kv.substring(e + 1)));
                    }
                }

                Response r = route(method, path, query, body, headers);
                boolean keepAlive = !"close".equalsIgnoreCase(headers.get("connection"));
                write(out, r, keepAlive);
                if (!keepAlive) return;
            }
        } catch (Exception ignored) {
        }
    }

    private Response route(String method, String path, Map<String, String> query, byte[] body,
                           Map<String, String> headers) {
        // The page itself and its icons are public; everything that does something needs a token.
        if (path.equals("/pair/start") && method.equals("POST")) {
            return handler.handle(method, path, query, body);
        }
        if (path.equals("/pair") && method.equals("POST")) {
            String token = tryPair(query.get("code"));
            if (token == null) return Response.error(403, "wrong code");
            return Response.json("{\"token\":\"" + token + "\"}");
        }
        boolean isPublic = method.equals("GET") && (path.equals("/") || path.startsWith("/static/"));
        if (!isPublic) {
            String token = headers.get("x-token");
            if (token == null) token = query.get("t");
            if (!authorized(token)) return Response.error(401, "not paired");
        }
        Response r = handler.handle(method, path, query, body);
        return r != null ? r : Response.error(404, "not found");
    }

    private static void write(OutputStream out, Response r, boolean keepAlive) throws IOException {
        String head = "HTTP/1.1 " + r.status + (r.status == 200 ? " OK" : " Error") + "\r\n"
                + "Content-Type: " + r.type + "\r\n"
                + "Content-Length: " + r.data.length + "\r\n"
                + "Cache-Control: " + (r.type.startsWith("image/") ? "max-age=86400" : "no-store") + "\r\n"
                + "Connection: " + (keepAlive ? "keep-alive" : "close") + "\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(r.data);
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) return null;
        }
        if (c < 0 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }

    private static String dec(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
