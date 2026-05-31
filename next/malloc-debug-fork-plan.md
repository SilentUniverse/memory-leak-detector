# 基于 bionic `malloc_debug` 的二开方案（Android 12 + root + FW 可改 + 无 App 源码）

> 上游源码已 sparse-checkout 到 [reference/bionic/libc/malloc_debug/](reference/bionic/libc/malloc_debug/) 与 [reference/bionic/libc/bionic/malloc_common_dynamic.cpp](reference/bionic/libc/bionic/malloc_common_dynamic.cpp)。本文所有结论、代码位置、改造点均按真实代码核对。

## 0. 目标对齐

用户的硬约束：
- Android 12，root，FW 可改。
- 目标 App 无源码、不能重打包。
- 要拿到**按 so 排序的分配大小 + 每个 so 的分配栈**。
- 要把超阈值的 so 立刻指派到模块负责人。
- 长跑不 ANR、开销可控。

最终方案：**就地 fork `libc_malloc_debug.so`，库名 / prefix / 21 个 `debug_*` 符号 / 两个 property 全部不变**（详见 §2.1），只在内部加 4 件事：
1. 加 `mmap/munmap/mremap` hook（malloc_debug 不抓 mmap，必须自己补），含 **区间撕裂** 处理（§3.5）。
2. per-so 聚合改为 **maps-based lazy resolution**：设备端不调 `dladdr`，只用 raw PC + `/proc/self/maps` 做 PC→so 区间匹配；同一套逻辑可在设备端输出 `SO_SUMMARY`，也可在 host 侧离线重算（§4）。
3. unwind 默认走 FP 快路径（拷 Raphael 80 行 arm64 FP unwind），加严格边界 / 对齐校验，承认对 `-fomit-frame-pointer` 库会断链；保留 libunwindstack 作 `bt_full` 兜底（§5）。
4. malloc/mmap 两条路径都复用官方 `frames_` 抓栈去重表，热路径不写 owner、不碰 Linker、不改 `PointerInfoType`。

可选 5：原生 `record_allocs` 保留、作为时间序列分析的免费品。

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
- `PointerInfoType` 保持 **16 字节原样不动**。per-so 归属不写进分配记录，而是在 dump/host 聚合阶段由 `hash_index -> frames -> /proc/self/maps` 推导，避免 ABI、32-bit 与锁序风险。
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

**唯一代码动作：把改造后的 `bionic/libc/malloc_debug/` 编进 `com.android.runtime.apex`，库名仍是 `libc_malloc_debug.so`**。工程机可尝试直推展开目录，稳定交付以重打 runtime APEX / 系统镜像为准（详 §7.2）。

### 2.1.1 启用流程（与官方 malloc_debug 完全等价）

