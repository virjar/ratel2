/*
 * ratel native 模块示例：验证 native API 三件套（watch / resolve / inline hook）。
 * 预期设备日志：
 *   RatelNativeModule: native module init, api_version=1
 *   RatelNativeModule: watch fired: /apex/.../libart.so   （已加载 → 立即回调）
 *   RatelNativeModule: ratel_test_target → 42 (orig=1)    （inline hook 生效）
 */
#include "ratel_native.h"

#include <android/log.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "RatelNativeModule", __VA_ARGS__)

static const ratel_native_api_t *api;

static int replacement_test_target() {
    LOGI("ratel_test_target hooked! replacement called");
    return 42;
}

static void on_lib_loaded(const char *path, void *handle) {
    LOGI("watch fired: %s (handle=%p)", path, handle);
}

void ratel_module_init(const ratel_native_api_t *a) {
    api = a;
    LOGI("native module init, api_version=%d", a->api_version);

    // watch 自验证：libart 已加载 → 应立即回调
    int immediate = a->watch_library("libart.so", on_lib_loaded);
    LOGI("watch libart.so registered=%d (1=已加载立即回调)", immediate);

    // inline hook 自验证：hook libratel.so 的 ratel_test_target 后调用
    void *target = a->resolve("libratel.so", "ratel_test_target");
    if (target == NULL) {
        LOGI("resolve ratel_test_target failed");
        return;
    }
    void *orig = a->hook(target, (void *) replacement_test_target);
    int (*fn)() = (int (*)()) target;
    int hooked_value = fn();
    int orig_value = orig ? ((int (*)()) orig)() : -1;
    LOGI("ratel_test_target → %d (orig=%d)", hooked_value, orig_value);
}
