#!/bin/bash

# FreeSWITCH Event Socket Connection Diagnostic Script
#
# This script helps diagnose Event Socket connection issues by checking:
# - FreeSWITCH process status
# - Event Socket port listening status
# - ACL configuration
# - Connection test
#
# Usage: ./check-event-socket.sh [host] [port] [password]
#   Default: localhost 8022 ClueCon

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Default values
HOST="${1:-localhost}"
PORT="${2:-8022}"
PASSWORD="${3:-ClueCon}"
FS_CLI="${FS_CLI:-fs_cli}"

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}FreeSWITCH Event Socket Diagnostic Tool${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""
echo "Checking connection to: $HOST:$PORT"
echo "Password: $PASSWORD"
echo ""

# Check 1: FreeSWITCH process
echo -e "${YELLOW}[1/5] Checking FreeSWITCH process...${NC}"
if pgrep -x "freeswitch" > /dev/null; then
    echo -e "${GREEN}✓ FreeSWITCH process is running${NC}"
    ps aux | grep freeswitch | grep -v grep | head -1
else
    echo -e "${RED}✗ FreeSWITCH process is NOT running${NC}"
    echo -e "${RED}  Please start FreeSWITCH first${NC}"
    exit 1
fi
echo ""

# Check 2: Event Socket port listening
echo -e "${YELLOW}[2/5] Checking Event Socket port $PORT...${NC}"
if command -v lsof &> /dev/null; then
    if lsof -i :$PORT | grep LISTEN > /dev/null; then
        echo -e "${GREEN}✓ Port $PORT is listening${NC}"
        lsof -i :$PORT | grep LISTEN
    else
        echo -e "${RED}✗ Port $PORT is NOT listening${NC}"
        echo -e "${RED}  Check Event Socket configuration in event_socket.conf.xml${NC}"
    fi
elif command -v netstat &> /dev/null; then
    if netstat -an | grep $PORT | grep LISTEN > /dev/null; then
        echo -e "${GREEN}✓ Port $PORT is listening${NC}"
        netstat -an | grep $PORT | grep LISTEN
    else
        echo -e "${RED}✗ Port $PORT is NOT listening${NC}"
        echo -e "${RED}  Check Event Socket configuration in event_socket.conf.xml${NC}"
    fi
else
    echo -e "${YELLOW}⚠ Cannot check port (lsof/netstat not available)${NC}"
fi
echo ""

# Check 3: Event Socket configuration
echo -e "${YELLOW}[3/5] Checking Event Socket configuration...${NC}"
FS_CONF="/usr/local/freeswitch/conf/autoload_configs/event_socket.conf.xml"
if [ -f "$FS_CONF" ]; then
    echo -e "${GREEN}✓ Configuration file found: $FS_CONF${NC}"

    # Check listen-ip
    LISTEN_IP=$(grep "listen-ip" "$FS_CONF" | sed -n 's/.*value="\([^"]*\)".*/\1/p')
    echo "  listen-ip: $LISTEN_IP"

    # Check listen-port
    LISTEN_PORT=$(grep "listen-port" "$FS_CONF" | sed -n 's/.*value="\([^"]*\)".*/\1/p')
    echo "  listen-port: $LISTEN_PORT"

    # Check password
    CONFIG_PASSWORD=$(grep "password" "$FS_CONF" | sed -n 's/.*value="\([^"]*\)".*/\1/p')
    echo "  password: $CONFIG_PASSWORD"

    # Check ACL
    ACL=$(grep "apply-inbound-acl" "$FS_CONF" | grep -v "<!--" | sed -n 's/.*value="\([^"]*\)".*/\1/p')
    if [ -n "$ACL" ]; then
        echo -e "  ${YELLOW}apply-inbound-acl: $ACL${NC}"
        if [ "$ACL" = "lan" ] && [ "$HOST" = "localhost" -o "$HOST" = "127.0.0.1" ]; then
            echo -e "${RED}  ⚠ WARNING: ACL is set to 'lan' which may block localhost!${NC}"
            echo -e "${RED}     Recommended: Change to 'loopback.auto' or remove ACL restriction${NC}"
            echo -e "${RED}     Edit: $FS_CONF${NC}"
            echo -e "${RED}     Then run: fs_cli -x 'reload mod_event_socket'${NC}"
        fi
    else
        echo -e "${GREEN}  apply-inbound-acl: not set (allows all)${NC}"
    fi
else
    echo -e "${RED}✗ Configuration file not found: $FS_CONF${NC}"
fi
echo ""

# Check 4: ACL definitions
echo -e "${YELLOW}[4/5] Checking ACL definitions...${NC}"
ACL_CONF="/usr/local/freeswitch/conf/autoload_configs/acl.conf.xml"
if [ -f "$ACL_CONF" ]; then
    echo -e "${GREEN}✓ ACL configuration found: $ACL_CONF${NC}"

    # Show LAN ACL if configured
    if [ -n "$ACL" ] && [ "$ACL" = "lan" ]; then
        echo "  Current LAN ACL definition:"
        grep -A 10 'list name="lan"' "$ACL_CONF" | head -15 || echo "  (Could not extract ACL definition)"
    fi
else
    echo -e "${YELLOW}⚠ ACL configuration not found${NC}"
fi
echo ""

# Check 5: Connection test
echo -e "${YELLOW}[5/5] Testing Event Socket connection...${NC}"
if command -v nc &> /dev/null || command -v netcat &> /dev/null; then
    NC_CMD="nc"
    command -v netcat &> /dev/null && NC_CMD="netcat"

    echo "Attempting to connect to $HOST:$PORT..."

    # Try to connect and send auth command
    RESPONSE=$(echo -e "auth $PASSWORD\n\n" | timeout 3 $NC_CMD $HOST $PORT 2>&1 || true)

    if echo "$RESPONSE" | grep -q "Content-Type: auth/request"; then
        echo -e "${GREEN}✓ Received auth request from FreeSWITCH${NC}"

        if echo "$RESPONSE" | grep -q "command/reply"; then
            echo -e "${GREEN}✓ Authentication response received${NC}"

            if echo "$RESPONSE" | grep -q "+OK"; then
                echo -e "${GREEN}✓✓✓ SUCCESS: Authentication successful!${NC}"
            elif echo "$RESPONSE" | grep -q "-ERR"; then
                echo -e "${RED}✗ Authentication failed - check password${NC}"
            fi
        fi
    elif echo "$RESPONSE" | grep -q "rude-rejection"; then
        echo -e "${RED}✗✗✗ CONNECTION REJECTED: Access Denied${NC}"
        echo -e "${RED}     This is the 'Access Denied, go away' error${NC}"
        echo -e "${RED}     CAUSE: ACL restriction is blocking the connection${NC}"
        echo ""
        echo -e "${YELLOW}SOLUTION:${NC}"
        echo -e "  1. Edit: $FS_CONF"
        echo -e "     Change: <param name=\"apply-inbound-acl\" value=\"lan\"/>"
        echo -e "     To:     <param name=\"apply-inbound-acl\" value=\"loopback.auto\"/>"
        echo -e "     Or remove the line entirely"
        echo ""
        echo -e "  2. Reload Event Socket module:"
        echo -e "     fs_cli -x 'reload mod_event_socket'"
        echo ""
        echo -e "  3. Run this script again to verify"
    elif [ -z "$RESPONSE" ]; then
        echo -e "${RED}✗ No response from server - connection refused or timeout${NC}"
    else
        echo -e "${YELLOW}⚠ Unexpected response:${NC}"
        echo "$RESPONSE"
    fi
else
    echo -e "${YELLOW}⚠ netcat not available, cannot test connection${NC}"
    echo "  Install netcat: brew install netcat (macOS) or apt-get install netcat (Linux)"
fi

echo ""
echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}Diagnostic Complete${NC}"
echo -e "${BLUE}========================================${NC}"
