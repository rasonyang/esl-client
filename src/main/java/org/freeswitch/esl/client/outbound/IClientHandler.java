package org.freeswitch.esl.client.outbound;

import org.freeswitch.esl.client.inbound.IEslEventListener;
import org.freeswitch.esl.client.internal.Context;
import org.freeswitch.esl.client.transport.event.EslEvent;

/**
 * Handler interface for outbound FreeSWITCH connections.
 * Compatible with https://github.com/esl-client/esl-client API.
 */
public interface IClientHandler extends IEslEventListener {

	/**
	 * Called when the outbound connection is established.
	 *
	 * @param context the context for sending commands
	 * @param event the connection event
	 */
	void onConnect(Context context, EslEvent event);

	/**
	 * Alias for {@link #onEslEvent(Context, EslEvent)} for API compatibility.
	 * Default implementation delegates to onEslEvent.
	 *
	 * @param context the context for sending commands
	 * @param event the ESL event
	 */
	default void handleEslEvent(Context context, EslEvent event) {
		onEslEvent(context, event);
	}
}
