package com.taxpilot.documents.scan;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Talks to clamd over TCP using the INSTREAM command. Fails closed. */
public class ClamAvScanner implements MalwareScanner {

    private static final int CHUNK = 64 * 1024;

    private final String host;
    private final int port;
    private final int timeoutMs;

    public ClamAvScanner(String host, int port, Duration timeout) {
        this.host = host;
        this.port = port;
        this.timeoutMs = (int) timeout.toMillis();
    }

    @Override
    public Result scan(byte[] data) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            OutputStream out = socket.getOutputStream();
            out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            for (int offset = 0; offset < data.length; offset += CHUNK) {
                int len = Math.min(CHUNK, data.length - offset);
                out.write(ByteBuffer.allocate(4).putInt(len).array());
                out.write(data, offset, len);
            }
            out.write(new byte[]{0, 0, 0, 0});
            out.flush();
            String reply = readReply(socket.getInputStream());
            if (reply.endsWith("OK")) {
                return new Result(Verdict.CLEAN, null);
            }
            if (reply.endsWith("FOUND")) {
                String sig = reply.replaceFirst("^stream:\\s*", "").replaceFirst("\\s*FOUND$", "");
                return new Result(Verdict.INFECTED, sig);
            }
            throw new ScannerUnavailableException("Unexpected scanner reply", null);
        } catch (IOException e) {
            throw new ScannerUnavailableException("Malware scanner unreachable", e);
        }
    }

    private static String readReply(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1 && b != 0) {
            buf.write(b);
            if (buf.size() > 4096) {
                break;
            }
        }
        return buf.toString(StandardCharsets.US_ASCII).trim();
    }
}
