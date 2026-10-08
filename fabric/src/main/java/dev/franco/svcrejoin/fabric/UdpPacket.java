package dev.franco.svcrejoin.fabric;

import de.maxhenkel.voicechat.api.RawUdpPacket;

import java.net.SocketAddress;

final class UdpPacket implements RawUdpPacket {

    private final byte[] data;
    private final SocketAddress address;
    private final long timestamp;

    UdpPacket(byte[] data, SocketAddress address, long timestamp) {
        this.data = data;
        this.address = address;
        this.timestamp = timestamp;
    }

    @Override
    public byte[] getData() {
        return data;
    }

    @Override
    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public SocketAddress getSocketAddress() {
        return address;
    }
}
