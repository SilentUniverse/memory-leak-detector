# 基于 bionic `malloc_debug` 的二开方案（Android 12 + root + FW 可改 + 无 App 源码）

> 上游源码已 sparse-checkout 到 [reference/bionic/libc/malloc_debug/](reference/bionic/libc/malloc_debug/) 与 [reference/bionic/libc/bionic/malloc_common_dynamic.cpp](reference/bionic/libc/bionic/malloc_common_dynamic.cpp)。本文所有结论、代码位置、改造点均按真实代码核对。

## 0. 目标对齐

用户的硬约束：
- Android 12，root，FW 可改。
- 目标 App 无源码、不能重打包。
- 要拿到**按 so 排序的分配大小 + 每个 so 的分配栈**。
- 要把超阈值的 so 立刻指派到模块负责人。
- 长跑不 ANR、开销可控。

最终方案：**fork `libc_malloc_debug.so` 改名 `libc_malloc_owl.so`**（"owl"=on-the-fly per-so leak watcher，下文沿用此命名占位），加 4 件事：
1. 加 `mmap/munmap/mremap` hook（malloc_debug 不抓 mmap，必须自己补）。
2. 在 `PointerInfoType` 上加 `owner_so_id`，每次 `Add()` 时归属到一个业务 so。
3. dump 时按 `owner_so_id` 分桶 → 按 retained bytes 排序输出 `SO_SUMMARY`。
4. unwind 默认走 FP 快路径（拷 Raphael 那 80 行），把单次 alloc 开销打回 µs 级；保留 libunwindstack 作 `bt_full` 备选。

可选 5：保留 `record_allocs` 原样能用，作为时间序列分析的免费品。

---

## 1. 上游代码事实速记

### 1.1 加载链路：bionic 怎么把 `libc_malloc_debug.so` 拉起来

文件：[reference/bionic/libc/bionic/malloc_common_dynamic.cpp](reference/bionic/libc/bionic/malloc_common_dynamic.cpp)

```cpp
static constexpr char kDebugSharedLib[] = "libc_malloc_debug.so";
static constexpr char kDebugPrefix[]    = "debug";
static constexpr char kDebugPropertyOptions[] = "libc.debug.malloc.options";
static constexpr char kDebugPropertyProgram[] = "libc.debug.malloc.program";
```

`CheckLoadMallocDebug()` 读两个 system property：
- `libc.debug.malloc.options` —— 必须非空。
- `libc.debug.malloc.program` —— 可选，用 `strstr(getprogname(), program)` 做包含匹配。

`InitSharedLibrary()` 找 6 个 `$prefix_xxx` 符号 + 14 个 `$prefix_$dispatch` 符号（即 `debug_malloc / debug_free / ...`），把它们写进 `MallocDispatch` 替换表。

**结论：库名 `libc_malloc_debug.so`、prefix `debug`、导出符号 `debug_*`、两个 property 名（`libc.debug.malloc.options` / `libc.debug.malloc.program`）全部是 bionic 写死的接口。我们的 fork 必须照原样命名、原样导出，bionic 加载逻辑零改动，App 启用方式与官方 malloc_debug 完全一致**。

### 1.2 `MallocDispatch` 表里**没有 mmap**

`InitMallocFunctions()` 一次性 dlsym 出来的 14 个函数（[malloc_common_dynamic.cpp:139](reference/bionic/libc/bionic/malloc_common_dynamic.cpp)）：

```
free / calloc / mallinfo / mallopt / malloc / malloc_info /
malloc_usable_size / memalign / posix_memalign / aligned_alloc /
realloc / malloc_iterate / malloc_disable / malloc_enable
( + pvalloc / valloc on 32-bit )
```

**没有 `mmap / munmap / mremap / brk / sbrk`**。这是 malloc_debug 抓不到 SoLoader / 字体 / texture / `MAP_ANONYMOUS` 大块的根本原因。要补这块**必须自己 PLT/inline hook**，不能复用 dispatch 机制。

### 1.3 `PointerData` —— 活分配核心数据结构

文件：[reference/bionic/libc/malloc_debug/PointerData.h](reference/bionic/libc/malloc_debug/PointerData.h)、[PointerData.cpp](reference/bionic/libc/malloc_debug/PointerData.cpp)

```cpp
struct PointerInfoType {
  size_t size;           // 高位用作 zygote_child_alloc 标记
  size_t hash_index;     // → frames_ 的 key
};

static std::mutex pointer_mutex_;
static std::unordered_map<uintptr_t, PointerInfoType> pointers_;  // mangled_ptr → info

static std::mutex frame_mutex_;
static std::unordered_map<size_t, FrameInfoType> frames_;         // hash_index → 帧 + refcount
```

