package me.pewa.event;

public class PacketReceiveEvent extends Event {
    private final Object packet;

    public PacketReceiveEvent(Object packet) {
        this.packet = packet;
    }

    public Object getPacket() {
        return packet;
    }
}
