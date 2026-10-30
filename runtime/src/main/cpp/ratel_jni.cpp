// ratel 2.0 native bridge: LSPlant (Java method hook) + Dobby (inline hook)
// ABI: arm64-v8a / x86_64（ELF64 解析通用）
#include <jni.h>
#include <lsplant.hpp>
#include <dobby.h>
#include <android/log.h>

#include <link.h>
#include <elf.h>
#include <fcntl.h>
#include <unistd.h>
#include <dlfcn.h>
#include <string.h>
#include <stdlib.h>
#include <algorithm>
#include <string>
#include <vector>

#define LOG_TAG "RatelLoader"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

static bool g_inited = false;

// ratel_native.cpp 提供（模块原生入口桥）
extern "C" JNIEXPORT jboolean JNICALL impl_nativeModuleInit(JNIEnv *env, jclass, jstring soPath);
extern "C" JNIEXPORT jboolean JNICALL impl_nativeRedirectFile(JNIEnv *env, jclass, jstring origPath, jstring newPath);
extern "C" JNIEXPORT jboolean JNICALL impl_nativeRedirectSuspend(JNIEnv *env, jclass);
extern "C" JNIEXPORT jboolean JNICALL impl_nativeRedirectResume(JNIEnv *env, jclass);

// ---- liblzma 动态加载（系统 liblzma.so，无 scudo 冲突） ----

// lzma_stream 布局兼容定义（ARM64，与系统 lzma.h 二进制兼容）
struct LzmaStream {
    const uint8_t *next_in;
    size_t avail_in;
    uint64_t total_in;
    uint8_t *next_out;
    size_t avail_out;
    uint64_t total_out;
    const void *allocator;
    void *internal;
    void *reserved_ptr[4];
    uint64_t reserved_int[2];
    size_t reserved_int3;
    size_t reserved_int4;
    uint32_t reserved_enum[2];
};

// lzma 常量（与 lzma.h 一致）
enum LzmaRet { LZMA_OK = 0, LZMA_STREAM_END = 1, LZMA_BUF_ERROR = 10 };
enum LzmaAction { LZMA_RUN = 0, LZMA_FINISH = 3 };

static void *g_lzma = nullptr;
static int (*g_lzma_stream_decoder)(LzmaStream *, uint64_t, uint32_t) = nullptr;
static int (*g_lzma_code)(LzmaStream *, int) = nullptr;
static void (*g_lzma_end)(LzmaStream *) = nullptr;

static bool init_liblzma() {
    if (g_lzma_stream_decoder) return true;
    g_lzma_stream_decoder = (int (*)(LzmaStream *, uint64_t, uint32_t))
            dlsym(RTLD_DEFAULT, "lzma_stream_decoder");
    g_lzma_code = (int (*)(LzmaStream *, int))
            dlsym(RTLD_DEFAULT, "lzma_code");
    g_lzma_end = (void (*)(LzmaStream *))
            dlsym(RTLD_DEFAULT, "lzma_end");
    if (g_lzma_stream_decoder && g_lzma_code && g_lzma_end) {
        LOGI("liblzma via RTLD_DEFAULT");
        return true;
    }
    // reset（不可用时置空避免误用）
    g_lzma_stream_decoder = nullptr; g_lzma_code = nullptr; g_lzma_end = nullptr;
    return false;
}

// 内嵌 xz-embedded 前置声明（XZ_SINGLE 模式，无内部内存分配）
extern "C" {
#include <xz.h>
}

static std::vector<uint8_t> xz_decompress_embedded(const uint8_t *in, size_t in_size) {
    xz_crc32_init();
    struct xz_dec *dec = xz_dec_init(XZ_SINGLE, 0);
    if (!dec) return {};

    size_t out_cap = in_size * 12;
    if (out_cap < 1 << 20) out_cap = 1 << 20;
    std::vector<uint8_t> out(out_cap);

    struct xz_buf buf = {};
    buf.in = in; buf.in_pos = 0; buf.in_size = in_size;
    buf.out = out.data(); buf.out_pos = 0; buf.out_size = out_cap;

    int ret = xz_dec_run(dec, &buf);
    // XZ_SINGLE 模式 xz_dec_end 是 no-op（无内部分配），不调用避免潜在 scudo 问题
    if (ret == XZ_STREAM_END) {
        out.resize(buf.out_pos);
        return out;
    }
    return {};
}

