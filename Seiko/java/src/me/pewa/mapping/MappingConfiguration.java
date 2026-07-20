package me.pewa.mapping;

public final class MappingConfiguration {
    private final boolean mapNullValues;
    private final boolean ignoreMissingProperties;
    private final boolean failOnMappingError;
    private final boolean nestedMappingEnabled;
    private final boolean debugLoggingEnabled;
    private final int maxDepth;
    private final TypeConverterRegistry typeConverterRegistry;

    private MappingConfiguration(Builder builder) {
        this.mapNullValues = builder.mapNullValues;
        this.ignoreMissingProperties = builder.ignoreMissingProperties;
        this.failOnMappingError = builder.failOnMappingError;
        this.nestedMappingEnabled = builder.nestedMappingEnabled;
        this.debugLoggingEnabled = builder.debugLoggingEnabled;
        this.maxDepth = builder.maxDepth;
        this.typeConverterRegistry = builder.typeConverterRegistry == null
                ? TypeConverterRegistry.standard()
                : builder.typeConverterRegistry;
    }

    public static MappingConfiguration defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
                .mapNullValues(mapNullValues)
                .ignoreMissingProperties(ignoreMissingProperties)
                .failOnMappingError(failOnMappingError)
                .nestedMappingEnabled(nestedMappingEnabled)
                .debugLoggingEnabled(debugLoggingEnabled)
                .maxDepth(maxDepth)
                .typeConverterRegistry(typeConverterRegistry);
    }

    public boolean isMapNullValues() {
        return mapNullValues;
    }

    public boolean isIgnoreMissingProperties() {
        return ignoreMissingProperties;
    }

    public boolean isFailOnMappingError() {
        return failOnMappingError;
    }

    public boolean isNestedMappingEnabled() {
        return nestedMappingEnabled;
    }

    public boolean isDebugLoggingEnabled() {
        return debugLoggingEnabled;
    }

    public int getMaxDepth() {
        return maxDepth;
    }

    public TypeConverterRegistry getTypeConverterRegistry() {
        return typeConverterRegistry;
    }

    public static final class Builder {
        private boolean mapNullValues;
        private boolean ignoreMissingProperties = true;
        private boolean failOnMappingError;
        private boolean nestedMappingEnabled = true;
        private boolean debugLoggingEnabled;
        private int maxDepth = 32;
        private TypeConverterRegistry typeConverterRegistry = TypeConverterRegistry.standard();

        private Builder() {
        }

        public Builder mapNullValues(boolean mapNullValues) {
            this.mapNullValues = mapNullValues;
            return this;
        }

        public Builder ignoreMissingProperties(boolean ignoreMissingProperties) {
            this.ignoreMissingProperties = ignoreMissingProperties;
            return this;
        }

        public Builder failOnMappingError(boolean failOnMappingError) {
            this.failOnMappingError = failOnMappingError;
            return this;
        }

        public Builder nestedMappingEnabled(boolean nestedMappingEnabled) {
            this.nestedMappingEnabled = nestedMappingEnabled;
            return this;
        }

        public Builder debugLoggingEnabled(boolean debugLoggingEnabled) {
            this.debugLoggingEnabled = debugLoggingEnabled;
            return this;
        }

        public Builder maxDepth(int maxDepth) {
            this.maxDepth = Math.max(1, maxDepth);
            return this;
        }

        public Builder typeConverterRegistry(TypeConverterRegistry typeConverterRegistry) {
            if (typeConverterRegistry == null) {
                throw new IllegalArgumentException("typeConverterRegistry cannot be null");
            }
            this.typeConverterRegistry = typeConverterRegistry;
            return this;
        }

        public MappingConfiguration build() {
            return new MappingConfiguration(this);
        }
    }
}
