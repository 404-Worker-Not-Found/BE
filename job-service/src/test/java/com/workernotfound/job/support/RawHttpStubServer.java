package com.workernotfound.job.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 응답을 바이트 단위로 제어하는 소켓 기반 HTTP 대역 서버.
 *
 * <p>헤더 전 정지, 본문 일부 후 정지, 본문 조금씩 전송을 실제 소켓으로 재현하고, 클라이언트가 연결을 닫은 시각과
 * 아직 열린 연결 수를 관찰한다. 응답 처리 스레드는 연결이 닫히거나 시나리오 최대 시간이 지나면 끝난다.
 */
public class RawHttpStubServer implements AutoCloseable {

    private static final Duration MAX_SCENARIO_DURATION = Duration.ofSeconds(10);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(20);

    private final ServerSocket serverSocket;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final AtomicInteger openConnections = new AtomicInteger();
    private final List<Long> clientClosedAtNanos = new CopyOnWriteArrayList<>();
    private volatile Behavior behavior = Behavior.stallBeforeHeaders();

    private RawHttpStubServer(ServerSocket serverSocket) {
        this.serverSocket = serverSocket;
        executor.execute(this::acceptLoop);
    }

    public static RawHttpStubServer start() {
        try {
            return new RawHttpStubServer(new ServerSocket(0, 50, InetAddress.getLoopbackAddress()));
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + serverSocket.getLocalPort();
    }

    public void behave(Behavior behavior) {
        this.behavior = behavior;
    }

    public int openConnections() {
        return openConnections.get();
    }

    public List<Long> clientClosedAtNanos() {
        return List.copyOf(clientClosedAtNanos);
    }

    // 열린 연결이 모두 닫힐 때까지 기다린다. 제한 시간 안에 닫히지 않으면 false
    public boolean awaitAllClosed(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (openConnections.get() == 0) {
                return true;
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
        return openConnections.get() == 0;
    }

    @Override
    public void close() {
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // 테스트 종료 정리
        }
        executor.shutdownNow();
    }

    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                openConnections.incrementAndGet();
                executor.execute(() -> handle(socket, behavior));
            } catch (IOException exception) {
                return;
            }
        }
    }

    private void handle(Socket socket, Behavior currentBehavior) {
        try (socket) {
            readRequestHead(socket.getInputStream());
            OutputStream output = socket.getOutputStream();
            write(output, currentBehavior.immediate());
            if (currentBehavior.dripInterval() == null) {
                awaitClientClose(socket);
            } else {
                drip(output, currentBehavior.dripInterval());
            }
        } catch (IOException exception) {
            clientClosedAtNanos.add(System.nanoTime());
        } finally {
            openConnections.decrementAndGet();
        }
    }

    private void readRequestHead(InputStream input) throws IOException {
        int matched = 0;
        byte[] terminator = "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        while (matched < terminator.length) {
            int value = input.read();
            if (value == -1) {
                throw new SocketException("요청 헤더 수신 전 연결 종료");
            }
            matched = value == terminator[matched] ? matched + 1 : (value == terminator[0] ? 1 : 0);
        }
    }

    private void write(OutputStream output, String data) throws IOException {
        if (!data.isEmpty()) {
            output.write(data.getBytes(StandardCharsets.ISO_8859_1));
            output.flush();
        }
    }

    // 클라이언트가 닫으면 read가 -1 또는 예외를 돌려준다. 그 시각을 기록한다.
    private void awaitClientClose(Socket socket) throws IOException {
        socket.setSoTimeout((int) POLL_INTERVAL.toMillis());
        InputStream input = socket.getInputStream();
        long deadline = System.nanoTime() + MAX_SCENARIO_DURATION.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if (input.read() == -1) {
                    clientClosedAtNanos.add(System.nanoTime());
                    return;
                }
            } catch (SocketTimeoutException ignored) {
                // 계속 관찰
            }
        }
    }

    // 클라이언트가 닫기 전까지 간격마다 한 바이트씩 보낸다. 닫힌 뒤의 쓰기는 예외로 드러난다.
    private void drip(OutputStream output, Duration interval) throws IOException {
        long deadline = System.nanoTime() + MAX_SCENARIO_DURATION.toNanos();
        while (System.nanoTime() < deadline) {
            output.write(' ');
            output.flush();
            try {
                TimeUnit.NANOSECONDS.sleep(interval.toNanos());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * @param immediate    요청을 받은 직후 보내는 원문
     * @param dripInterval null이면 이후 아무것도 보내지 않고, 값이 있으면 그 간격으로 한 바이트씩 계속 보낸다
     */
    public record Behavior(String immediate, Duration dripInterval) {

        // 정상 응답 전체를 바로 보낸 뒤 연결을 유지한다.
        public static Behavior success() {
            String body = "{\"success\":true,\"status\":200,\"code\":\"SUCCESS\",\"data\":null}";
            return new Behavior("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                    + body.length() + "\r\n\r\n" + body, null);
        }

        public static Behavior stallBeforeHeaders() {
            return new Behavior("", null);
        }

        public static Behavior dripHeaders(Duration interval) {
            return new Behavior("HTTP/1.1 200 OK\r\nX-Slow:", interval);
        }

        public static Behavior partialBodyThenStall() {
            return new Behavior("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 1000\r\n\r\n{\"succ", null);
        }

        // 본문 길이를 크게 알리고 공백을 조금씩 보낸다. 읽기 타임아웃보다 짧은 간격이면 읽기 타임아웃으로는 끝나지 않는다.
        public static Behavior dripBody(Duration interval) {
            return new Behavior(
                    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 100000\r\n\r\n", interval);
        }
    }
}
