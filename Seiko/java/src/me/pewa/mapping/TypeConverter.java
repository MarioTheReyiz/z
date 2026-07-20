package me.pewa.mapping;

public interface TypeConverter<S, T> {
    boolean supports(Class<?> sourceType, Class<?> targetType);

    T convert(S source, Class<T> targetType);
}
