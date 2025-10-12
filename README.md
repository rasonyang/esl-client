# esl-client

**Java 21 Virtual Threads** Event Socket Library for [FreeSWITCH](https://freeswitch.org/)

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Gradle](https://img.shields.io/badge/Gradle-9.1-blue.svg)](https://gradle.org/)
[![License](https://img.shields.io/badge/License-Apache%202.0-green.svg)](LICENSE)

## Overview

**esl-client** is a modern Java-based Event Socket Library for FreeSWITCH, completely rewritten to leverage **Java 21 Virtual Threads (Project Loom)** for maximum performance and scalability.

This version removes all Netty dependencies and uses native Java Sockets with Virtual Threads, providing:

- ✅ **Zero external networking dependencies** - Pure Java implementation
- ✅ **Massive scalability** - Handle millions of concurrent connections with Virtual Threads
- ✅ **Simplified architecture** - Straightforward blocking I/O model that's easy to understand
- ✅ **High performance** - Virtual threads provide excellent throughput with minimal overhead
- ✅ **Modern Java** - Takes full advantage of Java 21 features

This project is a fork of the original unmaintained project at:
<https://github.com/esl-client/esl-client>

## Requirements

- **Java 21 or higher** (for Virtual Threads support)
- **Gradle 9.1+** (for building)
- FreeSWITCH server with Event Socket module enabled

## Quick Start

### Inbound Connection (Client Mode)

Connect to FreeSWITCH and receive events:

```java
import org.freeswitch.esl.client.inbound.Client;
import org.freeswitch.esl.client.internal.IModEslApi.EventFormat;

import java.net.InetSocketAddress;

public class InboundExample {
    public static void main(String[] args) throws Exception {
        // Create client (uses virtual threads internally)
        Client client = new Client();

        // Add event listener
        client.addEventListener((ctx, event) -> {
            System.out.println("Event: " + event.getEventName());
        });

        // Connect to FreeSWITCH
        client.connect(new InetSocketAddress("localhost", 8021), "ClueCon", 10);

        // Subscribe to events
        client.setEventSubscriptions(EventFormat.PLAIN, "all");

        // Keep running
        Thread.currentThread().join();
    }
}
```

### Filtered Event Listeners

Subscribe to specific event types only (client-side filtering):

```java
import org.freeswitch.esl.client.inbound.Client;
import org.freeswitch.esl.client.internal.IModEslApi.EventFormat;

import java.net.InetSocketAddress;

public class FilteredListenerExample {
    public static void main(String[] args) throws Exception {
        Client client = new Client();

        // Listen only to channel creation events
        client.addEventListener((ctx, event) -> {
            System.out.println("Channel created: " + event.getEventHeaders().get("Unique-ID"));
        }, "CHANNEL_CREATE");

        // Listen only to hangup events
        client.addEventListener((ctx, event) -> {
            System.out.println("Channel hung up: " + event.getEventHeaders().get("Unique-ID"));
        }, "CHANNEL_HANGUP");

        // Global listener receives all events
        client.addEventListener((ctx, event) -> {
            System.out.println("All events: " + event.getEventName());
        });

        // Connect and subscribe
        client.connect(new InetSocketAddress("localhost", 8021), "ClueCon", 10);
        client.setEventSubscriptions(EventFormat.PLAIN, "all");

        // Remove a specific listener when done
        // client.removeEventListener(listener);

        Thread.currentThread().join();
    }
}
```

### Automatic Server Subscription Optimization

Enable automatic server-side filtering to reduce network bandwidth:

```java
import org.freeswitch.esl.client.inbound.Client;

import java.net.InetSocketAddress;

public class AutoOptimizationExample {
    public static void main(String[] args) throws Exception {
        Client client = new Client();

        // Enable automatic server subscription optimization
        client.setAutoUpdateServerSubscription(true);

        // Connect to FreeSWITCH
        client.connect(new InetSocketAddress("localhost", 8021), "ClueCon", 10);

        // Add filtered listeners
        client.addEventListener((ctx, event) -> {
            System.out.println("Channel created");
        }, "CHANNEL_CREATE");
        // Auto-sends: event plain CHANNEL_CREATE

        client.addEventListener((ctx, event) -> {
            System.out.println("Channel hangup");
        }, "CHANNEL_HANGUP");
        // Auto-sends: event plain CHANNEL_CREATE CHANNEL_HANGUP

        // Add global listener
        client.addEventListener((ctx, event) -> {
            System.out.println("All events");
        });
        // Auto-sends: event plain all

        // Remove global listener
        client.removeEventListener(globalListener);
        // Auto-sends: event plain CHANNEL_CREATE CHANNEL_HANGUP

        Thread.currentThread().join();
    }
}
```

**How it works:**
- Calculates the union of all listener event filters
- If any global listener exists (no filter) → subscribes to "all"
- Otherwise → subscribes only to events that listeners need
- Automatically updates when listeners are added/removed
- Reduces network traffic from FreeSWITCH

### Automatic Reconnection with Heartbeat Monitoring

Production-ready client with automatic reconnection on connection failure:

```java
import org.freeswitch.esl.client.inbound.ReconnectableClient;
import org.freeswitch.esl.client.inbound.ReconnectionConfig;
import org.freeswitch.esl.client.internal.IModEslApi.EventFormat;

import java.net.InetSocketAddress;

public class ReconnectableExample {
    public static void main(String[] args) throws Exception {
        // Create reconnectable client with default config
        // Default: 60s heartbeat timeout, 10s health check, exponential backoff
        ReconnectableClient client = new ReconnectableClient();

        // Or use custom configuration
        ReconnectionConfig config = new ReconnectionConfig();
        config.setHeartbeatTimeoutMs(40_000);  // 40 seconds (fast detection)
        config.setHealthCheckIntervalMs(10_000); // Check every 10 seconds
        // ReconnectableClient client = new ReconnectableClient(config);

        // Or use preset configurations
        // ReconnectableClient client = new ReconnectableClient(ReconnectionConfig.fastDetection());
        // ReconnectableClient client = new ReconnectableClient(ReconnectionConfig.tolerant());

        // Add business event listeners (preserved across reconnections)
        client.addEventListener((ctx, event) -> {
            System.out.println("Channel created: " + event.getEventHeaders().get("Unique-ID"));
        }, "CHANNEL_CREATE");

        client.addEventListener((ctx, event) -> {
            System.out.println("Channel hung up");
        }, "CHANNEL_HANGUP");

        // Connect (automatically subscribes to HEARTBEAT internally)
        client.connect(new InetSocketAddress("localhost", 8021), "ClueCon", 10);

        // Set business event subscriptions (preserved across reconnections)
        client.setEventSubscriptions(EventFormat.PLAIN, "CHANNEL_CREATE CHANNEL_HANGUP");

        // Client will automatically:
        // - Monitor HEARTBEAT events (FreeSWITCH sends every 20 seconds)
        // - Detect connection failures (no heartbeat for 60 seconds)
        // - Reconnect with exponential backoff (1s, 2s, 4s, 8s, ... up to 60s)
        // - Restore all listeners and subscriptions after reconnection

        Thread.currentThread().join();
    }
}
```

**Features:**
- **HEARTBEAT Monitoring**: Tracks FreeSWITCH HEARTBEAT events (sent every 20 seconds)
- **Automatic Detection**: Detects connection failures when heartbeat timeout is exceeded
- **Exponential Backoff**: Retries with increasing delays (1s → 2s → 4s → 8s → ... → 60s max)
- **Jitter**: Adds randomization (±10%) to prevent thundering herd
- **State Preservation**: Automatically restores listeners and subscriptions after reconnection
- **Virtual Threads**: All background tasks use lightweight virtual threads

**Configuration Options:**

| Parameter | Default | Fast Detection | Tolerant | Description |
|-----------|---------|----------------|----------|-------------|
| Heartbeat Timeout | 60s (3x) | 40s (2x) | 90s (4.5x) | Max time without heartbeat before reconnecting |
| Health Check Interval | 10s | 10s | 15s | How often to check for timeout |
| Initial Reconnect Delay | 1s | 500ms | 1s | First retry delay |
| Max Reconnect Delay | 60s | 60s | 120s | Maximum retry delay |
| Reconnect Multiplier | 2.0 | 2.0 | 2.0 | Exponential backoff multiplier |
| Jitter Factor | 0.2 (±10%) | 0.2 | 0.2 | Randomization to avoid thundering herd |

**How it works:**
```
FreeSWITCH HEARTBEAT:  0s    20s    40s    60s    80s    100s
                       ↓     ↓      ↓      ✗      ✗      ✗
Client receives:       ✓     ✓      ✓
                             ↑                           ↑
                       lastHeartbeat              Timeout detected
                       (40s)                      (100s - 40s = 60s)

Health Check (10s):    10s   20s    30s    40s    50s    60s    70s    80s    90s    100s
                       ✓     ✓      ✓      ✓      ✓      ✓      ✓      ✓      ✓      ✗
                                                                                     Triggers
                                                                                     Reconnect

Reconnection Attempts: Wait 1s → Attempt 1 → Fail → Wait 2s → Attempt 2 → Fail → Wait 4s → Attempt 3 → Success
                                                              Exponential backoff with jitter
```

### Outbound Connection (Server Mode)

Accept connections from FreeSWITCH:

```java
import org.freeswitch.esl.client.outbound.IClientHandler;
import org.freeswitch.esl.client.outbound.SocketClient;
import org.freeswitch.esl.client.internal.Context;

import java.net.InetSocketAddress;

public class OutboundExample {
    public static void main(String[] args) throws Exception {
        // Create outbound server (uses virtual threads for each connection)
        SocketClient server = new SocketClient(
            new InetSocketAddress("localhost", 8084),
            () -> new IClientHandler() {
                @Override
                public void onConnect(Context context, EslEvent event) {
                    System.out.println("New call connected!");
                    // Handle the call...
                }

                @Override
                public void onEslEvent(Context context, EslEvent event) {
                    System.out.println("Event: " + event.getEventName());
                }
            }
        );

        // Start server
        server.start();

        System.out.println("Outbound server listening on port 8084");
        Thread.currentThread().join();
    }
}
```

## Building

### Using Gradle

```bash
# Build the project
gradle clean build

# Skip tests
gradle build -x test

# Build JAR
gradle jar
```

### Using SDKMAN for Gradle

```bash
# Install Gradle 9.1
sdk install gradle 9.1.0

# Build
gradle clean build
```

## Virtual Threads Architecture

This library makes extensive use of Java 21 Virtual Threads with **guaranteed event ordering**:

### Inbound Mode (Client)

**Message Processing**:
- 1 virtual thread per connection for reading ESL messages

**Event Processing with Ordering**:
- Each Channel UUID gets its own single-threaded virtual thread executor
- Events for the same channel are processed **sequentially** (preserves order)
- Different channels process **concurrently** (maximum throughput)
- Events without Channel UUID (HEARTBEAT, etc.) use shared thread pool

```java
// Per-channel executor ensures sequential processing
ExecutorService channelExecutor = channelExecutors.computeIfAbsent(
    channelUuid,
    uuid -> Executors.newSingleThreadExecutor(Thread.ofVirtual().factory())
);
```

**Critical**: This ensures channel state transitions maintain correct order:
```
CHANNEL_CREATE → CHANNEL_ANSWER → CHANNEL_EXECUTE → CHANNEL_HANGUP
```

### Outbound Mode (Server)

**Connection Handling**:
- 1 virtual thread for accepting connections
- 1 virtual thread per FreeSWITCH connection for reading messages

**Event Processing with Ordering**:
- Each connection gets its own single-threaded virtual thread executor
- Events are processed **sequentially** to preserve order
- Virtual thread overhead is minimal (~1KB per thread)

```java
// Single-threaded executor per connection ensures event ordering
private final ExecutorService eventExecutor =
    Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
```

## Performance Benefits

**Virtual Threads** provide massive advantages over traditional threading models:

- **Low Memory Overhead**: Each virtual thread uses only ~1KB of memory (vs ~1MB for platform threads)
- **Millions of Threads**: Can handle millions of concurrent connections on commodity hardware
- **Simple Code**: Write blocking I/O code that performs like async code
- **JVM Optimized**: Automatic scheduling and management by the JVM

**Removed Dependencies**:
- ✅ No Netty (removed ~15k lines of dependency code)
- ✅ Smaller JAR size (~52KB)
- ✅ Faster startup time
- ✅ Easier to debug and maintain

## API Changes from Previous Versions

### Breaking Changes

1. **Java Version**: Requires Java 21+ (was Java 8)
2. **SocketClient.start()**: Changed from `startAsync()` to `start()` (removed Guava dependency)

### Compatible APIs

All core APIs remain the same:
- `Client.connect()`
- `Client.addEventListener()` - now with optional event type filtering
- `Client.addEventListener(listener, eventNames...)` - new overload for filtered listeners
- `Client.removeEventListener()` - new method to remove specific listeners
- `Client.setAutoUpdateServerSubscription(boolean)` - new method for auto-optimization
- `Client.setEventSubscriptions()`
- All ESL commands and message handling

## FreeSWITCH Configuration

### Event Socket Configuration

In `conf/autoload_configs/event_socket.conf.xml`:

```xml
<configuration name="event_socket.conf" description="Socket Client">
  <settings>
    <param name="nat-map" value="false"/>
    <param name="listen-ip" value="127.0.0.1"/>
    <param name="listen-port" value="8021"/>
    <param name="password" value="ClueCon"/>
  </settings>
</configuration>
```

## Testing

Run the included test to verify your setup:

```bash
# Compile and run test
gradle compileTestJava
java -cp build/classes/java/main:build/classes/java/test:<dependencies> \
     SimpleTest ClueCon
```

## Contributing

Contributions are welcome! Please feel free to submit pull requests.

## License

**esl-client** is licensed under the [Apache License, version 2](LICENSE).

## Additional Resources

- [FreeSWITCH Event Socket Documentation](https://developer.signalwire.com/freeswitch/FreeSWITCH-Explained/Client-and-Developer-Interfaces/Event-Socket-Library/)
- [Java 21 Virtual Threads Guide](https://docs.oracle.com/en/java/javase/21/core/virtual-threads.html)

---

**Powered by Java 21 Virtual Threads** 🚀