关键点：
- `pointers_` 单一全局锁，但 hashmap 平摊 O(1)，比 Raphael 的"链表 + 单锁"好。
- `frames_` 用 **refcount 复用**，同样的栈只存一份——天然就是"按栈聚合"的雏形，per-so 维度只需要再加一层 group-by。
- `PointerInfoType` 是 **16 字节**。我们要加 `owner_so_id`，建议复用 `size` 的高 24 位（size 实际只到 31 位减 1，最大 2 GiB - 1，一次 alloc 不会大到溢出），**不改结构体大小**。
- `Add` 路径（[PointerData.cpp:204](reference/bionic/libc/malloc_debug/PointerData.cpp)）：先 `AddBacktrace`，再持 `pointer_mutex_` 写 `pointers_[mangled_ptr] = {...}`。两段锁，干净。
- `Remove`：持 `pointer_mutex_` 读 + erase；`RemoveBacktrace` 持 `frame_mutex_` decref。

### 1.4 unwind 路径：默认 `_Unwind_Backtrace`，`bt_full` 走 libunwindstack

文件：[backtrace.cpp](reference/bionic/libc/malloc_debug/backtrace.cpp)、[UnwindBacktrace.cpp](reference/bionic/libc/malloc_debug/UnwindBacktrace.cpp)

```cpp
// PointerData::AddBacktrace
if (g_debug->config().options() & BACKTRACE_FULL) {
  if (!Unwind(&frames, &frames_info, num_frames)) { ... }   // libunwindstack
} else {
  num_frames = backtrace_get(frames.data(), frames.size()); // _Unwind_Backtrace
}
```

默认的 `backtrace_get` 用 `_Unwind_Backtrace`（libgcc/libunwind 的 EH unwind），**比 Raphael 的 FP unwind 慢一档**，但比 libunwindstack 的 DWARF 快一档。README 那句"slow down by an order of magnitude"主要是相对 `nop` baseline 说的，不是相对 FP。

替换路径：在 `backtrace_get` 旁加一个 `backtrace_get_fp`，按 arm64 FP 链走，runtime 通过 option 选。

### 1.5 dump 格式 —— 已经"按 size 排序"，但没有"按 so 排序"

`PointerData::GetList()`（[PointerData.cpp:405](reference/bionic/libc/malloc_debug/PointerData.cpp)）的排序 key：
1. zygote_child 在前；
2. **size 降序**；
3. backtrace 长度降序；
4. pointer 升序。

`DumpLiveToFile()`（[PointerData.cpp:580](reference/bionic/libc/malloc_debug/PointerData.cpp)）输出：

```
Total memory: <bytes>
Allocation records: <n>
Backtrace size: <max_frames>

z <0|1>  sz <size>  num <count>  bt <pc> <pc> ...
  bt_info {"map" rel_pc "func" off} ...   (仅 BACKTRACE_FULL)
```

**没有 so 维度的视图**。要"按 so 排序"必须改这里。

---

## 2. 总体设计

### 2.1 对外接口全部保留（库名 / prefix / 符号 / property 不变）

所有对 App、bionic、运维脚本可见的接口严格保留原值：

| 项 | 取值（与官方完全一致） |
|---|---|
| 库名 | `libc_malloc_debug.so` |
| AOSP 模块名 | `libc_malloc_debug` |
| prefix | `debug` |
| 导出符号 | `debug_initialize / debug_malloc / debug_free / ...`（21 个，见 [exported64.map](reference/bionic/libc/malloc_debug/exported64.map)） |
| 启用 property | `libc.debug.malloc.options` / `libc.debug.malloc.program` |
| 触发 dump 信号 | `SIGRTMAX-17` (47) |
| dump 文件路径 | `/data/local/tmp/backtrace_heap.<pid>.txt` |

好处：
- App 侧启用方式 **0 变化**：原来怎么 `setprop libc.debug.malloc.options "backtrace"` 启用官方 malloc_debug，现在就怎么用，区别只是 option 字符串可以多写几个新关键字（`bt_fp` / `by_so` / `track_mmap` / `mmap_filter_ro`）。
- `am dumpheap -n`、`dumpsys meminfo --unreachable`、官方 `aosp/development/scripts/native_heapdump_viewer.py` 等周边工具继续可用。
- 回滚只需推回原版 so，配置不动。

**唯一动作：把改造后的 `bionic/libc/malloc_debug/` 编出 `libc_malloc_debug.so`，覆盖 `/apex/com.android.runtime/lib64/bionic/libc_malloc_debug.so`**。

### 2.1.1 启用流程（与官方 malloc_debug 完全等价）