/** XZ 解压：优先系统 liblzma，不可用走内嵌 xz-embedded。 */
static std::vector<uint8_t> xz_decompress(const uint8_t *in, size_t in_size) {
    if (init_liblzma()) {

    LzmaStream strm;
    memset(&strm, 0, sizeof(strm));
    strm.next_in = in;
    strm.avail_in = in_size;

    if (g_lzma_stream_decoder(&strm, 0, 0) != LZMA_OK) {
        LOGE("lzma_stream_decoder init failed");
        return {};
    }

    size_t out_cap = in_size * 12;
    if (out_cap < 1 << 20) out_cap = 1 << 20;
    std::vector<uint8_t> out(out_cap);
    strm.next_out = out.data();
    strm.avail_out = out_cap;

    int ret = g_lzma_code(&strm, LZMA_FINISH);
    g_lzma_end(&strm);

    if (ret == LZMA_STREAM_END) {
        out.resize(out_cap - strm.avail_out);
        LOGI("xz decompressed: %zu -> %zu bytes", in_size, out.size());
        return out;
    }
    if (ret == LZMA_BUF_ERROR && strm.avail_out == 0) {
        // 输出缓冲区不够，重试一次（翻倍）
        out_cap *= 2;
        out.resize(out_cap);
        memset(&strm, 0, sizeof(strm));
        strm.next_in = in;
        strm.avail_in = in_size;
        if (g_lzma_stream_decoder(&strm, 0, 0) == LZMA_OK) {
            strm.next_out = out.data();
            strm.avail_out = out_cap;
            ret = g_lzma_code(&strm, LZMA_FINISH);
            g_lzma_end(&strm);
            if (ret == LZMA_STREAM_END) {
                out.resize(out_cap - strm.avail_out);
                LOGI("xz retry decompressed: %zu -> %zu bytes", in_size, out.size());
                return out;
            }
        }
    }
    }  // close if(init_liblzma())
    // liblzma 不可用或失败 → fallback 到内嵌 xz-embedded
    return xz_decompress_embedded(in, in_size);
}

/** 在 mini-ELF 数据中搜索符号。 */
static void *search_in_elf_data(const uint8_t *data, size_t data_size,
                                 const char *query, bool prefix, uintptr_t base) {
    if (data_size < sizeof(Elf64_Ehdr)) return nullptr;
    auto *ehdr = (const Elf64_Ehdr *) data;
    if (memcmp(ehdr->e_ident, ELFMAG, SELFMAG) != 0) return nullptr;
    if (ehdr->e_shoff == 0 || ehdr->e_shnum == 0) return nullptr;
    if (ehdr->e_shoff + ehdr->e_shnum * sizeof(Elf64_Shdr) > data_size) return nullptr;

    auto *shdrs = (const Elf64_Shdr *) (data + ehdr->e_shoff);
    if (ehdr->e_shstrndx >= ehdr->e_shnum) return nullptr;
    auto &shstr_sh = shdrs[ehdr->e_shstrndx];
    if (shstr_sh.sh_offset + shstr_sh.sh_size > data_size) return nullptr;

    for (int pass = 0; pass < 2; pass++) {
        uint32_t want = pass == 0 ? SHT_SYMTAB : SHT_DYNSYM;
        for (int i = 0; i < ehdr->e_shnum; i++) {
            const auto &sh = shdrs[i];
            if (sh.sh_type != want || sh.sh_link >= ehdr->e_shnum) continue;
            if (sh.sh_offset + sh.sh_size > data_size) continue;
            const auto &str_sh = shdrs[sh.sh_link];
            if (str_sh.sh_offset + str_sh.sh_size > data_size) continue;

            auto *syms = (const Elf64_Sym *) (data + sh.sh_offset);
            size_t count = sh.sh_size / sizeof(Elf64_Sym);
            const char *strs = (const char *) (data + str_sh.sh_offset);

            for (size_t j = 0; j < count; j++) {
                if (syms[j].st_name == 0 || syms[j].st_value == 0) continue;
                if (syms[j].st_name >= str_sh.sh_size) continue;
                const char *name = strs + syms[j].st_name;
                bool hit = prefix ? strncmp(name, query, strlen(query)) == 0
                                  : strcmp(name, query) == 0;
                if (hit) {
                    return (void *) (base + syms[j].st_value);
                }
            }
        }
    }
    return nullptr;
}

// ---- libart 符号解析 ----