```bash
# 一次性：部署改造版 libc_malloc_debug.so
# 推荐：重打并刷入/安装 com.android.runtime.apex，或随系统镜像交付。
# 特定 eng/userdebug 工程机可尝试直推展开目录，但 Android 12 上 /apex 通常是只读挂载。
adb root && adb remount
adb push out/.../com.android.runtime.apex /system/apex/com.android.runtime.apex
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

理论上可以加一个新的 sibling 库（如 `libc_malloc_ext.so`）+ 新 prefix + 新 property，但要改 libc 自身，且 App 启用流程会与官方文档脱节，运维成本明显更高。**放弃**。

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

### 3.1.1 初始化时序：避免与 Linker 重入

在 `debug_initialize` 入口不能立即 `bytehook_hook_all`——那个时点 Linker 还拿着 `g_dl_mutex` 加载 `libc_malloc_debug.so`，此时反过来 `bytehook_hook_all` 又会走 dlopen / `dl_iterate_phdr`，有 AB-BA 死锁风险。

序列化步骤：
1. `debug_initialize`：完成 `g_dispatch` 保存 → `DebugData` 创建 → `backtrace_startup` 。不调用 bytehook。
2. `g_debug = debug` 赋值后，设一个原子标志 `g_pending_install_mmap_hooks`。
3. 第一次进入 `debug_malloc / debug_free` 且未持任何锁时，原子 CAS 拍下标志，调用 `bytehook_hook_all`。此时 App 主线程已经在跑业务代码，Linker 锁已释放。
4. Hook 未装装前的 mmap 调用不记录——这是设计上可接受的盲区（与原版 malloc_debug 未抓 zygote 早期分配同类型）。

### 3.1.2 自定义 SoLoader / 密盘 SVC 盲区（明说）

PLT/GOT hook 仅能拦截“走 `libc.so` 导出表”的 mmap。以下场景捕不到，需主动向业务说明：
- 加固壳内置的 ELF Loader（直接 `svc 0xde` / `syscall` 发 `__NR_mmap`）。
- 某些匿名 JIT / WebView 中的内联汇编路径。
- 友商 libhwui / libGLESv2 依赖的 ion/dmabuf ioctl 申请（不走 mmap）。

如需覆盖这些场景，可选 inline hook 或 seccomp BPF 拦截 syscall——超出本方案范围，不列入 W1–W4。

### 3.2 新增 `MmapTracker.{h,cpp}`

放到 [reference/bionic/libc/malloc_debug/](reference/bionic/libc/malloc_debug/) 同级目录。核心数据结构：

```cpp
// MmapTracker.h
#pragma once
#include <sys/mman.h>
#include <map>
#include <mutex>
#include <utility>
#include <vector>

#include "OptionData.h"

struct MmapRange {
  size_t   size;
  size_t   hash_index;   // 复用 PointerData 的 frame 表
  uint8_t  prot;
  uint8_t  flags;
  uint8_t  fd_kind;      // 0=anon, 1=file_ro, 2=file_rw, 3=dmabuf, 4=stack
};

class MmapTracker : public OptionData {
 public:
  explicit MmapTracker(DebugData* d) : OptionData(d) {}
  bool Initialize(const Config& cfg);

  void InsertRange(uintptr_t addr, size_t size, uint8_t prot, uint8_t flags, int fd);
  void EraseRange(uintptr_t addr, size_t size);    // 含区间撕裂，见 §3.5
  void Mremap(uintptr_t old_addr, size_t old_size, uintptr_t new_addr, size_t new_size);

  std::vector<std::pair<uintptr_t, MmapRange>> SnapshotRanges();  // 只做拷贝，不做解析

 private:
  static bool ShouldRecord(int fd, uint8_t prot, uint8_t flags);

  std::mutex mu_;
  std::map<uintptr_t, MmapRange> ranges_;  // start → range
};
```

`MmapRange` **不存 owner**——与 §4 一致走延迟解析，只存 `hash_index`。

`InsertRange` 内部走与 `PointerData::AddBacktrace` 一样的栈采集，**复用 `PointerData::frames_` 与 `key_to_index_`**，mmap 与 malloc 的栈聚合是统一索引。

### 3.3 hook 安装

在 `debug_initialize`（[malloc_debug.cpp:387](reference/bionic/libc/malloc_debug/malloc_debug.cpp)）使用 §3.1.1 描述的“延迟装 hook”状态机。`mmap_proxy`：

```cpp
static void* mmap_proxy(void* addr, size_t len, int prot, int flags, int fd, off_t off) {
  void* r = BYTEHOOK_CALL_PREV(mmap_proxy, mmap_t, addr, len, prot, flags, fd, off);
  if (r != MAP_FAILED && len > 0 && g_debug && g_debug->mmap_tracker) {
    // MAP_FIXED 不另外走，InsertRange 内部走 ReserveRange 逻辑，
    // 调 EraseRange(addr, len) 后再 InsertRange ——复用 §3.5 的区间撕裂算法。
    g_debug->mmap_tracker->InsertRange((uintptr_t)r, len, prot, flags, fd);
  }
  BYTEHOOK_POP_STACK();
  return r;
}

