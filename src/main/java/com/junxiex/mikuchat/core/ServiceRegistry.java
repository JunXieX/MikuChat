package com.junxiex.mikuchat.core;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 轻量服务注册表，用于模块之间按类型解耦获取服务（如网络、频道）。
 */
public final class ServiceRegistry {

    private final Map<Class<?>, Object> services = new ConcurrentHashMap<>();

    public <T> void register(Class<T> type, T instance) {
        services.put(type, instance);
    }

    public <T> Optional<T> find(Class<T> type) {
        return Optional.ofNullable(type.cast(services.get(type)));
    }

    public <T> T require(Class<T> type) {
        T value = type.cast(services.get(type));
        if (value == null) {
            throw new IllegalStateException("Service not available: " + type.getName());
        }
        return value;
    }

    public void unregister(Class<?> type) {
        services.remove(type);
    }
}
