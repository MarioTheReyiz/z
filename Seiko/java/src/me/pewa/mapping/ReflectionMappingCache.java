package me.pewa.mapping;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

final class ReflectionMappingCache {
    private final ConcurrentMap<Key, MappingPlan> plans = new ConcurrentHashMap<>();

    MappingPlan getPlan(Class<?> sourceType, Class<?> targetType) {
        Key key = new Key(sourceType, targetType);
        MappingPlan existing = plans.get(key);
        if (existing != null) {
            return existing;
        }
        MappingPlan created = MappingPlan.build(sourceType, targetType);
        MappingPlan previous = plans.putIfAbsent(key, created);
        return previous == null ? created : previous;
    }

    void clear() {
        plans.clear();
    }

    String stats() {
        return "Object mapping plans: " + plans.size();
    }

    private static final class Key {
        private final Class<?> sourceType;
        private final Class<?> targetType;
        private final int hash;

        private Key(Class<?> sourceType, Class<?> targetType) {
            this.sourceType = sourceType;
            this.targetType = targetType;
            this.hash = 31 * sourceType.hashCode() + targetType.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof Key)) {
                return false;
            }
            Key other = (Key) obj;
            return sourceType == other.sourceType && targetType == other.targetType;
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }
}
