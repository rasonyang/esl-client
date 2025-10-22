import org.freeswitch.esl.client.inbound.Client;
import org.freeswitch.esl.client.internal.Context;
import org.freeswitch.esl.client.internal.IModEslApi.EventFormat;
import org.freeswitch.esl.client.outbound.IClientHandler;
import org.freeswitch.esl.client.outbound.IClientHandlerFactory;
import org.freeswitch.esl.client.outbound.SocketClient;
import org.freeswitch.esl.client.transport.event.EslEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

/**
 * API compatibility example matching https://github.com/esl-client/esl-client
 *
 * This example demonstrates the exact API from the reference implementation:
 * - Client with addEventListener
 * - SocketClient with IClientHandlerFactory
 * - IClientHandler with handleEslEvent and onConnect
 * - Built-in automatic reconnection (enabled by default in Client)
 */
public class CompatibleApiExample {

    private static final Logger logger = LoggerFactory.getLogger(CompatibleApiExample.class);

    public static void main(String[] args) {
        basicInboundExample();
        customReconnectionExample();
        outboundExample();
    }

    /**
     * Example 1: Basic inbound client with automatic reconnection (default behavior)
     *
     * The Client class includes automatic reconnection by default:
     * - Monitors HEARTBEAT events from FreeSWITCH
     * - Automatically reconnects on connection failure
     * - Preserves listeners and subscriptions across reconnections
     */
    public static void basicInboundExample() {
        try {
            // Create inbound client (reconnection enabled by default)
            final Client inboundClient = new Client();
            inboundClient.connect(new InetSocketAddress("localhost", 8022), "ClueCon", 10);

            // Add event listener (preserved across reconnections)
            inboundClient.addEventListener((ctx, event) -> {
                logger.info("Event received: {}", event.getEventName());
            });

            // Subscribe to all events (preserved across reconnections)
            inboundClient.setEventSubscriptions(EventFormat.PLAIN, "all");

            logger.info("Inbound client running with automatic reconnection enabled");

        } catch (Throwable t) {
            logger.error("Error in basic inbound example", t);
        }
    }

    /**
     * Example 2: Client with custom reconnection settings or disabled reconnection
     */
    public static void customReconnectionExample() {
        try {
            // Option 1: Disable reconnection
            final Client noReconnectClient = new Client();
            noReconnectClient.setReconnectable(false);
            noReconnectClient.connect(new InetSocketAddress("localhost", 8022), "ClueCon", 10);

            logger.info("Client running WITHOUT automatic reconnection");

            // Option 2: Enable with custom config
            // final Client customClient = new Client();
            // customClient.setReconnectionConfig(ReconnectionConfig.fastDetection());
            // customClient.connect(new InetSocketAddress("localhost", 8022), "ClueCon", 10);

        } catch (Throwable t) {
            logger.error("Error in custom reconnection example", t);
        }
    }

    /**
     * Example 3: Outbound server (matches reference API exactly)
     */
    public static void outboundExample() {
        try {
            // Create outbound server with factory (matches reference API)
            final SocketClient outboundServer = new SocketClient(
                new InetSocketAddress("localhost", 8084),
                new IClientHandlerFactory() {
                    @Override
                    public IClientHandler createClientHandler() {
                        return new IClientHandler() {
                            @Override
                            public void handleEslEvent(Context context, EslEvent eslEvent) {
                                // This method is compatible with reference API
                                logger.info("Outbound event: {}", eslEvent.getEventName());
                            }

                            @Override
                            public void onConnect(Context context, EslEvent eslEvent) {
                                logger.info("Outbound connection established");

                                // Example: answer and play
                                try {
                                    context.sendMessage(createAnswerCommand());
                                    Thread.sleep(500);
                                    context.sendMessage(createPlaybackCommand());
                                } catch (Exception e) {
                                    logger.error("Error handling call", e);
                                }
                            }

                            @Override
                            public void onEslEvent(Context ctx, EslEvent event) {
                                // Also available - same as handleEslEvent
                                handleEslEvent(ctx, event);
                            }
                        };
                    }
                });

            outboundServer.start();
            logger.info("Outbound server running on port 8084");

        } catch (Throwable t) {
            logger.error("Error in outbound example", t);
        }
    }

    /**
     * Example 4: Compact lambda syntax (modern Java style)
     */
    public static void compactExample() {
        try {
            // Inbound with lambda (automatic reconnection enabled by default)
            final Client client = new Client();
            client.connect(new InetSocketAddress("localhost", 8022), "ClueCon", 10);
            client.addEventListener((ctx, event) ->
                logger.info("Event: {}", event.getEventName()));
            client.setEventSubscriptions(EventFormat.PLAIN, "all");

            // Outbound with lambda
            final SocketClient server = new SocketClient(
                new InetSocketAddress("localhost", 8084),
                () -> new IClientHandler() {
                    @Override
                    public void onConnect(Context context, EslEvent eslEvent) {
                        logger.info("Connected");
                    }

                    @Override
                    public void onEslEvent(Context ctx, EslEvent event) {
                        logger.info("Event: {}", event.getEventName());
                    }
                });

            server.start();

        } catch (Throwable t) {
            logger.error("Error in compact example", t);
        }
    }

    // Helper methods
    private static org.freeswitch.esl.client.transport.SendMsg createAnswerCommand() {
        org.freeswitch.esl.client.transport.SendMsg msg = new org.freeswitch.esl.client.transport.SendMsg();
        msg.addCallCommand("execute");
        msg.addExecuteAppName("answer");
        return msg;
    }

    private static org.freeswitch.esl.client.transport.SendMsg createPlaybackCommand() {
        org.freeswitch.esl.client.transport.SendMsg msg = new org.freeswitch.esl.client.transport.SendMsg();
        msg.addCallCommand("execute");
        msg.addExecuteAppName("playback");
        msg.addExecuteAppArg("$${hold_music}");
        return msg;
    }
}
