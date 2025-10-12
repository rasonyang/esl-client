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
package org.freeswitch.esl.client.inbound;

import org.freeswitch.esl.client.internal.AbstractEslClientHandler;
import org.freeswitch.esl.client.internal.Context;
import org.freeswitch.esl.client.transport.CommandResponse;
import org.freeswitch.esl.client.transport.event.EslEvent;
import org.freeswitch.esl.client.transport.message.EslHeaders;
import org.freeswitch.esl.client.transport.socket.SocketWrapper;

/**
 * End users of the inbound {@link Client} should not need to use this class.
 * <p/>
 * Specialized {@link AbstractEslClientHandler} that implements the connection logic for an
 * 'Inbound' FreeSWITCH Event Socket connection.  The responsibilities for this class are:
 * <ul><li>
 * To handle the auth request that the FreeSWITCH server will send immediately following a new
 * connection when mode is Inbound.
 * <li>
 * To signal the observing {@link IEslProtocolListener} (expected to be the Inbound client
 * implementation) when ESL events are received.
 * </ul>
 * All message processing is done in virtual threads for maximum scalability.
 */
class InboundClientHandler extends AbstractEslClientHandler {

	private final String password;
	private final IEslProtocolListener listener;

	public InboundClientHandler(String password, IEslProtocolListener listener) {
		this.password = password;
		this.listener = listener;
	}

	@Override
	protected void handleEslEvent(SocketWrapper socket, EslEvent event) {
		log.debug("Received event: [{}]", event);
		listener.eventReceived(new Context(socket, this), event);
	}

	@Override
	protected void handleAuthRequest(SocketWrapper socket) {
		log.debug("Auth requested, sending [auth {}]", "*****");

		sendApiSingleLineCommand(socket, "auth " + password)
				.thenAccept(response -> {
					log.debug("Auth response [{}]", response);
					if (response.getContentType().equals(EslHeaders.Value.COMMAND_REPLY)) {
						final CommandResponse commandResponse = new CommandResponse("auth " + password, response);
						listener.authResponseReceived(commandResponse);
					} else {
						log.error("Bad auth response message [{}]", response);
						throw new IllegalStateException("Incorrect auth response");
					}
				});
	}

	@Override
	protected void handleDisconnectionNotice() {
		log.debug("Received disconnection notice");
		listener.disconnected();
	}

}