static int munmap_proxy(void* addr, size_t len) {
  int rc = BYTEHOOK_CALL_PREV(munmap_proxy, munmap_t, addr, len);
  if (rc == 0 && g_debug && g_debug->mmap_tracker) {
    g_debug->mmap_tracker->EraseRange((uintptr_t)addr, len);
  }
  BYTEHOOK_POP_STACK();
  return rc;
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

### 3.5 区间撕裂 (partial munmap / mremap)

**场景**：ART / V8 / 游戏引擎常见套路是 “先 `mmap` 10MB 匿名块 → 靠 `munmap` 裁掉头尾或中间一页”。仅用 `ranges_.erase(addr)` 会造成漏算 / 多算。

**`EraseRange(addr, len)` 算法**（区间裁剪）：

```cpp
void MmapTracker::EraseRange(uintptr_t addr, size_t len) {
  uintptr_t end = addr + len;
  std::lock_guard<std::mutex> g(mu_);
  // 找到第一个 end > addr 的区间（upper_bound 后退一步）
  auto it = ranges_.upper_bound(addr);
  if (it != ranges_.begin()) --it;
  while (it != ranges_.end() && it->first < end) {
    uintptr_t r_lo = it->first;
    uintptr_t r_hi = r_lo + it->second.size;
    if (r_hi <= addr) { ++it; continue; }            // 不交
    auto info = it->second;
    it = ranges_.erase(it);
    if (r_lo < addr) {                               // 左侧残留
      ranges_[r_lo] = {addr - r_lo, info.hash_index, info.prot, info.flags, info.fd_kind};
    }
    if (r_hi > end) {                                // 右侧残留
      ranges_[end]  = {r_hi - end,  info.hash_index, info.prot, info.flags, info.fd_kind};
    }
    // 中间部分 [max(r_lo,addr), min(r_hi,end)) 被抹除。裂出来的两段复用原栈归属。
  }
}
```

`InsertRange(addr, len, ...)` 遇 `MAP_FIXED` / `MAP_FIXED_NOREPLACE` 同理：先调 `EraseRange(addr, len)` 删除重叠部分，再 `ranges_.emplace(addr, ...)`。这样全部路径走同一条裁剪逻辑，不再需要单独的 `ReplaceForMapFixed`。

`Mremap`：`EraseRange(old_addr, old_size)` + `InsertRange(new_addr, new_size, ...)`，使用原 hash_index。

**验收**：在 hello-world 跳着 `mmap(10MB)` → `munmap(addr+4MB, 1MB)` 之后，`ranges_` 应该有 2 条 合计 9MB，不是 0 也不是 1×10MB。

### 3.6 验收

- [#53 复现](https://github.com/bytedance/memory-leak-detector/issues/53) 的 `MAP_FIXED` + 部分 munmap 不再误报。
- 加载完目标 App 启动闪屏后，dump 中 mmap 类记录数与 `/proc/<pid>/maps` 中 anon 段数同数量级（差异由 free 池和小段合并解释）。

---

## 4. 改造 2：per-so 归属（maps-based lazy resolution）

### 4.1 关键设计决策：目标进程内不调用 Linker 解析 API

严禁在 `PointerData::Add`、`MmapTracker::InsertRange`、`DumpLiveToFile` 中调用 `dladdr` / `dl_iterate_phdr`。原因：
- `dladdr` / `dl_iterate_phdr` 内部要持 bionic Linker 全局互斥锁（`g_dl_mutex` 之类）。
- 上游 `kill -47` 的信号 handler 只置 `backtrace_dump_` 标志；真正 dump 发生在下一次 `malloc/free` 的 `debug_*` 路径里。如果这个分配来自 `dlopen` 内部，此时线程已经持有 Linker 锁，再调 `dladdr` 会自锁。
- 即使放到 dump 路径，只要一边持 `frame_mutex_` 一边调 Linker API，也会形成 `frame_mutex_ -> linker_lock` 与 `linker_lock -> malloc -> frame_mutex_` 的 AB-BA。

**解决：不用 Linker API，改用 `/proc/self/maps` 区间匹配**。malloc_debug 的 dump 已经天然包含 raw PC 与 `MAPS` 段；我们只需要在 dump 前解析 `/proc/self/maps` 为 `[start,end,path]` 列表，然后用 PC 做二分查找归属 so。这个路径不拿 Linker 锁，且可被 host 侧离线工具 100% 复现。

附加好处：
- **`PointerInfoType` 零修改**（结构体 / size 位布局 全部不动）。官方代码 [PointerData.h:92](reference/bionic/libc/malloc_debug/PointerData.h) 中 `zygote_child_alloc` 上限用的是 `1U << 31`（bit 31），不是 bit 63；不用去赌 size_t 高位布局是最安全的。
- 32-bit ABI 同步免疫，不需要编译期 if。
- owner 归属与 symbol 解析解耦：设备端只要给出 so 路径，函数名 / 行号可交给 host 侧 addr2line / symbols 处理。

### 4.2 新增 `MapsResolver`

新增 `MapsResolver.{h,cpp}`（纯文本解析，不碰 Linker）：

```cpp
struct MapEntry {
  uintptr_t start;
  uintptr_t end;
  uintptr_t offset;
  std::string path;
  bool executable;
};

class MapsResolver {
 public:
  static MapsResolver FromProcSelfMaps();
  const MapEntry* Find(uintptr_t pc) const;        // 二分查找 start <= pc < end
  std::string OwnerForFrames(const std::vector<uintptr_t>& frames) const;

 private:
  std::vector<MapEntry> entries_;                  // 按 start 排序
};
```

owner 选择规则：从一条栈的 frames 中取第一个满足条件的 PC：
- 所属 mapping `executable == true`；
- path 非空，且不是 `libc_malloc_debug.so`、`libc.so`、`libc++.so`、`libm.so`、`libdl.so`、linker；
- 如果全部跳过，owner 记为 `[unknown]`，离线侧再根据 top frame raw PC 兜底。

### 4.3 dump 时的安全快照流程

在 `DumpLiveToFile`（[PointerData.cpp:580](reference/bionic/libc/malloc_debug/PointerData.cpp)）顶部加：

```cpp
if (g_debug->config().options() & GROUP_BY_SO) {
  DumpSoSummary(fd);
}
```

`DumpSoSummary` 只做三件事：读 maps、拷贝快照、无锁聚合。

```cpp
static void DumpSoSummary(int fd) {
  MapsResolver maps = MapsResolver::FromProcSelfMaps();

  std::vector<AllocationSnapshot> allocs;
  std::vector<MmapSnapshot> mmaps;

  // 1) 在锁内只拷贝必要数据，不做文件 IO、不做字符串解析、不 dprintf。
  //    PointerData 内部仍沿用原有 pointer_mutex_ -> frame_mutex_ 顺序。
  {
    std::lock_guard<std::mutex> pointer_guard(pointer_mutex_);
    std::lock_guard<std::mutex> frame_guard(frame_mutex_);
    BuildAllocationSnapshotsLocked(&allocs);  // 拷贝 size/count/hash_index/frames
  }
  if (g_debug->mmap_tracker != nullptr) {
    mmaps = g_debug->mmap_tracker->SnapshotRanges();  // 只持 mmap_mu_ 拷贝 ranges_
  }

  // 2) 释放所有锁后，用 maps 做 PC→so 区间匹配并聚合。
  AggregateMallocBySo(maps, allocs, fd);
  AggregateMmapBySo(maps, mmaps, fd);

  // 3) 输出 SO_SUMMARY。原始逐条 dump + MAPS 仍保留，host 可重算。
}
```

输出格式：

```
SO_SUMMARY kind=malloc resolver=maps
so=/data/app/.../libfoo.so retained=134217728 count=12345 truncated=0
  top_stack hash=0x9ab... retained=67108864 count=321
    pc=0x7a12345678 map=/data/app/.../libfoo.so rel=0x145678
    pc=0x7a00001234 map=/apex/com.android.runtime/lib64/bionic/libc.so rel=0x1234

SO_SUMMARY kind=mmap resolver=maps
so=/data/app/.../libfoo.so anon=67108864 file_rw=8388608 count=15 truncated=1
  top_stack hash=0x7cd... retained=33554432 count=4
END_SO_SUMMARY
```

### 4.4 锁序硬规则

必须写进 code review checklist：
- `PointerData` 内部沿用上游锁序：`pointer_mutex_ -> frame_mutex_`。
- `MmapTracker::InsertRange()` 必须先完成 `AddBacktrace()` 得到 `hash_index`，再持 `mmap_mu_` 写 `ranges_`；不能在持 `mmap_mu_` 时调用 `PointerData`。
- dump 聚合先 snapshot 再解析：锁内只拷贝 POD / vectors，释放锁后再 `dprintf`、解析 maps、排序、聚合。
- `dladdr` / `dl_iterate_phdr` 在 `libc_malloc_debug.so` 内禁止使用。

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

在 [backtrace.cpp](reference/bionic/libc/malloc_debug/backtrace.cpp) 旁边加 `backtrace_fp.cpp`，arm64 实现照搬 Raphael 的 [library/src/main/unwind64/backtrace_64.cpp](library/src/main/unwind64/backtrace_64.cpp)。加强边界 / 对齐校验后 ~90 行：

```cpp
// arm64 only; arm32 / x86 / x86_64 退回 backtrace_get
size_t backtrace_get_fp(uintptr_t* stack, size_t max_depth) {
  uintptr_t stack_top, stack_bot;
  if (!GetStackRangeTLS(&stack_top, &stack_bot)) return 0;
  auto fp = (uintptr_t) __builtin_frame_address(0);
  size_t depth = 0;
  while (depth < max_depth) {
    // (1) FP 在合法栈区间内，且 8 字节对齐
    if (fp < stack_bot || fp + 16 > stack_top) break;
    if (fp & 0x7u) break;

    uintptr_t lr  = *((uintptr_t*) fp + 1);
    uintptr_t pre = *((uintptr_t*) fp);

    // (2) lr 做基本合法性检查；热路径不查 maps，避免额外 IO/锁
    if (lr == 0 || (lr & 0x3u) != 0) break;

    // (3) 上一层 FP 必须严格递增、对齐、仍在栈内
    if (pre <= fp || (pre & 0x7u) != 0 || pre + 16 > stack_top) break;

    stack[depth++] = lr;
    fp = pre;
  }
  return depth;
}
```

三道校验源于同事评审的提醒：机械式按 FP 走会被 `-fomit-frame-pointer` 库带到乱步。任一条不满足就断链返回，显著降低崩溃风险；最终仍以 fuzz/soak 验证为准，文档不承诺“绝对不崩”。

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

FP 模式的代价是 `-fomit-frame-pointer` 编译的库会断链：
- Android 12 核心系统库（bionic / ART / libhwui / libgui 等）在 AOSP 默认 build flags 下保留了 FP。
- 但三方商业库 + 部分 MTK/高通闭源驱动会开 `-fomit-frame-pointer` / `-O3`。这些库内部发起的 alloc 只能抓出 1-2 帧。
- 离线侧：`SO_SUMMARY` 输出时标记 `truncated=true` 供下游识别；必要时可针对问题 so 临时切 `bt_full` 跑一次拍深栈。

**魔鬼测试**：针对常见商业库（libgame.so / libwebviewchromium.so）预先测一下平均拍栈深度，列在发布说明里。

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

最简：加一个新 prop `persist.debug.malloc.wrap_all`，置 1 时无条件允许 wrap。

```cpp
if (android::base::GetBoolProperty("persist.debug.malloc.wrap_all", false)) {
  // 跳过 debuggable 检查，照样读 wrap.<pkg>
}
```

这样不动平台默认行为，灰度可控。

### 6.2 让 setprop 持久化

Android 12 上 `setprop libc.debug.malloc.options` 重启会丢。要长期跑，加进 `init.rc`：

```
on property:persist.debug.malloc.target_program=*
    setprop libc.debug.malloc.options "backtrace bt_fp by_so track_mmap mmap_filter_ro"
    setprop libc.debug.malloc.program  ${persist.debug.malloc.target_program}
```

然后 `setprop persist.debug.malloc.target_program com.example.targetapp; stop; start`。

### 6.3 让 zygote 改 dispatch 时仍走我们的 fork

`bionic/libc/bionic/malloc_common_dynamic.cpp::MallocInitImpl` 启动期检查 prop → dlopen libc_malloc_debug.so。**不需要改**，A 方案完美兼容。

---

## 7. AOSP 构建路径

### 7.1 源码位置

把改动放到 `bionic/libc/malloc_debug/` 内（fork 一份的话放 `vendor/<oem>/bionic_patches/malloc_debug/`）：

```
bionic/libc/malloc_debug/
  malloc_debug.cpp          (+ 延迟装 mmap hook 状态机，见 §3.1.1)
  PointerData.{h,cpp}       (+ DumpSoSummary + 快照逻辑；PointerInfoType 不动)
  Config.{h,cpp}            (+ TRACK_MMAP / BACKTRACE_FP / GROUP_BY_SO / MMAP_FILTER_RO)
  backtrace.cpp             (不动)
  backtrace_fp.cpp          (新)
  MmapTracker.{h,cpp}       (新，含区间裁剪)
  MapsResolver.{h,cpp}      (新，解析 /proc/self/maps，不碰 Linker)
  DebugData.{h,cpp}         (+ MmapTracker* mmap_tracker;)
  Android.bp                (+ static_libs: libbytehook_static, srcs: 新文件)
```

### 7.2 构建

```bash
source build/envsetup.sh
lunch <target>-userdebug
mma bionic/libc/malloc_debug
m com.android.runtime
# 产物优先使用 out/target/product/<dev>/system/apex/com.android.runtime.apex
adb root && adb remount
adb push out/.../com.android.runtime.apex /system/apex/com.android.runtime.apex
adb reboot
```

说明：Android 12 的 `/apex/com.android.runtime/...` 是展开后的只读挂载，很多设备即使 `adb remount` 也不能直接覆盖。直推 `libc_malloc_debug.so` 只能作为少数 eng/userdebug 机型的临时调试法；可重复交付路径是重打 `com.android.runtime.apex` 或随 ROM/system image 刷入。

### 7.3 调试技巧

- 加 `verbose` option 看 `info_log` 输出：`adb logcat -s libc malloc_debug`。
- crash 在 hook 路径里：先把 `track_mmap` 关掉，二分定位是 mmap hook 还是 FP unwinder 引入。
- dump 卡死：检查是否违反了 “目标进程内不调用 Linker 解析 API” 硬规则（§4.1）。`grep -nE 'dladdr|dl_iterate_phdr' bionic/libc/malloc_debug/*.cpp` 应无结果。
- FP unwind 随机断链：检查是否上线了 §5.1 中三道校验。

---

## 8. 工作量与里程碑

按一人估算。

| 阶段 | 内容 | 估时 |
|---|---|---|
| **W1** | sync AOSP、跑通原版 malloc_debug、确认 dump 格式 | 1 天 |
|  | 加 `BACKTRACE_FP` option + arm64 FP unwinder（含三道校验）+ benchmark | 2 天 |
|  | 加 `GROUP_BY_SO` + `MapsResolver` + `DumpSoSummary`（maps 区间匹配），验证排序输出 | 1 天 |
| **W2** | bytehook apex 化与延迟装 hook 状态机（§3.1.1） | 2 天 |
|  | `MmapTracker` + 区间撕裂算法（§3.5）+ 单元测试 | 2 天 |
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

1. **runtime APEX 替换风险**：Android 12 的 `com.android.runtime` apex 是 mainline 模块，OTA 会覆盖。需要在 OTA 后重新推一次，或把改动落进 `vendor/` 让 ROM build 时一并打进系统镜像。不要把“直推 `/apex/.../libc_malloc_debug.so`”当成通用部署方案。
2. **已规避的 Linker 死锁**（详§4.1）：原设计热路径 / dump 路径 `dladdr` 都存在 AB-BA 或自锁窗口，已改为 `/proc/self/maps` 区间匹配。需 “no `dladdr` / `dl_iterate_phdr` in `libc_malloc_debug.so`” 作为 code review 硬规则，linter 加 grep。
3. **bytehook apex 化**：bytehook 默认依赖 Java 层 BackgroundExecutor，要先剥成纯 native 版。同时初始化顺序按 §3.1.1 走，避免与 Linker 重入。
4. **partial munmap 漏算**：必须走 §3.5 的区间裁剪算法，不能偷懒用 `erase(addr)`；上线前写单元测试（头裁 / 尾裁 / 中间挖洞 / 跨多区间）。
5. **FP unwind 断链**（详§5.4）：`-fomit-frame-pointer` 库只能抓 1-2 帧；离线侧要能识别这类栈、必要时兜底一次 `bt_full`。
6. **自定义 SoLoader / SVC 盲区**（详§3.1.2）：PLT hook 抓不到绕过导出表的 mmap。对业务明说这是设计性盲区。
7. **MapsResolver 口径风险**：PC→so 归属依赖 `/proc/self/maps` 快照。dlclose 后旧 PC 可能已经没有 mapping；这类 stack 标记为 `[unmapped]`，保留 raw PC 供 host 侧符号化兜底。
8. **fork upstream 维护**：每次 Android 大版本升级，bionic `MallocDispatch` 与 `PointerData` 都可能变。建议每个 Android release 上游 rebase 一次，写自动 diff 报告辅助 merge。
9. **mmap hook 在 zygote fork 时机**：bionic 在 `__libc_init_main_thread` 阶段就调 `mmap`，那时 malloc_debug 还没初始化。这部分早期 mmap 不会被记录——这是**设计上可接受**的盲区（不属于业务泄漏）。

---

## 11. 验收清单

- [ ] 部署新 runtime APEX / 系统镜像后，原版 `kill -47 <pid>` 能产出与原版兼容的 dump（向后兼容）。
- [ ] 开启 `by_so` 后，dump 顶部出现 `SO_SUMMARY` 段，按 retained 降序。
- [ ] 开启 `track_mmap`，[#53 复现代码](https://github.com/bytedance/memory-leak-detector/issues/53) 不再误报泄漏。
- [ ] **partial munmap 单元测试**：头裁 / 尾裁 / 中间挖洞 / 跨多区间 4 种用例后，`ranges_` 总量与预期严格一致。
- [ ] **全库无 Linker 解析 API**：`grep -nE 'dladdr|dl_iterate_phdr' bionic/libc/malloc_debug/*.cpp` 无结果；per-so 只由 `MapsResolver` 完成。
- [ ] **锁序验证**：`InsertRange()` 不在持 `mmap_mu_` 时调用 `AddBacktrace()`；dump 聚合先 snapshot 再排序/输出。
- [ ] **FP unwind 坚固性**：面向带 `-fomit-frame-pointer` 的商业库跑 fuzz，千万次调用无 `SIGSEGV`；断链能被离线侧标记。
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
