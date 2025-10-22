# FreeSWITCH Event Socket Setup Guide

This guide helps you configure FreeSWITCH Event Socket Library (ESL) for use with this esl-client library.

## Quick Diagnosis

If you're getting **"Access Denied, go away"** errors, run the diagnostic script:

```bash
chmod +x scripts/check-event-socket.sh
./scripts/check-event-socket.sh
```

The script will automatically detect and explain configuration issues.

## Common Issues and Solutions

### Issue 1: "Access Denied, go away" / text/rude-rejection

**Symptom:**
```
Content-Type: text/rude-rejection
Content-Length: 24
Access Denied, go away.
```

**Cause:** ACL (Access Control List) is blocking your connection, typically when:
- `apply-inbound-acl` is set to `lan`
- You're connecting from `localhost` (127.0.0.1)
- The ACL doesn't include localhost in its definition

**Solution:**

1. Edit the Event Socket configuration file:
   ```bash
   # macOS/Linux (adjust path if needed)
   vim /usr/local/freeswitch/conf/autoload_configs/event_socket.conf.xml

   # Or use your preferred editor
   nano /usr/local/freeswitch/conf/autoload_configs/event_socket.conf.xml
   ```

2. Find the `apply-inbound-acl` line:
   ```xml
   <param name="apply-inbound-acl" value="lan"/>
   ```

3. **Option A** - Allow localhost connections (Recommended for development):
   ```xml
   <param name="apply-inbound-acl" value="loopback.auto"/>
   ```

   **Option B** - Remove ACL restriction entirely (Local testing only):
   ```xml
   <!-- <param name="apply-inbound-acl" value="lan"/> -->
   ```

   **Option C** - Keep `lan` but add localhost to the ACL definition:

   Edit `/usr/local/freeswitch/conf/autoload_configs/acl.conf.xml`:
   ```xml
   <list name="lan" default="allow">
     <node type="allow" cidr="127.0.0.1/32"/>
     <node type="allow" cidr="192.168.0.0/16"/>
     <node type="allow" cidr="10.0.0.0/8"/>
   </list>
   ```

4. Reload the Event Socket module:
   ```bash
   # Method 1: Using fs_cli
   fs_cli -x "reload mod_event_socket"

   # Method 2: From FreeSWITCH console
   # First connect: fs_cli
   # Then run: reload mod_event_socket

   # Method 3: Restart FreeSWITCH (if above doesn't work)
   systemctl restart freeswitch
   # or
   /usr/local/freeswitch/bin/freeswitch -stop
   /usr/local/freeswitch/bin/freeswitch -nc
   ```

5. Verify the fix:
   ```bash
   ./scripts/check-event-socket.sh
   ```

### Issue 2: Connection Refused / Port Not Listening

**Symptom:**
```
Connection refused
```

**Causes:**
- FreeSWITCH is not running
- Event Socket module is not loaded
- Wrong port configured

**Solutions:**

1. Check if FreeSWITCH is running:
   ```bash
   ps aux | grep freeswitch

   # Or check with systemctl
   systemctl status freeswitch
   ```

2. Start FreeSWITCH if needed:
   ```bash
   systemctl start freeswitch
   # or
   /usr/local/freeswitch/bin/freeswitch -nc
   ```

3. Verify Event Socket is listening:
   ```bash
   lsof -i :8021  # or your configured port
   # or
   netstat -an | grep 8021
   ```

4. Check Event Socket module status in FreeSWITCH:
   ```bash
   fs_cli -x "module_exists mod_event_socket"
   ```

5. Load the module if needed:
   ```bash
   fs_cli -x "load mod_event_socket"
   ```

### Issue 3: Authentication Failed

**Symptom:**
```
-ERR invalid
```

**Cause:** Wrong password

**Solution:**

1. Check the configured password in `event_socket.conf.xml`:
   ```bash
   grep password /usr/local/freeswitch/conf/autoload_configs/event_socket.conf.xml
   ```

2. Update your Java code to use the correct password:
   ```java
   client.connect(new InetSocketAddress("localhost", 8021), "YourPassword", 10);
   ```

## Complete Event Socket Configuration

Here's a complete working configuration for **local development**:

### /usr/local/freeswitch/conf/autoload_configs/event_socket.conf.xml

```xml
<configuration name="event_socket.conf" description="Socket Client">
  <settings>
    <param name="nat-map" value="false"/>
    <param name="listen-ip" value="127.0.0.1"/>
    <param name="listen-port" value="8021"/>
    <param name="password" value="ClueCon"/>

    <!-- For LOCAL development: use loopback.auto -->
    <param name="apply-inbound-acl" value="loopback.auto"/>

    <!-- For PRODUCTION: use specific ACLs -->
    <!-- <param name="apply-inbound-acl" value="lan"/> -->

    <!-- Stop FreeSWITCH if module fails to bind -->
    <!-- <param name="stop-on-bind-error" value="true"/> -->
  </settings>
</configuration>
```

