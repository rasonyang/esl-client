package org.freeswitch.esl.client.transport.socket;

import java.io.*;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Wrapper around Java Socket providing thread-safe operations
 * for ESL protocol communication.
 */
public class SocketWrapper implements AutoCloseable {

    private final Socket socket;
    private final InputStream inputStream;
    private final OutputStream outputStream;
    private final ReentrantLock writeLock = new ReentrantLock();

    public SocketWrapper(Socket socket) throws IOException {
        this.socket = socket;
        this.inputStream = new BufferedInputStream(socket.getInputStream());
        this.outputStream = new BufferedOutputStream(socket.getOutputStream());
    }

    public static SocketWrapper connect(SocketAddress address, int timeoutMillis) throws IOException {
        Socket socket = new Socket();
        socket.connect(address, timeoutMillis);
        socket.setKeepAlive(true);
        socket.setTcpNoDelay(true);
        return new SocketWrapper(socket);
    }

    public InputStream getInputStream() {
        return inputStream;
    }

    public OutputStream getOutputStream() {
        return outputStream;
    }

    /**
     * Thread-safe write operation
     */
    public void write(String data) throws IOException {
        writeLock.lock();
        try {
            outputStream.write(data.getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Thread-safe write and flush operation
     */
    public void writeAndFlush(String data) throws IOException {
        write(data);
    }

    public boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    public boolean isClosed() {
        return socket == null || socket.isClosed();
    }

    @Override
    public void close() throws IOException {
        if (socket != null && !socket.isClosed()) {
            try {
                outputStream.close();
            } catch (IOException ignored) {
            }
            try {
                inputStream.close();
            } catch (IOException ignored) {
            }
            socket.close();
        }
    }

    public Socket getSocket() {
        return socket;
    }
}
