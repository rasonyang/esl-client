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

This library makes extensive use of Java 21 Virtual Threads:

### Message Processing
Each connection spawns a virtual thread that continuously reads and processes ESL messages:

```java
Thread readerThread = Thread.startVirtualThread(() -> messageReaderLoop(socket, handler));
```

### Event Callbacks
All event listeners execute in a virtual thread pool:

```java
ExecutorService callbackExecutor = Executors.newVirtualThreadPerTaskExecutor();
```

### Outbound Connections
Each incoming FreeSWITCH connection is handled in its own virtual thread:

```java
Thread.startVirtualThread(() -> handleClient(clientSocket));
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
- `Client.addEventListener()`
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
