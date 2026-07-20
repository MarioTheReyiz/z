package me.pewa.util.animation;

public abstract class Animation {
    private long lastMS = System.currentTimeMillis();
    protected int duration;
    protected double endPoint;
    protected Direction direction;

    public Animation(int ms, double endPoint) {
        this(ms, endPoint, Direction.FORWARDS);
    }

    public Animation(int ms, double endPoint, Direction direction) {
        this.duration = ms;
        this.endPoint = endPoint;
        this.direction = direction;
    }

    public boolean finished(Direction direction) {
        return isDone() && this.direction == direction;
    }

    public void reset() {
        lastMS = System.currentTimeMillis();
    }

    public boolean isDone() {
        return System.currentTimeMillis() - lastMS > duration;
    }

    public Direction getDirection() {
        return direction;
    }

    public Animation setDirection(Direction direction) {
        if (this.direction != direction) {
            this.direction = direction;
            long elapsed = System.currentTimeMillis() - lastMS;
            long remaining = Math.min(duration, elapsed);
            lastMS = System.currentTimeMillis() - (duration - remaining);
        }
        return this;
    }

    public Double getOutput() {
        long elapsed = System.currentTimeMillis() - lastMS;
        double t = Math.min(1.0, (double) elapsed / duration);

        if (direction.forwards()) {
            if (isDone()) return endPoint;
            return getEquation(t) * endPoint;
        } else {
            if (isDone()) return 0.0;
            return (1.0 - getEquation(t)) * endPoint;
        }
    }

    protected abstract double getEquation(double t);
}