```bash
# 一次性：推送改造版 so（覆盖原版）
adb root && adb remount
adb push out/.../libc_malloc_debug.so /apex/com.android.runtime/lib64/bionic/
adb reboot

# 启用（与官方 README 一致：https://android.googlesource.com/platform/bionic/+/master/libc/malloc_debug/README.md）
adb shell setprop libc.debug.malloc.options \
  "backtrace bt_fp by_so track_mmap mmap_filter_ro"
adb shell setprop libc.debug.malloc.program com.example.targetapp
adb shell stop && adb shell start

# 触发 dump
adb shell kill -47 $(pidof com.example.targetapp)
adb pull /data/local/tmp/backtrace_heap.<pid>.txt artifacts/
```

**关键**：option 字符串中老的关键字（`backtrace` / `backtrace=N` / `fill` / `guard` / `free_track` / `record_allocs` / `verbose` 等）**全部继续生效**。我们只在 [Config.cpp](reference/bionic/libc/malloc_debug/Config.cpp) 的 `kOptions[]` 末尾**追加** 4 条新条目，没动任何已有条目（见 §2.2）。也就是说：
- 想用官方原能力 → `setprop libc.debug.malloc.options "backtrace"`，行为与原版逐字节一致。
- 想用新能力 → 在 option 串里加 `bt_fp by_so track_mmap` 等关键字即可。

### 2.1.2 不采用：B 方案（另开 sibling 库）

理论上可以加 `libc_malloc_owl.so` + `kOwlPrefix` + `libc.owl.options`，但要改 libc 自身，且 App 启用流程会与官方文档脱节，运维成本明显更高。**放弃**。

### 2.2 新增 option 位

在 [Config.h](reference/bionic/libc/malloc_debug/Config.h) 现有位图后续接：

```cpp
constexpr uint64_t TRACK_MMAP        = 0x20000;  // hook mmap/munmap/mremap
constexpr uint64_t BACKTRACE_FP      = 0x40000;  // arm64 FP unwind (低开销)
constexpr uint64_t GROUP_BY_SO       = 0x80000;  // dump 时输出 SO_SUMMARY
constexpr uint64_t MMAP_FILTER_RO    = 0x100000; // 默认过滤 dlopen / 只读文件 mmap
```

option 字符串解析：在 [Config.cpp](reference/bionic/libc/malloc_debug/Config.cpp) 的 `kOptions[]` 数组里追加四行 `{ "track_mmap", TRACK_MMAP, ... }` 即可，沿用 `SetFeature` 模板。

推荐组合：`backtrace bt_fp by_so track_mmap mmap_filter_ro backtrace_min_size=512`，对长跑大型 App 是平衡点。

### 2.3 启用方式补充：单 App 精确 attach

基础流程已在 §2.1.1 给出。若想只对一个 release App 生效（不影响其它进程），两条路：

```bash
# 路径 A：用官方 program 过滤（推荐，无 FW 改动）
#   bionic 内部 strstr(getprogname(), program) 匹配才加载 libc_malloc_debug.so
adb shell setprop libc.debug.malloc.options \
  "backtrace bt_fp by_so track_mmap mmap_filter_ro"
adb shell setprop libc.debug.malloc.program com.example.targetapp
adb shell am force-stop com.example.targetapp

# 路径 B：wrap.<pkg> + LIBC_DEBUG_MALLOC_OPTIONS（FW 要摘 debuggable 校验，见 §6.1）
adb shell setprop wrap.com.example.targetapp \
  '"LIBC_DEBUG_MALLOC_OPTIONS=backtrace bt_fp by_so track_mmap mmap_filter_ro logwrapper"'
adb shell am force-stop com.example.targetapp
```

触发 dump：
```bash
# 信号：SIGRTMAX-17 = 47
adb shell kill -47 $(pidof com.example.targetapp)
adb pull /data/local/tmp/backtrace_heap.<pid>.txt artifacts/
```

---

## 3. 改造 1：mmap / munmap / mremap hook

malloc_debug 自身不引入 hook 库。我们需要：

### 3.1 引入轻量 hook：bytehook（推荐）

