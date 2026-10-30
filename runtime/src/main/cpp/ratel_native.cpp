// ratel native API：模块原生入口可用的 inline hook / 符号解析 / so 加载监听。
// libart 符号解析与 Dobby inline hook 基建复用（LSPlant 同源），此处仅做能力导出。
#include <jni.h>
#include <dobby.h>
#include <android/log.h>

#include <dlfcn.h>
#include <fcntl.h>
#include <link.h>
#include <stdarg.h>
#include <string.h>
#include <sys/stat.h>
#include <utility>

#include <mutex>
#include <string>
#include <vector>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "RatelNative", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "RatelNative", __VA_ARGS__)

#define RATEL_NATIVE_API_VERSION 1

// ---- so 加载监听注册表 ----

struct LibWatch {
    std::string name;
    void (*cb)(const char *path, void *handle);
};

static std::vector<LibWatch> g_watches;
static std::mutex g_watch_mutex;
static bool g_linker_hooked = false;

/** dl_iterate_phdr 按短名查已加载库（basename contains 语义）。 */
static void *find_loaded(const char *name_part, std::string *path_out) {
    struct Ctx {
        const char *q;
        void *base;
        std::string path;
        bool found;
    } ctx{name_part, nullptr, {}, false};
    dl_iterate_phdr([](struct dl_phdr_info *info, size_t, void *data) -> int {
        auto *c = (Ctx *) data;
        const char *name = info->dlpi_name ? info->dlpi_name : "";
        const char *slash = strrchr(name, '/');
        const char *bn = slash ? slash + 1 : name;
        if (strstr(bn, c->q) != nullptr) {
            c->base = (void *) info->dlpi_addr;
            c->path = name;
            c->found = true;
            return 1;
        }
        return 0;
    }, &ctx);
    if (ctx.found && path_out) *path_out = ctx.path;
    return ctx.base;
}

/** dlopen/android_dlopen_ext 返回后按 basename 命中回调（一次性，命中即注销）。 */
static void fire_watchers(const char *path, void *handle) {
    const char *slash = strrchr(path, '/');
    const char *bn = slash ? slash + 1 : path;
    std::unique_lock<std::mutex> lk(g_watch_mutex);
    for (auto it = g_watches.begin(); it != g_watches.end();) {
        if (strstr(bn, it->name.c_str()) != nullptr) {
            auto cb = it->cb;
            it = g_watches.erase(it);
            lk.unlock();
            cb(path, handle);
            lk.lock();
        } else {
            ++it;
        }
    }
}

// ---- dlopen / android_dlopen_ext inline hook（所有顶层加载走 libdl stub，可全量拦截） ----

static void *(*orig_dlopen)(const char *filename, int flag);

static void *my_dlopen(const char *filename, int flag) {
    void *h = orig_dlopen(filename, flag);
    if (h != nullptr && filename != nullptr) {
        fire_watchers(filename, h);
    }
    return h;
}

static void *(*orig_android_dlopen_ext)(const char *filename, int flag, const void *extinfo);

static void *my_android_dlopen_ext(const char *filename, int flag, const void *extinfo) {
    void *h = orig_android_dlopen_ext(filename, flag, extinfo);
    if (h != nullptr && filename != nullptr) {
        fire_watchers(filename, h);
    }
    return h;
}

static void ensure_linker_hooked() {
    if (g_linker_hooked) return;
    g_linker_hooked = true;
    void *addr = dlsym(RTLD_DEFAULT, "dlopen");
    if (addr != nullptr) {
        DobbyHook(addr, (dobby_dummy_func_t) my_dlopen, (dobby_dummy_func_t *) &orig_dlopen);
    }
    addr = dlsym(RTLD_DEFAULT, "android_dlopen_ext");
    if (addr != nullptr) {
        DobbyHook(addr, (dobby_dummy_func_t) my_android_dlopen_ext,
                  (dobby_dummy_func_t *) &orig_android_dlopen_ext);
    }
    LOGI("dlopen/android_dlopen_ext hooked for library watching");
}

// ---- 模块原生 API（函数指针表经 ratel_module_init 传入，零动态链接依赖） ----

