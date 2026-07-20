package me.pewa.util.animation;

public class TimerUtil {
    public long lastMS = System.currentTimeMillis();

    public void reset() {
        this.lastMS = System.currentTimeMillis();
    }

    public boolean hasTimeElapsed(long time) {
        return System.currentTimeMillis() - this.lastMS > time;
    }

    public long getTime() {
        return System.currentTimeMillis() - this.lastMS;
    }

    public void setTime(long time) {
        this.lastMS = time;
    }
}
