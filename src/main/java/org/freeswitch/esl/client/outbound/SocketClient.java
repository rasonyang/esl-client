/*
 * Copyright 2010 david varnes.
 *
 * Licensed under the Apache License, version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.freeswitch.esl.client.outbound;

import org.freeswitch.esl.client.transport.message.EslMessage;
import org.freeswitch.esl.client.transport.socket.SocketFrameDecoder;
import org.freeswitch.esl.client.transport.socket.SocketWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Entry point to run a socket server that a running FreeSWITCH Event Socket Library module can
 * make outbound connections to.
 * <p/>
 * This class provides for what the FreeSWITCH documentation refers to as 'Outbound' connections
 * from the Event Socket module. That is, with reference to the module running on the FreeSWITCH
 * server, this server accepts an outbound connection from the server module.
 * <p/>
 * Each incoming connection is handled in its own virtual thread for maximum scalability.
 * <p/>
 * See <a href="http://wiki.freeswitch.org/wiki/Mod_event_socket">http://wiki.freeswitch.org/wiki/Mod_event_socket</a>
 */
public class SocketClient {

	private final Logger log = LoggerFactory.getLogger(this.getClass());
	private final IClientHandlerFactory clientHandlerFactory;
	private final SocketAddress bindAddress;
	private final ExecutorService callbackExecutor;
	private final AtomicBoolean running = new AtomicBoolean(false);

	private ServerSocket serverSocket;
	private Thread acceptorThread;

	public SocketClient(SocketAddress bindAddress, IClientHandlerFactory clientHandlerFactory) {
		this(bindAddress, clientHandlerFactory, Executors.newVirtualThreadPerTaskExecutor());
	}

	public SocketClient(SocketAddress bindAddress, IClientHandlerFactory clientHandlerFactory, ExecutorService callbackExecutor) {
		this.bindAddress = bindAddress;
		this.clientHandlerFactory = clientHandlerFactory;
		this.callbackExecutor = callbackExecutor;
	}

	/**
	 * Start the server and begin accepting connections
	 */
	public void start() throws IOException {
		if (running.compareAndSet(false, true)) {
			// Create server socket
			if (bindAddress instanceof InetSocketAddress) {
				InetSocketAddress inetAddress = (InetSocketAddress) bindAddress;
				serverSocket = new ServerSocket(inetAddress.getPort(), 50, inetAddress.getAddress());
			} else {
				throw new IllegalArgumentException("Only InetSocketAddress is supported");
			}

			// Start acceptor thread (virtual thread)
			acceptorThread = Thread.startVirtualThread(this::acceptLoop);

			log.info("SocketClient waiting for connections on [{}] ...", bindAddress);
		} else {
			throw new IllegalStateException("SocketClient is already running");
		}
	}

	/**
	 * Stop the server and close all connections
	 */
	public void stop() {
		if (running.compareAndSet(true, false)) {
			log.info("Stopping SocketClient...");

			// Close server socket to stop accepting new connections
			if (serverSocket != null && !serverSocket.isClosed()) {
				try {
					serverSocket.close();
				} catch (IOException e) {
					log.warn("Error closing server socket", e);
				}
			}

			// Interrupt acceptor thread
			if (acceptorThread != null) {
				acceptorThread.interrupt();
			}

			log.info("SocketClient stopped");
		}
	}

	/**
	 * Accept loop - runs in a virtual thread.
	 * Accepts incoming connections and spawns a handler thread for each.
	 */
	private void acceptLoop() {
		while (running.get() && !Thread.currentThread().isInterrupted()) {
			try {
				Socket clientSocket = serverSocket.accept();
				log.debug("Accepted connection from {}", clientSocket.getRemoteSocketAddress());

				// Handle each connection in its own virtual thread
				Thread.startVirtualThread(() -> handleClient(clientSocket));

			} catch (IOException e) {
				if (running.get()) {
					log.error("Error accepting connection", e);
				}
				// If not running, this is expected during shutdown
			}
		}
		log.info("Accept loop exiting");
	}

	/**
	 * Handle a single client connection - runs in a virtual thread.
	 */
	private void handleClient(Socket clientSocket) {
		SocketWrapper socket = null;
		OutboundClientHandler handler = null;
		try {
			// Configure socket
			clientSocket.setTcpNoDelay(true);
			clientSocket.setKeepAlive(true);

			socket = new SocketWrapper(clientSocket);

			// Create handler for this connection
			handler = new OutboundClientHandler(
					clientHandlerFactory.createClientHandler(),
					callbackExecutor);

			// Notify handler of new connection
			handler.onConnectionEstablished(socket);

			// Create decoder for this connection
			SocketFrameDecoder decoder = new SocketFrameDecoder(8092, true);

			// Message processing loop
			while (socket.isConnected() && !Thread.currentThread().isInterrupted()) {
				try {
					EslMessage message = decoder.decode(socket.getInputStream());
					log.debug("Received message: {}", message.getContentType());

					// Process message through handler
					handler.processMessage(socket, message);

				} catch (IOException e) {
					log.debug("Connection closed: {}", e.getMessage());
					break;
				} catch (Exception e) {
					log.error("Error processing message", e);
					handler.handleException(e);
					break;
				}
			}

		} catch (Exception e) {
			log.error("Error handling client connection", e);
		} finally {
			// Cleanup handler resources
			if (handler != null) {
				handler.shutdown();
			}

			// Cleanup socket
			if (socket != null) {
				try {
					socket.close();
				} catch (IOException e) {
					log.warn("Error closing client socket", e);
				}
			}
			log.debug("Client handler thread exiting");
		}
	}
}
