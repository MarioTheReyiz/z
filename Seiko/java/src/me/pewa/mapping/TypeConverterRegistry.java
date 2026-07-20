package me.pewa.mapping;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class TypeConverterRegistry {
    private final List<TypeConverter<?, ?>> converters = new CopyOnWriteArrayList<>();

    public static TypeConverterRegistry standard() {
        TypeConverterRegistry registry = new TypeConverterRegistry();
        registry.register(new StandardTypeConverter());
        return registry;
    }

    public void register(TypeConverter<?, ?> converter) {
        if (converter == null) {
            throw new IllegalArgumentException("converter cannot be null");
        }
        converters.add(0, converter);
    }

    public Object convert(Object source, Class<?> targetType) {
        if (targetType == null) {
            throw new IllegalArgumentException("targetType cannot be null");
        }
        if (source == null) {
            return null;
        }

        Class<?> wrappedTarget = wrap(targetType);
        if (wrappedTarget.isInstance(source)) {
            return source;
        }

        Class<?> sourceType = source.getClass();
        for (TypeConverter<?, ?> converter : converters) {
            if (!converter.supports(sourceType, wrappedTarget)) {
                continue;
            }
            return convertUnchecked(converter, source, wrappedTarget);
        }

        throw new MappingException("No converter for " + sourceType.getName() + " -> " + wrappedTarget.getName());
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Object convertUnchecked(TypeConverter converter, Object source, Class<?> targetType) {
        return converter.convert(source, targetType);
    }

    public static Class<?> wrap(Class<?> type) {
        if (type == null || !type.isPrimitive()) {
            return type;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == char.class) {
            return Character.class;
        }
        if (type == void.class) {
            return Void.class;
        }
        return type;
    }

    private static final class StandardTypeConverter implements TypeConverter<Object, Object> {
        @Override
        public boolean supports(Class<?> sourceType, Class<?> targetType) {
            return targetType == String.class
                    || Number.class.isAssignableFrom(targetType)
                    || targetType == Boolean.class
                    || targetType == Character.class
                    || targetType.isEnum();
        }

        @Override
        @SuppressWarnings({ "unchecked", "rawtypes" })
        public Object convert(Object source, Class<Object> targetType) {
            Class<?> targetClass = targetType;
            if (source == null) {
                return null;
            }
            if (targetClass.isInstance(source)) {
                return source;
            }
            if (targetClass == String.class) {
                return String.valueOf(source);
            }
            if (targetClass.isEnum()) {
                return convertEnum(source, (Class<? extends Enum>) targetClass);
            }
            if (Number.class.isAssignableFrom(targetClass)) {
                return convertNumber(source, targetClass);
            }
            if (targetClass == Boolean.class) {
                return convertBoolean(source);
            }
            if (targetClass == Character.class) {
                return convertCharacter(source);
            }
            throw new MappingException("Unsupported conversion to " + targetClass.getName());
        }

        private static Object convertEnum(Object source, Class<? extends Enum> enumType) {
            if (source instanceof String) {
                return Enum.valueOf(enumType, ((String) source).trim());
            }
            if (source instanceof Number) {
                Object[] constants = enumType.getEnumConstants();
                int ordinal = ((Number) source).intValue();
                if (ordinal >= 0 && ordinal < constants.length) {
                    return constants[ordinal];
                }
            }
            throw new MappingException("Cannot convert " + source + " to enum " + enumType.getName());
        }

        private static Object convertNumber(Object source, Class<?> targetType) {
            if (source instanceof Number) {
                return narrowNumber((Number) source, targetType);
            }
            if (source instanceof String) {
                String value = ((String) source).trim();
                if (value.length() == 0) {
                    return null;
                }
                return parseNumber(value, targetType);
            }
            if (source instanceof Boolean) {
                return narrowNumber(((Boolean) source) ? Integer.valueOf(1) : Integer.valueOf(0), targetType);
            }
            if (source instanceof Character) {
                return narrowNumber(Integer.valueOf((Character) source), targetType);
            }
            throw new MappingException("Cannot convert " + source.getClass().getName() + " to number");
        }

        private static Object narrowNumber(Number number, Class<?> targetType) {
            if (targetType == Byte.class) {
                return number.byteValue();
            }
            if (targetType == Short.class) {
                return number.shortValue();
            }
            if (targetType == Integer.class) {
                return number.intValue();
            }
            if (targetType == Long.class) {
                return number.longValue();
            }
            if (targetType == Float.class) {
                return number.floatValue();
            }
            if (targetType == Double.class) {
                return number.doubleValue();
            }
            if (targetType == BigInteger.class) {
                return BigInteger.valueOf(number.longValue());
            }
            if (targetType == BigDecimal.class) {
                return BigDecimal.valueOf(number.doubleValue());
            }
            throw new MappingException("Unsupported number target: " + targetType.getName());
        }

        private static Object parseNumber(String value, Class<?> targetType) {
            if (targetType == Byte.class) {
                return Byte.valueOf(value);
            }
            if (targetType == Short.class) {
                return Short.valueOf(value);
            }
            if (targetType == Integer.class) {
                return Integer.valueOf(value);
            }
            if (targetType == Long.class) {
                return Long.valueOf(value);
            }
            if (targetType == Float.class) {
                return Float.valueOf(value);
            }
            if (targetType == Double.class) {
                return Double.valueOf(value);
            }
            if (targetType == BigInteger.class) {
                return new BigInteger(value);
            }
            if (targetType == BigDecimal.class) {
                return new BigDecimal(value);
            }
            throw new MappingException("Unsupported number target: " + targetType.getName());
        }

        private static Boolean convertBoolean(Object source) {
            if (source instanceof Boolean) {
                return (Boolean) source;
            }
            if (source instanceof Number) {
                return ((Number) source).intValue() != 0;
            }
            if (source instanceof String) {
                String value = ((String) source).trim();
                return Boolean.valueOf("true".equalsIgnoreCase(value)
                        || "yes".equalsIgnoreCase(value)
                        || "1".equals(value));
            }
            throw new MappingException("Cannot convert " + source.getClass().getName() + " to boolean");
        }

        private static Character convertCharacter(Object source) {
            if (source instanceof Character) {
                return (Character) source;
            }
            if (source instanceof Number) {
                return (char) ((Number) source).intValue();
            }
            if (source instanceof String) {
                String value = (String) source;
                return value.length() == 0 ? null : value.charAt(0);
            }
            throw new MappingException("Cannot convert " + source.getClass().getName() + " to character");
        }
    }
}
