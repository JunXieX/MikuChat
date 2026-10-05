package com.junxiex.mikuchat.core.module;

/**
 * 功能模块契约。每个功能模块实现本接口，由 {@link ModuleManager} 统一注册、启停与重载。
 * 模块之间不直接依赖，通过事件与 {@link com.junxiex.mikuchat.core.ServiceRegistry} 解耦。
 */
public interface ChatModule {

    /** 模块唯一 id，用于日志与 /mchat reload <id>。 */
    String id();

    /** 启用模块。抛出异常时该模块会被标记为未启用，但不会影响其它模块。 */
    void enable() throws Exception;

    /** 停用模块，释放资源。 */
    void disable();

    /** 重载模块配置与状态。默认先停用再启用。 */
    default void reload() throws Exception {
        disable();
        enable();
    }
}
