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

export module lsplant:thread;

// ratel patch: import std; 展开为传统 include（见 AGENTS 补丁清单）

import hook_helper;

namespace lsplant::art {
export class Thread {
    inline static auto CurrentFromGdb_ = "_ZN3art6Thread14CurrentFromGdbEv"_sym.as<Thread *()>;

public:
    static Thread *Current() {
        if (CurrentFromGdb_) [[likely]]
            return CurrentFromGdb_();
        return nullptr;
    }

    static bool Init(const HookHandler &handler) {
        if (!handler(CurrentFromGdb_)) [[unlikely]] {
            return false;
        }
        return true;
    }
};
}  // namespace lsplant::art
