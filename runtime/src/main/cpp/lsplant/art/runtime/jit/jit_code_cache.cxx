module;
#include <algorithm>
#include <array>
#include <atomic>
#include <bit>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <functional>
#include <list>
#include <map>
#include <memory>
#include <mutex>
#include <numeric>
#include <optional>
#include <set>
#include <shared_mutex>
#include <span>
#include <string>
#include <string_view>
#include <tuple>
#include <unordered_map>
#include <utility>
#include <variant>
#include <vector>

#include "logging.hpp"

export module lsplant:jit_code_cache;

// ratel patch: import std; 展开为传统 include（见 AGENTS 补丁清单）

import :art_method;
import :common;
import :thread;
import hook_helper;

namespace lsplant::art::jit {
export class JitCodeCache {
    inline static auto MoveObsoleteMethod_ =
            "_ZN3art3jit12JitCodeCache18MoveObsoleteMethodEPNS_9ArtMethodES3_"_sym.as<void(JitCodeCache::*)(ArtMethod *, ArtMethod *)>;

    static void MoveObsoleteMethods(JitCodeCache *thiz) {
        auto movements = GetJitMovements();
        LOGD("Before jit cache collection, moving %zu hooked methods", movements.size());
        for (auto [target, backup] : movements) {
            if (MoveObsoleteMethod_) [[likely]]
                MoveObsoleteMethod_(thiz, target, backup);
            else {
                backup->SetData(backup->GetData());
                target->SetData(nullptr);
            }
        }
    }

    inline static auto GarbageCollectCache_ =
            "_ZN3art3jit12JitCodeCache19GarbageCollectCacheEPNS_6ThreadE"_sym.hook->*[]
        <MemBackup auto backup>
        (JitCodeCache *thiz, Thread *self) static -> void {
            MoveObsoleteMethods(thiz);
            backup(thiz, self);
        };

    inline static auto DoCollection_ =
            "_ZN3art3jit12JitCodeCache12DoCollectionEPNS_6ThreadE"_sym.hook->*[]
        <MemBackup auto backup>
        (JitCodeCache *thiz, Thread *self) static -> void {
            MoveObsoleteMethods(thiz);
            backup(thiz, self);
        };

public:
    static bool Init(const HookHandler &handler) {
        auto sdk_int = GetAndroidApiLevel();
        if (sdk_int >= kSdkOreo) [[likely]] {
            if (!handler(MoveObsoleteMethod_)) [[unlikely]] {
                return false;
            }
        }
        if (sdk_int >= kSdkNougat) [[likely]] {
            if (!handler(GarbageCollectCache_, DoCollection_)) [[unlikely]] {
                return false;
            }
        }
        return true;
    }
};
}  // namespace lsplant::art::jit
