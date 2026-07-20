package me.pewa.event;

public class PacketSendEvent extends Event {
    private final Object packet;

    public PacketSendEvent(Object packet) {
        this.packet = packet;
    }

    public Object getPacket() {
        return packet;
    }
}
