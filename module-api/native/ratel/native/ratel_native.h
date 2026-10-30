/*
 * ratel native module API（与 libratel.so 二进制一致）。
 *
 * 模块原生入口：在模块 so 中导出
 *     void ratel_module_init(const ratel_native_api_t *api);
 * 声明于 assets/native_init（每行一个 so 文件名），runtime 加载 so 后回调。
 * API 以函数指针表传入——模块 so 无需链接 libratel.so。
 */
#pragma once

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    int api_version;
    /** inline hook：返回 trampoline（调用即原函数），失败返回 0。 */
    void *(*hook)(void *target, void *replacement);
    /** 取消 hook。 */
    int (*unhook)(void *target);
    /** 符号解析：lib_name 传 so 短名（libfoo.so）/全路径/NULL（全局）。 */
    void *(*resolve)(const char *lib_name, const char *symbol);
    /**
     * so 加载监听：name 为短名（basename contains 匹配）。
     * 已加载立即回调返回 1；否则加载时回调返回 0（一次性）。
     */
    int (*watch_library)(const char *name, void (*on_loaded)(const char *path, void *handle));
} ratel_native_api_t;

/** 模块原生入口（符号名固定，由 runtime 加载后回调）。 */
void ratel_module_init(const ratel_native_api_t *api);

#ifdef __cplusplus
}
#endif