bytedance 自己的 [bytehook](https://github.com/bytedance/bytehook) 已经成熟，支持 Android 4.1-15、armv7/arm64/x86/x86_64，PLT/PLT-GOT 双重，比 xhook 稳。把 `bytehook` 作为 prebuilt 静态库放进 `external/bytehook/`，在 [Android.bp](reference/bionic/libc/malloc_debug/Android.bp) 的 `libc_malloc_debug` 段加：

```
static_libs: [
    ...
    "libbytehook_static",
],
```

注意：`libc_malloc_debug.so` 是 `whole_static_libs: ["libmemory_trace"]` + `static_libs: ["libasync_safe", "libbase", ...]`，所有依赖必须 `apex_available: ["com.android.runtime"]`。bytehook 需要做对应改造（去掉 Java 依赖、把 prebuilt apex 化）。预计 0.5 天。

### 3.2 新增 `MmapTracker.{h,cpp}`

放到 [reference/bionic/libc/malloc_debug/](reference/bionic/libc/malloc_debug/) 同级目录。核心数据结构：

```cpp
// MmapTracker.h
#pragma once
#include <sys/mman.h>
#include <map>
#include <mutex>

#include "OptionData.h"

struct MmapRange {
  size_t   size;
  size_t   hash_index;   // 复用 PointerData 的 frame 表
  uint32_t owner_so_id;  // 见 §4
  uint8_t  prot;
  uint8_t  flags;
  uint8_t  fd_kind;      // 0=anon, 1=file_ro, 2=file_rw, 3=dmabuf, 4=stack
};

class MmapTracker : public OptionData {
 public:
  explicit MmapTracker(DebugData* d) : OptionData(d) {}
  bool Initialize(const Config& cfg);

  void InsertRange(uintptr_t addr, size_t size, uint8_t prot, uint8_t flags, int fd);
  void EraseRange(uintptr_t addr, size_t size);
  void ReplaceForMapFixed(uintptr_t addr, size_t size, uint8_t prot, uint8_t flags, int fd);
  void Mremap(uintptr_t old_addr, size_t old_size, uintptr_t new_addr, size_t new_size);

  void DumpBySo(int fd);  // 配合 GROUP_BY_SO

 private:
  static bool ShouldRecord(int fd, uint8_t prot, uint8_t flags);

  std::mutex mu_;
  std::map<uintptr_t, MmapRange> ranges_;  // start → range
};
```

`InsertRange` 内部走与 `PointerData::AddBacktrace` 一样的栈采集，**复用 `PointerData::frames_` 与 `key_to_index_`** 这样 mmap 与 malloc 的栈聚合是统一索引，dump 时按 so + stack 一并出表，不会重复存。

### 3.3 hook 安装

在 `debug_initialize`（[malloc_debug.cpp:387](reference/bionic/libc/malloc_debug/malloc_debug.cpp)）`g_debug = debug;` 之后插入：

```cpp
if (g_debug->config().options() & TRACK_MMAP) {
  InstallMmapHooks();
}
```

`InstallMmapHooks()`：

```cpp
static void* mmap_proxy(void* addr, size_t len, int prot, int flags, int fd, off_t off) {
  void* r = BYTEHOOK_CALL_PREV(mmap_proxy, mmap_t, addr, len, prot, flags, fd, off);
  if (r != MAP_FAILED && len > 0 && g_debug && g_debug->mmap_tracker) {
    if (flags & MAP_FIXED) {
      g_debug->mmap_tracker->ReplaceForMapFixed((uintptr_t)r, len, prot, flags, fd);
    } else {
      g_debug->mmap_tracker->InsertRange((uintptr_t)r, len, prot, flags, fd);
    }
  }
  BYTEHOOK_POP_STACK();
  return r;
}
```

按 `bytehook_hook_all("libc.so", "mmap", mmap_proxy, ...)` 安装。**注意要排除 libc_malloc_debug.so 自身和 bionic 内部的 mmap**（用 `bytehook_hook_partial` + 黑名单），否则会递归。

### 3.4 噪声过滤（默认开 `MMAP_FILTER_RO`）

```cpp
bool MmapTracker::ShouldRecord(int fd, uint8_t prot, uint8_t flags) {
  if ((g_debug->config().options() & MMAP_FILTER_RO) == 0) return true;
  // 1) dlopen-triggered mmap：用 TLS guard。dlopen 入口设置 tls=1，hook 路径读 tls 即跳过。
  if (pthread_getspecific(g_dlopen_guard_key) != nullptr) return false;
  // 2) 只读文件映射：MAP_PRIVATE + PROT_READ + fd>=0
  if (fd >= 0 && (flags & MAP_PRIVATE) && prot == PROT_READ) return false;
  return true;
}
```

dlopen guard：再 hook 一次 `dlopen / android_dlopen_ext`，进入时设 tls，离开时清。这一段 ~30 行。

### 3.5 验收

- [#53 复现](https://github.com/bytedance/memory-leak-detector/issues/53) 的 `MAP_FIXED` + 部分 munmap 不再误报。
- 加载完目标 App 启动闪屏后，dump 中 mmap 类记录数与 `/proc/<pid>/maps` 中 anon 段数同数量级（差异由 free 池和小段合并解释）。

---

## 4. 改造 2：per-so 归属

### 4.1 `owner_so_id` 在哪里产生

新增文件 `SoTable.{h,cpp}`：

```cpp
class SoTable {
 public:
  // 把 pc 反查到 so 路径，hash 后返回稳定 id；首次见到新 so 时记录 dli_fname。
  static uint32_t IdFor(uintptr_t pc);
  // dump 时输出 id → so 名映射。
  static void Dump(int fd);

 private:
  static std::mutex mu_;
  static std::unordered_map<uint32_t, std::string> id_to_name_;
};
```

实现用 bionic 的 `dladdr`（malloc_debug 已经依赖 dlfcn）。注意 `dladdr` 在 hook 路径里有递归 malloc 风险，需要在 `ScopedDisableDebugCalls` 块里调用——malloc_debug 现有的 `DebugDisableSet` 机制刚好可以复用。

### 4.2 在哪里赋值

`PointerData::Add` ([PointerData.cpp:204](reference/bionic/libc/malloc_debug/PointerData.cpp))：

```cpp
void PointerData::Add(const void* ptr, size_t pointer_size) {
  size_t hash_index = 0;
  uint32_t owner_so_id = 0;
  if (backtrace_enabled_) {
    hash_index = AddBacktrace(g_debug->config().backtrace_frames(), pointer_size);
    // NEW: 取栈顶第一个非 libc/非 libc_malloc_debug 的帧作为 owner
    if (g_debug->config().options() & GROUP_BY_SO) {
      owner_so_id = ResolveOwnerSo(hash_index);
    }
  }
  std::lock_guard<std::mutex> g(pointer_mutex_);
  uintptr_t m = ManglePointer(reinterpret_cast<uintptr_t>(ptr));
  pointers_[m] = PointerInfoType{
      PointerInfoType::GetEncodedSize(pointer_size, owner_so_id),  // 见 §4.3
      hash_index};
}
```

`ResolveOwnerSo(hash_index)`：

```cpp
uint32_t ResolveOwnerSo(size_t hash_index) {
  std::lock_guard<std::mutex> g(frame_mutex_);
  auto it = frames_.find(hash_index);
  if (it == frames_.end()) return 0;
  for (uintptr_t pc : it->second.frames) {
    Dl_info info;
    if (dladdr(reinterpret_cast<void*>(pc), &info) == 0 || info.dli_fname == nullptr) continue;
    const char* fname = info.dli_fname;
    // 跳过自身与基础库
    if (strstr(fname, "/libc_malloc_debug.so")) continue;
    if (strstr(fname, "/libc.so") || strstr(fname, "/libc++.so")) continue;
    if (strstr(fname, "/libm.so") || strstr(fname, "/libdl.so")) continue;
    if (strstr(fname, "/linker") || strstr(fname, "/ld-android")) continue;
    return SoTable::IdFor(reinterpret_cast<uintptr_t>(info.dli_fbase));  // 用 so 基址做稳定 id
  }
  return 0;  // unowned
}
```

### 4.3 不增加 `PointerInfoType` 体积：复用 size 高位

```cpp
struct PointerInfoType {
  size_t size;        // [62:32]=owner_so_id (30 bits), [31]=zygote_child, [30:0]=size
  size_t hash_index;

  size_t   RealSize()      const { return size & 0x7FFFFFFFULL; }
  bool     ZygoteChildAlloc() const { return size & (1ULL << 31); }
  uint32_t OwnerSoId()     const { return static_cast<uint32_t>(size >> 32) & 0x3FFFFFFFu; }

  static size_t GetEncodedSize(size_t real, uint32_t owner_id) {
    return GetEncodedSize(*g_zygote_child, real) |
           (static_cast<uint64_t>(owner_id & 0x3FFFFFFFu) << 32);
  }
};
```

64-bit 上 size_t 是 64 位，高 30 位完全空闲，**owner_so_id 不占额外内存**。32-bit 设备不支持 GROUP_BY_SO（直接编译期 if 屏蔽），因为 size_t 高位被 zygote_child 占了。32-bit App 在 Android 12 上越来越少，可以接受。

### 4.4 dump 时按 so 输出

在 `DumpLiveToFile`（[PointerData.cpp:580](reference/bionic/libc/malloc_debug/PointerData.cpp)）顶部加：

```cpp
if (g_debug->config().options() & GROUP_BY_SO) {
  DumpSoSummary(fd);  // 新函数
}
```

`DumpSoSummary` 工作流程：

```cpp
static void DumpSoSummary(int fd) {
  // 1) 持 pointer_mutex_ + frame_mutex_，遍历 pointers_，按 OwnerSoId 累加 retained / count。
  // 2) 收集每个 owner 的 top-K (k=5) stack hash_index，按 retained 排。
  // 3) 输出格式：
  //    SO_SUMMARY
  //    so_id=42 name=libfoo.so retained=128MB count=12345
  //      top_stack hash=0x... size=...x10 (重复10次)
  //        pc <off> /system/lib64/libfoo.so (Foo::Bar+0x10)
  //        ...
  //    so_id=17 name=libbar.so retained=64MB count=6789
  //      ...
  //    END_SO_SUMMARY
}
```

输出之后再走原本的 dump（仍然保留 per-allocation 详情），离线脚本可以二选一。

### 4.5 owner.yaml 离线侧

报告里只到 so 名。owner 映射放在 host 侧，与 [next/optimization-and-automation-roadmap.md](next/optimization-and-automation-roadmap.md) §3.13 设计一致：

```yaml
# next/owners.yaml
sos:
  - so: libfoo.so
    owner: media
    contacts: [alice@x.com, bob@x.com]
  - so: libbar.so
    owner: net
    contacts: [carol@x.com]
```

`raphaelctl report --in heap.txt --owners owners.yaml --by owner` 把 `SO_SUMMARY` 直接拼成"按团队的 retained 表"。

---

## 5. 改造 3：低开销 unwinder（FP 快路径）

### 5.1 新增 `backtrace_get_fp`

在 [backtrace.cpp](reference/bionic/libc/malloc_debug/backtrace.cpp) 旁边加 `backtrace_fp.cpp`，arm64 实现照搬 Raphael 的 [library/src/main/unwind64/backtrace_64.cpp](library/src/main/unwind64/backtrace_64.cpp)，~80 行：

```cpp
// arm64 only; arm32 / x86 / x86_64 退回 backtrace_get
size_t backtrace_get_fp(uintptr_t* stack, size_t max_depth) {
  uintptr_t st, sb;
  if (!GetStackRangeTLS(&st, &sb)) return 0;
  auto fp = (uintptr_t) __builtin_frame_address(0);
  size_t depth = 0;
  while (isValid(fp, st, sb) && depth < max_depth) {
    uintptr_t lr  = *((uintptr_t*) fp + 1);
    uintptr_t pre = *((uintptr_t*) fp);
    if ((pre & 0xfu) || pre < fp + 16) break;
    stack[depth++] = lr;
    sb = fp; fp = pre;
  }
  return depth;
}
```

### 5.2 dispatch

[PointerData.cpp::AddBacktrace](reference/bionic/libc/malloc_debug/PointerData.cpp)：

```cpp
if (g_debug->config().options() & BACKTRACE_FULL) {
  if (!Unwind(&frames, &frames_info, num_frames)) return kBacktraceEmptyIndex;
} else if (g_debug->config().options() & BACKTRACE_FP) {   // NEW
#if defined(__aarch64__)
  frames.resize(num_frames);
  num_frames = backtrace_get_fp(frames.data(), frames.size());
  if (num_frames == 0) return kBacktraceEmptyIndex;
  frames.resize(num_frames);
#else
  // fall through to default
  ...
#endif
} else {
  frames.resize(num_frames);
  num_frames = backtrace_get(frames.data(), frames.size());
  ...
}
```

### 5.3 预期开销

| unwinder | 单次 alloc 抓栈耗时（arm64 高通 870 估算） |
|---|---|
| 无 unwind 基线 | < 100 ns |
| **`BACKTRACE_FP`（新）** | **~1 µs** |
| `BACKTRACE` 默认 (`_Unwind_Backtrace`) | ~5-15 µs |
| `BACKTRACE_FULL` (libunwindstack DWARF) | ~30-100 µs |

按业务 App 每秒 1k-10k 次 alloc 估算，开 FP unwind 后 CPU 总开销 ~1-10%；开默认 unwind ~5-50%；开 BACKTRACE_FULL 起步 30%。FP 模式是唯一**长跑可接受**的档位。

FP 模式的代价是 `-fomit-frame-pointer` 编译的库会断链。**搭配 `bt_full` 作为兜底**：dump 时如果某条栈深度 < 3 帧，离线侧可对应到 maps 区域，必要时人工跑一次 `bt_full` 抓深栈。

---

## 6. FW 侧改造

### 6.1 让 `wrap.<APP>` 对非 debuggable 包生效

文件：`frameworks/base/core/jni/com_android_internal_os_Zygote.cpp`，搜 `EnableDebugger` / `MaybeInstallLinkerNamespace` 附近的 `IsAppDebuggable` 判断。Android 12 上对应：

```cpp
// frameworks/base/core/jni/com_android_internal_os_Zygote.cpp::SpecializeCommon
if ((runtime_flags & RuntimeFlags::DEBUG_ENABLE_JDWP) == 0) {
  // 原版：非 debuggable 时不读 wrap.<pkg>
  ...
}
```

最简：加一个新 prop `persist.owl.wrap.allow_all`，置 1 时无条件允许 wrap。

```cpp
if (android::base::GetBoolProperty("persist.owl.wrap.allow_all", false)) {
  // 跳过 debuggable 检查，照样读 wrap.<pkg>
}
```

这样不动平台默认行为，灰度可控。

### 6.2 让 setprop 持久化

Android 12 上 `setprop libc.debug.malloc.options` 重启会丢。要长期跑，加进 `init.rc`：

```
on property:persist.owl.target_program=*
    setprop libc.debug.malloc.options "backtrace bt_fp by_so track_mmap mmap_filter_ro"
    setprop libc.debug.malloc.program  ${persist.owl.target_program}
```

然后 `setprop persist.owl.target_program com.example.targetapp; stop; start`。

### 6.3 让 zygote 改 dispatch 时仍走 owl

`bionic/libc/bionic/malloc_common_dynamic.cpp::MallocInitImpl` 启动期检查 prop → dlopen libc_malloc_debug.so。**不需要改**，A 方案完美兼容。

---

## 7. AOSP 构建路径

### 7.1 源码位置

把改动放到 `bionic/libc/malloc_debug/` 内（fork 一份的话放 `vendor/<oem>/bionic_patches/malloc_debug/`）：

```
bionic/libc/malloc_debug/
  malloc_debug.cpp          (+ InstallMmapHooks 调用)
  PointerData.{h,cpp}       (+ owner_so_id, ResolveOwnerSo, DumpSoSummary)
  Config.{h,cpp}            (+ TRACK_MMAP / BACKTRACE_FP / GROUP_BY_SO / MMAP_FILTER_RO)
  backtrace.cpp             (不动)
  backtrace_fp.cpp          (新)
  MmapTracker.{h,cpp}       (新)
  SoTable.{h,cpp}           (新)
  DebugData.{h,cpp}         (+ MmapTracker* mmap_tracker;)
  Android.bp                (+ static_libs: libbytehook_static, srcs: 新文件)
```

### 7.2 构建

```bash
source build/envsetup.sh
lunch <target>-userdebug
mma bionic/libc/malloc_debug
# 产物：out/target/product/<dev>/system/apex/com.android.runtime/lib64/bionic/libc_malloc_debug.so
adb root && adb remount
adb push out/.../libc_malloc_debug.so /apex/com.android.runtime/lib64/bionic/
adb reboot
```

apex 直推不行的话，重打 `com.android.runtime.apex` 后 `adb install` 即可。Android 12 上 runtime apex 可热升级。

### 7.3 调试技巧

- 加 `verbose` option 看 `info_log` 输出：`adb logcat -s libc malloc_debug`。
- crash 在 hook 路径里：先把 `track_mmap` 关掉，二分定位是 mmap hook 还是 SoTable::IdFor 引入。
- dump 卡死：检查 `pointer_mutex_` 是否被 `dladdr` 间接走到了 malloc——必须在 `ScopedDisableDebugCalls` 里调 `dladdr`。

---

## 8. 工作量与里程碑

按一人估算。

| 阶段 | 内容 | 估时 |
|---|---|---|
| **W1** | sync AOSP、跑通原版 malloc_debug、确认 dump 格式 | 1 天 |
|  | 加 `BACKTRACE_FP` option + arm64 FP unwinder + benchmark | 2 天 |
|  | 加 `GROUP_BY_SO` + `SoTable` + `DumpSoSummary`，验证排序输出 | 2 天 |
| **W2** | bytehook 集成 apex，写 `MmapTracker` + mmap/munmap/mremap hook | 3 天 |
|  | 噪声过滤（dlopen guard、只读文件 mmap、stack）| 1 天 |
|  | dump 端集成 mmap 视图 | 1 天 |
| **W3** | FW 改 wrap debuggable 校验 + init.rc + 一键脚本 | 1 天 |
|  | 离线工具：解析 `SO_SUMMARY`、对 `owners.yaml`、出 markdown 报告 | 2 天 |
|  | 设备矩阵 smoke test（armv7 / arm64 / Android 12-13） | 2 天 |
| **W4** | 长跑 24h soak、性能 benchmark、文档 | 5 天 |

**总计 ~4 周，1 人**。Raphael 二开做到同等能力（按之前 roadmap M1+M2）大约 ~6 周，**fork malloc_debug 比改 Raphael 更省时间**，因为 PointerData 的 hashmap + frame refcount + dump 框架都是现成的。

---

## 9. 与 Raphael 二开方案的取舍

| 维度 | Fork malloc_debug | Raphael 二开 |
|---|---|---|
| App 改动 | **无** | 需要集成 AAR / 反编译注入 |
| 部署 | apex 推送 / OTA | 随 App 走 |
| 启用 | setprop | broadcast / JNI |
| mmap 抓取 | 自己加（§3） | 已有但有 bug |
| per-so | 自己加（§4） | 需自己加 |
| free 栈 / 时序 | `free_track` / `record_allocs` **现成** | 完全没有 |
| UAF 检测 | `guard` / `fill` **现成** | 没有 |
| 长跑稳定性 | hashmap + refcount，hash 分桶后即可线性扩展 | 32768 节点池硬上限 |
| 跨 Android 版本维护 | 每个 release merge upstream | 自己维护 hook 兼容矩阵 |
| 对生产 release App 适用 | **是**（本场景） | 否（要源码） |

**在本场景下 fork malloc_debug 是唯一可行解**，并且能力上位 Raphael 二开。

---

## 10. 风险清单

1. **apex 替换风险**：Android 12 的 `com.android.runtime` apex 是 mainline 模块，OTA 会覆盖。需要在 OTA 后重新推一次，或把改动落进 `vendor/` 让 ROM build 时一并打进系统镜像。
2. **dladdr 递归 malloc**：`SoTable::IdFor` 里调 `dladdr`，内部如果触发 malloc 会陷死锁。**必须**在 `ScopedDisableDebugCalls` 里调，且首次填表后缓存 (dli_fbase → id) 避免每次 alloc 都查。
3. **bytehook apex 化**：bytehook 默认依赖 Java 层 BackgroundExecutor，要先剥成纯 native 版本。
4. **GROUP_BY_SO + 32-bit**：见 §4.3，32-bit ABI 无法复用 size 高位，要么禁用、要么扩 `PointerInfoType` 到 24 字节。预算前者。
5. **fork upstream 维护**：每次 Android 大版本升级，bionic `MallocDispatch` 与 `PointerData` 都可能变。建议每个 Android release 上游 rebase 一次，写自动 diff 报告辅助 merge。
6. **mmap hook 在 zygote fork 时机**：bionic 在 `__libc_init_main_thread` 阶段就调 `mmap`，那时 malloc_debug 还没初始化。这部分早期 mmap 不会被记录——这是 **设计上可接受** 的盲区（不属于业务泄漏）。

---

## 11. 验收清单

- [ ] 推送新 `libc_malloc_debug.so` 后，原版 `kill -47 <pid>` 能产出与原版兼容的 dump（向后兼容）。
- [ ] 开启 `by_so` 后，dump 顶部出现 `SO_SUMMARY` 段，按 retained 降序。
- [ ] 开启 `track_mmap`，[#53 复现代码](https://github.com/bytedance/memory-leak-detector/issues/53) 不再误报泄漏。
- [ ] 开启 `bt_fp`，benchmark：每秒 10k 次 malloc 下 CPU < 5%。
- [ ] 8 小时 soak：dump 文件大小线性增长（说明真在抓），无 ANR，无 OOM。
- [ ] `wrap.<pkg>` 对非 debuggable release 包能生效（FW 改动）。
- [ ] 离线 `raphaelctl report --by owner --owners owners.yaml` 出按团队的 retained 表。
- [ ] 任意时刻 `kill -47 <pid>` 可多次出 dump，每次都包含当前 live 全集（不需要重启 App）。

---

## 12. 引用

- 上游源码（已 sparse clone）：
  - [reference/bionic/libc/malloc_debug/](reference/bionic/libc/malloc_debug/)
  - [reference/bionic/libc/bionic/malloc_common_dynamic.cpp](reference/bionic/libc/bionic/malloc_common_dynamic.cpp)
- 上游文档：
  - [bionic/libc/malloc_debug/README.md](https://android.googlesource.com/platform/bionic/+/master/libc/malloc_debug/README.md)
- 相关方案对比：
  - [next/raphael-vs-malloc-debug.md](next/raphael-vs-malloc-debug.md)
  - [next/optimization-and-automation-roadmap.md](next/optimization-and-automation-roadmap.md)
- 关键代码锚点：
  - 加载链路 `CheckLoadMallocDebug` / `InitMallocFunctions`：[malloc_common_dynamic.cpp:139](reference/bionic/libc/bionic/malloc_common_dynamic.cpp)
  - `debug_initialize` / `debug_malloc` / `debug_free` / `InternalFree`：[malloc_debug.cpp:387 / :583 / :679](reference/bionic/libc/malloc_debug/malloc_debug.cpp)
  - `PointerData::Add / Remove / AddBacktrace / GetList / DumpLiveToFile`：[PointerData.cpp:204 / :223 / :151 / :405 / :580](reference/bionic/libc/malloc_debug/PointerData.cpp)
  - option 位图：[Config.h](reference/bionic/libc/malloc_debug/Config.h)
  - 现有 unwind：[backtrace.cpp](reference/bionic/libc/malloc_debug/backtrace.cpp) + [UnwindBacktrace.cpp](reference/bionic/libc/malloc_debug/UnwindBacktrace.cpp)
  - 14 个 dispatch 函数（**不含 mmap**）：[malloc_common_dynamic.cpp:139-209](reference/bionic/libc/bionic/malloc_common_dynamic.cpp)