extern "C" {

__attribute__((visibility("default")))
int ratel_api_version() { return RATEL_NATIVE_API_VERSION; }

/** inline hook 自验证目标（模块可 hook 它证明链路）。 */
__attribute__((visibility("default")))
int ratel_test_target() { return 1; }

/** inline hook：返回 trampoline（调用即原函数），失败返回 nullptr。 */
__attribute__((visibility("default")))
void *ratel_hook(void *target, void *replacement) {
    dobby_dummy_func_t orig = nullptr;
    if (DobbyHook(target, (dobby_dummy_func_t) replacement, &orig) != 0) {
        return nullptr;
    }
    return (void *) orig;
}

__attribute__((visibility("default")))
int ratel_unhook(void *target) { return DobbyDestroy(target); }

/**
 * 符号解析：lib_name 传 so 短名（libfoo.so）或全路径或 nullptr（RTLD_DEFAULT）。
 * 命中已加载库的 dynsym（系统 so 剥离的内部符号需自行文件 symtab 解析的场景，
 * LSPlant 已内建，此处面向模块的通用符号走标准 dlsym）。
 */
__attribute__((visibility("default")))
void *ratel_resolve(const char *lib_name, const char *symbol) {
    if (lib_name == nullptr) {
        return dlsym(RTLD_DEFAULT, symbol);
    }
    void *h = dlopen(lib_name, RTLD_LAZY | RTLD_NOLOAD);
    if (h == nullptr) {
        std::string path;
        if (find_loaded(lib_name, &path)) {
            h = dlopen(path.c_str(), RTLD_LAZY | RTLD_NOLOAD);
        }
    }
    if (h == nullptr) {
        return nullptr;
    }
    return dlsym(h, symbol);
}

/** so 加载监听：已加载立即回调返回 1；否则注册待加载时回调返回 0。 */
__attribute__((visibility("default")))
int ratel_watch_library(const char *name, void (*on_loaded)(const char *path, void *handle)) {
    std::string path;
    if (find_loaded(name, &path)) {
        void *h = dlopen(path.c_str(), RTLD_LAZY | RTLD_NOLOAD);
        on_loaded(path.c_str(), h);
        return 1;
    }
    {
        std::lock_guard<std::mutex> lk(g_watch_mutex);
        g_watches.push_back({name, on_loaded});
    }
    ensure_linker_hooked();
    return 0;
}


// ---- 文件重定向（crcBypass：app 自校验读 base.apk → 原始字节） ----
// 1.x IORelocator 精神续作（收窄版：仅文件级重定向）。
// 关键坑（1.x AssertFixer 修复）：重定向后 AssetManager 若沿用旧 zip 索引
// （patched apk 的 central directory）配新内容体（origin），索引与内容错位 →
// resource api 读 axml 直接 crash。Java 侧 ResourcesRebuilder 必须紧随重定向执行。

static std::vector<std::pair<std::string, std::string>> g_redirects;
static std::mutex g_redirect_mutex;

// 重定向豁免：runtime 自身读 patched apk（ZipFile 构造瞬间 suspend，fd 打开后 resume）
static volatile bool g_redirect_suspended = false;

static int redirect_open(const char *path, int flags) {
    if (g_redirect_suspended) return -1;
    std::lock_guard<std::mutex> lk(g_redirect_mutex);
    for (auto &r : g_redirects) {
        if (path != nullptr && r.first == path) {
            return open(r.second.c_str(), flags);
        }
    }
    return -1;
}

static int (*orig_open)(const char *, int, ...);

static int my_open(const char *path, int flags, ...) {
    int fd = redirect_open(path, flags);
    if (fd >= 0) return fd;
    va_list ap;
    va_start(ap, flags);
    mode_t mode = static_cast<mode_t>(va_arg(ap, int));
    va_end(ap);
    return orig_open(path, flags, mode);
}

static int (*orig_openat)(int, const char *, int, ...);

static int my_openat(int dirfd, const char *path, int flags, ...) {
    int fd = redirect_open(path, flags);
    if (fd >= 0) return fd;
    va_list ap;
    va_start(ap, flags);
    mode_t mode = static_cast<mode_t>(va_arg(ap, int));
    va_end(ap);
    return orig_openat(dirfd, path, flags, mode);
}

/** 重定向豁免开关（runtime 内部读 patched apk 用；ZipFile 构造瞬间挂起）。 */
__attribute__((visibility("default")))
int ratel_redirect_suspend() {
    g_redirect_suspended = true;
    return 0;
}

__attribute__((visibility("default")))
int ratel_redirect_resume() {
    g_redirect_suspended = false;
    return 0;
}

/**
 * 文件级重定向（精确路径匹配）：后续 open/openat 打开 origPath 时实际打开 newPath。
 * 典型：base.apk → 释放的原始 apk（crc/hash 校验读到原始字节）。
 */
__attribute__((visibility("default")))
int ratel_redirect_file(const char *orig_path, const char *new_path) {
    if (orig_path == nullptr || new_path == nullptr) return -1;
    {
        std::lock_guard<std::mutex> lk(g_redirect_mutex);
        g_redirects.emplace_back(orig_path, new_path);
    }
    static bool hooked = false; // 惰性安装 open hook（libdl stub，与 watch 同款）
    if (!hooked) {
        hooked = true;
        void *addr = dlsym(RTLD_DEFAULT, "open");
        if (addr != nullptr) {
            DobbyHook(addr, (dobby_dummy_func_t) my_open, (dobby_dummy_func_t *) &orig_open);
        }
        addr = dlsym(RTLD_DEFAULT, "openat");
        if (addr != nullptr) {
            DobbyHook(addr, (dobby_dummy_func_t) my_openat, (dobby_dummy_func_t *) &orig_openat);
        }
        LOGI("open/openat hooked for file redirect");
    }
    return 0;
}

} // extern "C"

