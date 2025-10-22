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

import org.freeswitch.esl.client.internal.AbstractEslClientHandler;
import org.freeswitch.esl.client.internal.Context;
import org.freeswitch.esl.client.transport.event.EslEvent;
import org.freeswitch.esl.client.transport.message.EslMessage;
import org.freeswitch.esl.client.transport.socket.SocketWrapper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Specialized {@link AbstractEslClientHandler} that implements the base connection logic for an
 * 'Outbound' FreeSWITCH Event Socket connection.  The responsibilities for this class are:
 * <ul><li>
 * To send a 'connect' command when the FreeSWITCH server first establishes a new connection with
 * the socket client in Outbound mode.  This will result in an incoming {@link EslMessage} that is
 * transformed into an {@link EslEvent} that sub classes can handle.
 * </ul>
 * All message processing is done in virtual threads for maximum scalability.
 * Each outbound connection gets its own single-threaded virtual thread executor to ensure
 * all callbacks (onConnect and onEslEvent) are processed sequentially in order.
 */
class OutboundClientHandler extends AbstractEslClientHandler {

	private final IClientHandler clientHandler;
	// Single-threaded virtual thread executor for this connection to ensure event ordering
	private final ExecutorService eventExecutor;

	public OutboundClientHandler(IClientHandler clientHandler, ExecutorService callbackExecutor) {
		this.clientHandler = clientHandler;
		// Each outbound connection gets its own single-threaded executor to preserve event order
		this.eventExecutor = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
	}

	/**
	 * Called when a new connection is established.
	 * Sends 'connect' command to FreeSWITCH server.
	 */
	public void onConnectionEstablished(final SocketWrapper socket) {
		// Have received a connection from FreeSWITCH server, send connect response
		log.debug("Received new connection from server, sending connect message");

		sendApiSingleLineCommand(socket, "connect")
				.thenAccept(response -> {
					log.info("Connection established, subscribing to events");

					// Subscribe to events for this channel only using 'myevents'
					// In outbound mode, 'myevents' automatically filters events to the current channel
					sendApiSingleLineCommand(socket, "myevents")
							.thenAccept(eventResponse -> {
								log.info("Event subscription confirmed: {}",
									eventResponse.getHeaderValue(org.freeswitch.esl.client.transport.message.EslHeaders.Name.REPLY_TEXT));

								// Notify client handler after subscription confirmed
								// Run in eventExecutor to ensure single-threaded processing per channel
								eventExecutor.execute(() -> clientHandler.onConnect(
										new Context(socket, OutboundClientHandler.this),
										new EslEvent(response, true)));
							})
							.exceptionally(throwable -> {
								log.error("Failed to subscribe to events", throwable);
								// Still notify handler even if subscription failed
								// Run in eventExecutor to ensure single-threaded processing per channel
								eventExecutor.execute(() -> clientHandler.onConnect(
										new Context(socket, OutboundClientHandler.this),
										new EslEvent(response, true)));
								return null;
							});
				})
				.exceptionally(throwable -> {
					try {
						socket.close();
					} catch (Exception ignored) {
					}
					handleDisconnectionNotice();
					return null;
				});
	}

	@Override
	protected void handleEslEvent(final SocketWrapper socket, final EslEvent event) {
		// Use single-threaded executor to ensure events are processed in order
		eventExecutor.execute(() -> clientHandler.onEslEvent(
				new Context(socket, OutboundClientHandler.this), event));
	}

	@Override
	protected void handleAuthRequest(SocketWrapper socket) {
		// This should not happen in outbound mode
		log.warn("Auth request received in outbound mode, ignoring");
	}

	@Override
	protected void handleDisconnectionNotice() {
		log.debug("Received disconnection notice");
	}

	/**
	 * Shutdown the event executor to release resources.
	 * Should be called when the connection is closed.
	 */
	public void shutdown() {
		log.debug("Shutting down event executor");
		eventExecutor.shutdown();
	}
}