struct LibInfo {
    uintptr_t base = 0;
    std::string path;
};

static LibInfo g_libart;

static void locate_libart() {
    if (g_libart.base != 0) return;
    struct Ctx { LibInfo *out; bool found; } ctx{&g_libart, false};
    dl_iterate_phdr([](struct dl_phdr_info *info, size_t, void *data) -> int {
        auto *c = (Ctx *) data;
        const char *name = info->dlpi_name ? info->dlpi_name : "";
        if (strstr(name, "libart.so") != nullptr) {
            c->out->base = info->dlpi_addr;
            c->out->path = name;
            c->found = true;
            return 1;
        }
        return 0;
    }, &ctx);
    if (g_libart.path.empty()) {
        g_libart.path = "/apex/com.android.art/lib64/libart.so";
    }
    LOGI("libart base=%p path=%s", (void *) g_libart.base, g_libart.path.c_str());
}

static bool read_section(int fd, const Elf64_Shdr &sh, std::vector<uint8_t> *out) {
    out->resize(sh.sh_size);
    size_t done = 0;
    while (done < sh.sh_size) {
        ssize_t n = pread(fd, out->data() + done, sh.sh_size - done, sh.sh_offset + done);
        if (n <= 0) return false;
        done += n;
    }
    return true;
}

/**
 * 三路符号解析：.symtab → .dynsym → .gnu_debugdata（系统 liblzma XZ 解压）。
 * Android 16 剥离 .symtab 且隐藏 .dynsym 内部符号，.gnu_debugdata 是唯一恢复路径。
 */
static void *resolve_art_symbol(const char *query, bool prefix) {
    locate_libart();
    int fd = open(g_libart.path.c_str(), O_RDONLY);
    if (fd < 0) return nullptr;

    Elf64_Ehdr ehdr{};
    if (pread(fd, &ehdr, sizeof(ehdr), 0) != sizeof(ehdr) ||
        memcmp(ehdr.e_ident, ELFMAG, SELFMAG) != 0) {
        close(fd);
        return nullptr;
    }

    std::vector<Elf64_Shdr> shdrs(ehdr.e_shnum);
    if (pread(fd, shdrs.data(), sizeof(Elf64_Shdr) * ehdr.e_shnum, ehdr.e_shoff) < 0) {
        close(fd);
        return nullptr;
    }

    std::vector<uint8_t> shstrtab;
    if (ehdr.e_shstrndx >= shdrs.size() ||
        !read_section(fd, shdrs[ehdr.e_shstrndx], &shstrtab)) {
        close(fd);
        return nullptr;
    }

    void *result = nullptr;

    // Pass 1+2: .symtab → .dynsym
    for (int pass = 0; pass < 2 && result == nullptr; pass++) {
        uint32_t want = pass == 0 ? SHT_SYMTAB : SHT_DYNSYM;
        for (const auto &sh : shdrs) {
            if (sh.sh_type != want || sh.sh_link >= shdrs.size()) continue;
            std::vector<uint8_t> symtab, strtab;
            if (!read_section(fd, sh, &symtab) || !read_section(fd, shdrs[sh.sh_link], &strtab)) continue;
            auto *syms = (Elf64_Sym *) symtab.data();
            size_t count = symtab.size() / sizeof(Elf64_Sym);
            const char *strs = (const char *) strtab.data();
            for (size_t i = 0; i < count; i++) {
                if (syms[i].st_name == 0 || syms[i].st_value == 0) continue;
                const char *name = strs + syms[i].st_name;
                bool hit = prefix ? strncmp(name, query, strlen(query)) == 0
                                  : strcmp(name, query) == 0;
                if (hit) {
                    result = (void *) (g_libart.base + syms[i].st_value);
                    break;
                }
            }
            if (result != nullptr) break;
        }
    }

    // Pass 3: .gnu_debugdata（系统 liblzma XZ 解压 mini-ELF → .symtab 搜索）
    if (result == nullptr) {
        const char *secnames = (const char *) shstrtab.data();
        for (const auto &sh : shdrs) {
            if (sh.sh_name >= shstrtab.size()) continue;
            if (strcmp(secnames + sh.sh_name, ".gnu_debugdata") != 0) continue;

            std::vector<uint8_t> compressed;
            if (!read_section(fd, sh, &compressed)) break;
            std::vector<uint8_t> decompressed = xz_decompress(compressed.data(), compressed.size());
            if (decompressed.empty()) break;

            result = search_in_elf_data(decompressed.data(), decompressed.size(),
                                         query, prefix, g_libart.base);
            break;
        }
    }

    close(fd);
    return result;
}