// 与分发的 ratel_native.h 保持二进制一致（模块按头文件编译）
struct ratel_native_api {
    int api_version;
    void *(*hook)(void *target, void *replacement);
    int (*unhook)(void *target);
    void *(*resolve)(const char *lib_name, const char *symbol);
    int (*watch_library)(const char *name, void (*on_loaded)(const char *path, void *handle));
    int (*redirect_file)(const char *orig_path, const char *new_path);
};

static struct ratel_native_api g_native_api = {
        RATEL_NATIVE_API_VERSION,
        ratel_hook,
        ratel_unhook,
        ratel_resolve,
        ratel_watch_library,
        ratel_redirect_file,
};

// java 桥（RatelJNI 动态注册表引用）：重定向豁免
extern "C" JNIEXPORT jboolean JNICALL
impl_nativeRedirectSuspend(JNIEnv *, jclass) {
    return ratel_redirect_suspend() == 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
impl_nativeRedirectResume(JNIEnv *, jclass) {
    return ratel_redirect_resume() == 0 ? JNI_TRUE : JNI_FALSE;
}

// java 桥（RatelJNI 动态注册表引用）：文件重定向
extern "C" JNIEXPORT jboolean JNICALL
impl_nativeRedirectFile(JNIEnv *env, jclass, jstring origPath, jstring newPath) {
    const char *orig = env->GetStringUTFChars(origPath, nullptr);
    const char *dest = env->GetStringUTFChars(newPath, nullptr);
    int rc = ratel_redirect_file(orig, dest);
    env->ReleaseStringUTFChars(origPath, orig);
    env->ReleaseStringUTFChars(newPath, dest);
    return rc == 0 ? JNI_TRUE : JNI_FALSE;
}

// java 桥（RatelJNI 动态注册表引用）：调用模块 so 的 ratel_module_init 入口
extern "C" JNIEXPORT jboolean JNICALL
impl_nativeModuleInit(JNIEnv *env, jclass, jstring soPath) {
    const char *path = env->GetStringUTFChars(soPath, nullptr);
    void *h = dlopen(path, RTLD_LAZY | RTLD_NOLOAD); // 已由 System.load 加载，仅取 handle
    env->ReleaseStringUTFChars(soPath, path);
    if (h == nullptr) {
        LOGE("native module: dlopen NOLOAD failed");
        return JNI_FALSE;
    }
    auto init = (void (*)(const struct ratel_native_api *)) dlsym(h, "ratel_module_init");
    if (init == nullptr) {
        LOGE("native module: ratel_module_init not exported");
        return JNI_FALSE;
    }
    init(&g_native_api);
    return JNI_TRUE;
}
