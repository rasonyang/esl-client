package org.freeswitch.esl.client.transport.socket;

import org.freeswitch.esl.client.transport.HeaderParser;
import org.freeswitch.esl.client.transport.message.EslHeaders.Name;
import org.freeswitch.esl.client.transport.message.EslMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;

/**
 * Decoder for ESL protocol messages from an InputStream.
 * This replaces the Netty-based EslFrameDecoder with a blocking,
 * InputStream-based implementation suitable for virtual threads.
 */
public class SocketFrameDecoder {

    private static final Logger log = LoggerFactory.getLogger(SocketFrameDecoder.class);
    private static final byte LF = 10;

    private final int maxHeaderSize;
    private final boolean treatUnknownHeadersAsBody;

    public SocketFrameDecoder(int maxHeaderSize) {
        this(maxHeaderSize, false);
    }

    public SocketFrameDecoder(int maxHeaderSize, boolean treatUnknownHeadersAsBody) {
        if (maxHeaderSize <= 0) {
            throw new IllegalArgumentException("maxHeaderSize must be a positive integer: " + maxHeaderSize);
        }
        this.maxHeaderSize = maxHeaderSize;
        this.treatUnknownHeadersAsBody = treatUnknownHeadersAsBody;
    }

    /**
     * Decode a single ESL message from the input stream.
     * This method blocks until a complete message is read.
     *
     * @param inputStream the input stream to read from
     * @return the decoded EslMessage
     * @throws IOException if an I/O error occurs
     */
    public EslMessage decode(InputStream inputStream) throws IOException {
        EslMessage message = new EslMessage();

        // Read headers until we reach a double line feed
        boolean reachedDoubleLF = false;
        while (!reachedDoubleLF) {
            String headerLine = readLine(inputStream, maxHeaderSize);
            log.debug("read header line [{}]", headerLine);

            if (!headerLine.isEmpty()) {
                // Split the header line
                String[] headerParts = HeaderParser.splitHeader(headerLine);
                Name headerName = Name.fromLiteral(headerParts[0]);

                if (headerName == null) {
                    if (treatUnknownHeadersAsBody) {
                        // Cache this 'header' as a body line (useful for Outbound client mode)
                        message.addBodyLine(headerLine);
                    } else {
                        throw new IllegalStateException("Unhandled ESL header [" + headerParts[0] + ']');
                    }
                } else {
                    message.addHeader(headerName, headerParts[1]);
                }
            } else {
                reachedDoubleLF = true;
            }
        }

        // Check for content-length and read body if present
        if (message.hasContentLength()) {
            int contentLength = message.getContentLength();
            log.debug("have content-length [{}], decoding body ..", contentLength);

            byte[] bodyBytes = new byte[contentLength];
            int totalRead = 0;
            while (totalRead < contentLength) {
                int read = inputStream.read(bodyBytes, totalRead, contentLength - totalRead);
                if (read == -1) {
                    throw new IOException("Unexpected end of stream while reading message body");
                }
                totalRead += read;
            }

            // Parse body lines
            parseBodyLines(message, bodyBytes);
        }

        return message;
    }

    /**
     * Read a line from the input stream until LF character.
     */
    private String readLine(InputStream inputStream, int maxLineLength) throws IOException {
        StringBuilder sb = new StringBuilder(64);
        int b;

        while ((b = inputStream.read()) != -1) {
            if (b == LF) {
                return sb.toString();
            } else {
                if (sb.length() >= maxLineLength) {
                    throw new IOException("ESL line is longer than " + maxLineLength + " bytes.");
                }
                sb.append((char) b);
            }
        }

        // End of stream reached
        if (sb.length() > 0) {
            return sb.toString();
        }
        throw new IOException("Unexpected end of stream");
    }

    /**
     * Parse body bytes into lines and add to message.
     */
    private void parseBodyLines(EslMessage message, byte[] bodyBytes) {
        StringBuilder sb = new StringBuilder();

        for (byte b : bodyBytes) {
            if (b == LF) {
                String bodyLine = sb.toString();
                log.debug("read body line [{}]", bodyLine);
                message.addBodyLine(bodyLine);
                sb = new StringBuilder();
            } else {
                sb.append((char) b);
            }
        }

        // Add any remaining content as the last line
        if (sb.length() > 0) {
            String bodyLine = sb.toString();
            log.debug("read body line [{}]", bodyLine);
            message.addBodyLine(bodyLine);
        }
    }
}