// ---- JNI 动态注册（virjar 定版：不用静态 JNI 命名，class name 从 Java 侧传入） ----

static jboolean impl_nativeInit(JNIEnv *env, jclass) {
    if (g_inited) return JNI_TRUE;

    lsplant::InitInfo info{
        .inline_hooker = [](void *target, void *hooker) -> void * {
            void *orig = nullptr;
            if (DobbyHook(target, (dobby_dummy_func_t) hooker, (dobby_dummy_func_t *) &orig) == 0) {
                return orig;
            }
            return nullptr;
        },
        .inline_unhooker = [](void *func) -> bool { return DobbyDestroy(func) == 0; },
        .art_symbol_resolver = [](std::string_view symbol) -> void * {
            return resolve_art_symbol(std::string(symbol).c_str(), false);
        },
        .art_symbol_prefix_resolver = [](std::string_view prefix) -> void * {
            return resolve_art_symbol(std::string(prefix).c_str(), true);
        },
    };

    g_inited = lsplant::Init(env, info);
    if (g_inited) {
        LOGI("lsplant init ok");
    } else {
        LOGE("lsplant init FAILED");
    }
    return g_inited ? JNI_TRUE : JNI_FALSE;
}

static jobject impl_nativeHookMethod(JNIEnv *env, jclass,
                                       jobject target, jobject hooker, jobject callback) {
    if (!g_inited) return nullptr;
    return lsplant::Hook(env, target, hooker, callback);
}

static jboolean impl_nativeUnhook(JNIEnv *env, jclass, jobject target) {
    return g_inited && lsplant::UnHook(env, target) ? JNI_TRUE : JNI_FALSE;
}

static jboolean impl_nativeDeoptimize(JNIEnv *env, jclass, jobject method) {
    return g_inited && lsplant::Deoptimize(env, method) ? JNI_TRUE : JNI_FALSE;
}

/**
 * JNI 动态注册入口——Java 侧传入 class 名称，native 侧查类并注册。
 * 相比静态 JNI 命名（Java_com_ratel_runtime_RatelJNI_registerNatives）：
 * 1. so 里不暴露 Java 类名/方法名（grep 不到 com_ratel 等字符串）
 * 2. class name 从 Java 传入——R8 混淆后类名变化也不影响 native 查找
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_ratel_runtime_RatelJNI_registerNatives(JNIEnv *env, jclass, jstring className) {
    const char *clsName = env->GetStringUTFChars(className, nullptr);
    // Class.getName() 是点分隔；FindClass 只认斜杠分隔，点分隔在新 Android 上告警乃至失败
    std::string binaryName(clsName);
    env->ReleaseStringUTFChars(className, clsName);
    std::replace(binaryName.begin(), binaryName.end(), '.', '/');
    jclass clazz = env->FindClass(binaryName.c_str());
    if (!clazz) {
        LOGE("JNI register: class not found");
        return JNI_FALSE;
    }

    JNINativeMethod methods[] = {
        {"nativeInit", "()Z", (void *) impl_nativeInit},
        {"nativeModuleInit", "(Ljava/lang/String;)Z", (void *) impl_nativeModuleInit},
        {"nativeRedirectFile", "(Ljava/lang/String;Ljava/lang/String;)Z", (void *) impl_nativeRedirectFile},
        {"nativeRedirectSuspend", "()Z", (void *) impl_nativeRedirectSuspend},
        {"nativeRedirectResume", "()Z", (void *) impl_nativeRedirectResume},
        {"nativeHookMethod",
         "(Ljava/lang/reflect/Method;Ljava/lang/Object;Ljava/lang/reflect/Method;)Ljava/lang/Object;",
         (void *) impl_nativeHookMethod},
        {"nativeUnhook", "(Ljava/lang/reflect/Method;)Z", (void *) impl_nativeUnhook},
        {"nativeDeoptimize", "(Ljava/lang/reflect/Method;)Z", (void *) impl_nativeDeoptimize},
    };

    if (env->RegisterNatives(clazz, methods, sizeof(methods) / sizeof(methods[0])) != JNI_OK) {
        LOGE("JNI RegisterNatives failed");
        return JNI_FALSE;
    }
    LOGI("JNI natives registered");
    return JNI_TRUE;
}
