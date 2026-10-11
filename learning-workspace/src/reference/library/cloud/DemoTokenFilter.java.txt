package org.octavio.paymentreconciliationsim.security;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.octavio.paymentreconciliationsim.http.ApiExceptionHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DemoTokenFilter extends OncePerRequestFilter {
    public static final int MAX_REPORT_BYTES = 8_388_608;
    private final byte[] demoToken;
    private final byte[] workerToken;
    private final JsonMapper json;

    public DemoTokenFilter(@Value("${reconciliation.security.demo-token}") String demoToken,
                           @Value("${reconciliation.security.worker-token}") String workerToken,
                           JsonMapper json) {
        this.demoToken = token(demoToken);
        this.workerToken = token(workerToken);
        if (MessageDigest.isEqual(this.demoToken, this.workerToken))
            throw new IllegalArgumentException("Demo and worker bearer tokens must be distinct");
        this.json = json;
    }

    private static byte[] token(String value) {
        if (value == null || !value.matches("[!-~]{1,1024}"))
            throw new IllegalArgumentException("Bearer tokens must contain 1 to 1024 printable ASCII characters without spaces");
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = UUID.randomUUID().toString();
        request.setAttribute(ApiExceptionHandler.CORRELATION_ID, correlationId);
        response.setHeader("X-Correlation-Id", correlationId);
        String path = request.getServletPath();
        boolean worker = path.equals("/internal/v1") || path.startsWith("/internal/v1/");
        boolean demo = path.equals("/api/v1") || path.startsWith("/api/v1/");
        if (worker || demo) {
            String authorization = request.getHeader("Authorization");
            if (authorization == null || !authorization.startsWith("Bearer ")) {
                reject(response, 401, correlationId); return;
            }
            byte[] supplied = authorization.substring(7).getBytes(StandardCharsets.UTF_8);
            // Compare both configured tokens on every authenticated route, without String.equals on secrets.
            boolean matchesDemo = MessageDigest.isEqual(demoToken, supplied);
            boolean matchesWorker = MessageDigest.isEqual(workerToken, supplied);
            if (!matchesDemo && !matchesWorker) { reject(response, 401, correlationId); return; }
            if (worker ? !matchesWorker : !matchesDemo) { reject(response, 403, correlationId); return; }
        }
        if (request.getMethod().equals("PUT") && path.matches("/internal/v1/reconciliation-runs/[^/]+/results")) {
            byte[] body;
            try { body = request.getInputStream().readNBytes(MAX_REPORT_BYTES + 1); }
            catch (IOException unreadable) { reject(response, 400, correlationId); return; }
            if (body.length > MAX_REPORT_BYTES) { reject(response, 413, correlationId); return; }
            chain.doFilter(new BufferedRequest(request, body), response);
        } else {
            chain.doFilter(request, response);
        }
    }

    private void reject(HttpServletResponse response, int status, String correlationId) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        if (status == 401) response.setHeader("WWW-Authenticate", "Bearer");
        json.writeValue(response.getOutputStream(), ApiExceptionHandler.error(status, correlationId));
    }

    static final class BufferedRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        BufferedRequest(HttpServletRequest request, byte[] body) { super(request); this.body = body; }
        @Override public ServletInputStream getInputStream() {
            return new BodyStream(body);
        }
        @Override public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }

    static final class BodyStream extends ServletInputStream {
        private final ByteArrayInputStream source;
        BodyStream(byte[] body) { source = new ByteArrayInputStream(body); }
        @Override public int read() { return source.read(); }
        @Override public boolean isFinished() { return source.available() == 0; }
        @Override public boolean isReady() { return true; }
        @Override public void setReadListener(ReadListener listener) {
            throw new UnsupportedOperationException("Report submission uses synchronous input");
        }
    }
}
