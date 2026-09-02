// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * A JDK {@link HttpServer} the tests drive the real MicroProfile Rest Client against.
 *
 * <p>The point is fidelity: the generated interfaces, the JSON-B binding, the request filters and
 * the exception mapper are all exercised over an actual socket, by an actual MicroProfile Rest
 * Client implementation. A mocked proxy would prove none of that — and "does the generated client
 * send the vendor media type" is exactly the kind of question a mock answers wrongly.
 */
public final class StubServer implements AutoCloseable {

    /** One request as the server saw it. */
    public static final class RecordedRequest {

        private final String method;
        private final String path;
        private final String query;
        private final com.sun.net.httpserver.Headers headers;
        private final String body;

        RecordedRequest(HttpExchange exchange, String body) {
            this.method = exchange.getRequestMethod();
            this.path = exchange.getRequestURI().getPath();
            this.query = exchange.getRequestURI().getQuery();
            this.headers = exchange.getRequestHeaders();
            this.body = body;
        }

        public String method() {
            return method;
        }

        public String path() {
            return path;
        }

        public String query() {
            return query;
        }

        public String body() {
            return body;
        }

        /** First value of a request header, case-insensitively, or {@code null}. */
        public String header(String name) {
            List<String> values = headers.get(name);
            return values == null || values.isEmpty() ? null : values.get(0);
        }
    }

    /** What the stub should answer with. */
    public static final class StubResponse {

        final int status;
        final String contentType;
        final byte[] body;
        final String contentEncoding;

        private StubResponse(int status, String contentType, byte[] body, String contentEncoding) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
            this.contentEncoding = contentEncoding;
        }

        public static StubResponse of(int status, String contentType, String body) {
            return new StubResponse(status, contentType, body.getBytes(StandardCharsets.UTF_8), null);
        }

        public static StubResponse of(int status, String contentType, byte[] body) {
            return new StubResponse(status, contentType, body, null);
        }

        public StubResponse contentEncoding(String contentEncoding) {
            return new StubResponse(status, contentType, body, contentEncoding);
        }

        public static StubResponse noContent() {
            return new StubResponse(204, null, new byte[0], null);
        }
    }

    private final HttpServer server;
    private final List<RecordedRequest> requests = new ArrayList<>();

    private StubServer(HttpServer server) {
        this.server = server;
    }

    /**
     * Starts a stub that answers every request with what {@code responder} returns for it.
     * Requests are recorded in order.
     */
    public static StubServer start(Function<RecordedRequest, StubResponse> responder) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            StubServer stub = new StubServer(server);
            server.createContext("/", exchange -> stub.handle(exchange, responder));
            server.start();
            return stub;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to start the stub server", e);
        }
    }

    /** The base URI to hand to {@code baseUri(...)} — includes the {@code /api} prefix the spec declares. */
    public URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api");
    }

    /** Every request the stub has served, in order. */
    public List<RecordedRequest> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    /** The single request the stub has served; fails when there was not exactly one. */
    public RecordedRequest onlyRequest() {
        List<RecordedRequest> served = requests();
        if (served.size() != 1) {
            throw new IllegalStateException("Expected exactly one request, got " + served.size());
        }
        return served.get(0);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange, Function<RecordedRequest, StubResponse> responder) throws IOException {
        byte[] requestBody = exchange.getRequestBody().readAllBytes();
        RecordedRequest request = new RecordedRequest(exchange, new String(requestBody, StandardCharsets.UTF_8));
        synchronized (requests) {
            requests.add(request);
        }

        StubResponse response;
        try {
            response = responder.apply(request);
        } catch (RuntimeException e) {
            byte[] message = ("stub responder failed: " + e).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(500, message.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(message);
            }
            return;
        }

        if (response.contentType != null) {
            exchange.getResponseHeaders().add("Content-Type", response.contentType);
        }
        if (response.contentEncoding != null) {
            exchange.getResponseHeaders().add("Content-Encoding", response.contentEncoding);
        }
        // -1 means "no body"; 0 would mean "chunked with unknown length" to HttpServer.
        exchange.sendResponseHeaders(response.status, response.body.length == 0 ? -1 : response.body.length);
        if (response.body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response.body);
            }
        } else {
            exchange.close();
        }
    }
}
