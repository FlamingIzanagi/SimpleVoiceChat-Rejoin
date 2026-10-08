package dev.franco.svcrejoin.fabric;

import de.maxhenkel.voicechat.api.ClientVoicechatSocket;
import de.maxhenkel.voicechat.api.RawUdpPacket;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;

/**
 * UDP socket that, after a connection has fully succeeded, rebinds that same local port the next
 * time Simple Voice Chat opens one. Docker keeps answering the original flow; a random new port
 * never receives the authentication ack.
 *
 * <p>A port is remembered only when the voice connection reaches the connected state. A join that
 * never connects keeps using a fresh port, the same as the stock client.</p>
 */
final class ReusePortVoicechatSocket implements ClientVoicechatSocket {

    private static final int BUFFER_SIZE = 4096;

    /** Local port of the last voice connection that finished the handshake. {@code 0} if none yet. */
    private static volatile int provenPort;
    /** Socket currently open. The connected-event handler reads its port. */
    private static volatile ReusePortVoicechatSocket current;

    private final byte[] buffer = new byte[BUFFER_SIZE];
    private DatagramSocket socket;

    static void markCurrentProven() {
        ReusePortVoicechatSocket open = current;
        if (open != null) {
            open.markProven();
        }
    }

    @Override
    public void open() throws Exception {
        int reuse = provenPort;
        if (reuse > 0) {
            try {
                socket = bind(reuse);
                current = this;
                System.out.println("[SVCRejoin] Reusing the saved voice UDP port");
                return;
            } catch (SocketException e) {
                System.out.println("[SVCRejoin] Could not reuse the saved voice UDP port; opening a new one");
            }
        }
        socket = bind(0);
        current = this;
        System.out.println("[SVCRejoin] Opened a voice UDP port");
    }

    private void markProven() {
        DatagramSocket open = socket;
        if (open == null || open.isClosed()) {
            return;
        }
        int port = open.getLocalPort();
        if (port <= 0) {
            return;
        }
        provenPort = port;
        System.out.println("[SVCRejoin] Voice connected; the UDP port will be reused on rejoin");
    }

    @Override
    public RawUdpPacket read() throws Exception {
        DatagramSocket open = socket;
        if (open == null) {
            throw new IllegalStateException("Socket not opened yet");
        }
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        open.receive(packet);
        if (packet.getLength() >= buffer.length) {
            throw new java.io.IOException("Voice chat packet is too large");
        }
        byte[] data = new byte[packet.getLength()];
        System.arraycopy(packet.getData(), packet.getOffset(), data, 0, packet.getLength());
        return new UdpPacket(data, packet.getSocketAddress(), System.currentTimeMillis());
    }

    @Override
    public void send(byte[] data, SocketAddress address) throws Exception {
        DatagramSocket open = socket;
        if (open == null) {
            return;
        }
        open.send(new DatagramPacket(data, data.length, address));
    }

    @Override
    public void close() {
        DatagramSocket open = socket;
        socket = null;
        if (current == this) {
            current = null;
        }
        if (open != null) {
            open.close();
        }
    }

    @Override
    public boolean isClosed() {
        DatagramSocket open = socket;
        return open == null || open.isClosed();
    }

    private static DatagramSocket bind(int port) throws SocketException {
        DatagramSocket socket = new DatagramSocket(null);
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(port));
        return socket;
    }
}
