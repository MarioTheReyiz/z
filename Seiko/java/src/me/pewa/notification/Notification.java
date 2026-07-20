package me.pewa.notification;

public class Notification {
    private final String title;
    private final String message;
    private final Type type;
    private final long startTime;
    private final long duration;

    public Notification(String title, String message, Type type, long duration) {
        this.title     = title;
        this.message   = message;
        this.type      = type;
        this.duration  = duration;
        this.startTime = System.currentTimeMillis();
    }

    public String getTitle()     { return title;     }
    public String getMessage()   { return message;   }
    public Type   getType()      { return type;      }
    public long   getStartTime() { return startTime; }
    public long   getDuration()  { return duration;  }

    public float getProgress() {
        return Math.min(1f, (System.currentTimeMillis() - startTime) / (float) duration);
    }

    public boolean isFinished() {
        return System.currentTimeMillis() - startTime > duration;
    }

    public enum Type {
        ENABLED, DISABLED, INFO
    }
}