**Important Parameters:**

| Parameter | Value | Description |
|-----------|-------|-------------|
| `listen-ip` | `127.0.0.1` | Bind to localhost only (secure for development) |
| `listen-ip` | `0.0.0.0` | Bind to all interfaces (use with ACL in production) |
| `listen-port` | `8021` | Default ESL port |
| `password` | `ClueCon` | Password for authentication (change in production!) |
| `apply-inbound-acl` | `loopback.auto` | Allow localhost connections |
| `apply-inbound-acl` | `lan` | Allow only LAN connections |
| `apply-inbound-acl` | (not set) | Allow all connections (insecure!) |

## Dialplan Configuration for Outbound Mode

To test the outbound server example, add this to your dialplan:

### /usr/local/freeswitch/conf/dialplan/default.xml

Add inside the `<context>` block:

```xml
<!-- Outbound ESL Test -->
<extension name="outbound_test">
  <condition field="destination_number" expression="^9999$">
    <action application="socket" data="localhost:8084 async full"/>
  </condition>
</extension>
```

**Parameters:**
- `localhost:8084` - Where your outbound server is running
- `async` - Asynchronous mode (FreeSWITCH continues after connecting)
- `full` - Send full channel data in the initial message

Reload dialplan:
```bash
fs_cli -x "reloadxml"
```

## Testing Your Configuration

### Test 1: Manual Connection Test

```bash
# Try connecting with netcat
echo "auth ClueCon" | nc localhost 8021

# Expected response:
# Content-Type: auth/request
# (followed by)
# Content-Type: command/reply
# Reply-Text: +OK accepted
```

### Test 2: Run Diagnostic Script

```bash
./scripts/check-event-socket.sh
```

Should show all green checkmarks.

### Test 3: Run Example Programs

#### Inbound Client Test:
```bash
gradle test --tests SimpleTest
```

#### Outbound Server Test:
```bash
# Terminal 1: Start outbound server
gradle runOutboundExample

# Terminal 2: Make test call
fs_cli -x "originate user/1000 9999"
# Or dial 9999 from a registered SIP phone
```

## Security Recommendations

### Development Environment
- ✅ Use `listen-ip: 127.0.0.1` (localhost only)
- ✅ Use `apply-inbound-acl: loopback.auto`
- ⚠️ Default password "ClueCon" is acceptable

### Production Environment
- ✅ Use `apply-inbound-acl: lan` or specific ACL
- ✅ Change default password to something strong
- ✅ Use firewall rules to protect port 8021
- ✅ Consider using TLS/SSL (advanced)
- ❌ Never use `listen-ip: 0.0.0.0` without ACL

## Troubleshooting Commands

```bash
# Check FreeSWITCH status
fs_cli -x "status"

# Check loaded modules
fs_cli -x "module_exists mod_event_socket"

# Reload Event Socket
fs_cli -x "reload mod_event_socket"

# Check active calls
fs_cli -x "show calls"

# Watch events in real-time
fs_cli -x "events plain all"

# Check FreeSWITCH logs
tail -f /usr/local/freeswitch/log/freeswitch.log
```

## Quick Reference: File Locations

| Item | Path |
|------|------|
| Event Socket Config | `/usr/local/freeswitch/conf/autoload_configs/event_socket.conf.xml` |
| ACL Config | `/usr/local/freeswitch/conf/autoload_configs/acl.conf.xml` |
| Dialplan | `/usr/local/freeswitch/conf/dialplan/default.xml` |
| Main Config | `/usr/local/freeswitch/conf/freeswitch.xml` |
| Logs | `/usr/local/freeswitch/log/freeswitch.log` |
| CLI Tool | `/usr/local/freeswitch/bin/fs_cli` |

**Note:** Paths may vary depending on your installation method. Common alternatives:
- Docker: `/etc/freeswitch/`
- Debian/Ubuntu package: `/etc/freeswitch/`
- Custom compile: Your specified prefix

## Getting Help

If you're still having issues:

1. Run the diagnostic script and save the output:
   ```bash
   ./scripts/check-event-socket.sh > diagnostic.txt
   ```

2. Check FreeSWITCH logs:
   ```bash
   tail -100 /usr/local/freeswitch/log/freeswitch.log
   ```

3. Enable debug logging in FreeSWITCH:
   ```bash
   fs_cli -x "console loglevel debug"
   ```

4. Consult the official documentation:
   - [FreeSWITCH Event Socket](https://developer.signalwire.com/freeswitch/FreeSWITCH-Explained/Client-and-Developer-Interfaces/Event-Socket-Library/)
   - [mod_event_socket](https://developer.signalwire.com/freeswitch/FreeSWITCH-Explained/Modules/mod_event_socket_1048924/)
