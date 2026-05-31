# Raphael vs. bionic `malloc_debug`：实现原理与优缺点深度对比

参考资料：[bionic/libc/malloc_debug/README.md](https://android.googlesource.com/platform/bionic/+/master/libc/malloc_debug/README.md)

本文聚焦三件事：
1. **数据是怎么收集的**：hook 入口、记录字段、容量上限、是否落入临界区。
2. **是否计算已释放的数据**：alloc/free 配对策略、能不能拿到"曾经分配但已释放"的轨迹。
3. **解析期栈回溯流程**：unwind 算法、符号化、多线程下的可靠性。

---

## 0. TL;DR

| 维度 | Raphael (本仓库) | bionic `malloc_debug` |
|---|---|---|
| 部署形态 | 业务 so，App 内动态加载 + 广播控制 | 系统 libc 旁路 shim，`setprop` / `wrap.sh` 注入，**与 libc 同生命周期** |
| Hook 位置 | PLT/GOT 重定向（xHook）+ inline hook（And64InlineHook / inlineHook.c） | bionic 通过函数指针表把 `malloc/free/...` 切到 shim |
| 覆盖范围 | 选定 so 名单（PLT/GOT 模式）或全进程（inline 模式）；`mmap/munmap` 也 hook | 进程内所有 native 分配（**含 libc 自身**），但只覆盖 `malloc/calloc/realloc/posix_memalign/memalign/aligned_alloc/malloc_usable_size`（不含 `mmap`） |
| 记录哪些事件 | malloc/calloc/realloc/memalign/free/mmap/mmap64/munmap/pthread_exit | malloc 系 + free；**不记录 mmap** |
| 配对 free 后处理 | **从 in-memory hash 表直接摘除并回收 slot**，仅保留"未释放"分配 | 默认同样只保留活分配；`free_track` 模式额外保留最近 N 个已释放分配 + free 时栈；`record_allocs` 模式按时序把每一次 alloc/free 都写到文件 |
| 是否"算"已释放的数据 | **不算**。释放即丢失，无法回查 free 时的栈和大小 | **可选三档**：默认不算 / `free_track` 保留尾部 N 条 / `record_allocs` 保留全量时序 |
| 容量 | `ALLOC_CACHE_SIZE = 1<<15 = 32768` 个活分配节点（写死），满后静默丢弃 | `record_allocs` 默认 8M 条、上限 50M 条；活分配数仅受堆限制；`free_track` 默认 100、上限 16384 |
| 栈回溯算法 | FP 链：arm64 `__builtin_frame_address(0)` + 走 `fp[0]/fp[1]`；armv7 用自带的 `libudf_unwind`（EXIDX/CFI 简化版） | libunwindstack：默认非 `backtrace_full` 时只抓 PC；`backtrace_full` 用 DWARF/CFI 完整 unwind，**可穿透 Java 帧** |
| 栈最大深度 | `MAX_TRACE_DEPTH = 16`（写死） | `backtrace[=MAX_FRAMES]`，默认 16，可配到 256 |
| 解析期符号化 | 设备侧用 `xdl_addr + __cxa_demangle` 直接出 `so + offset + symbol`；离线 `raphael.py` 走 `addr2line` 还原行号 | 设备侧只输出 PC + `/proc/PID/maps`；离线用 `development/scripts/native_heap_dump.py` 或 `ndk-stack` 解符号 |
| 触发模式 | JNI / 广播 `ACTION_PRINT`，主动 dump | 进程退出、`SIGRTMAX-17` 信号、`am dumpheap -n`、`backtrace_dump_on_exit` |
| 适用对象 | 第三方业务 App、debuggable 包；不需要 root | 平台开发优先（root / userdebug）；非 root 设备需 `wrap.sh`（API 26+） |

一句话：**malloc_debug 是 OS 提供的"调试型 allocator shim"，覆盖更全、能保留 free 轨迹、unwind 更准；Raphael 是"业务 App 可发布的轻量内存快照工具"，覆盖更窄、只保留活分配、unwind 更快但更脆。**

---

## 1. 数据是怎么统计的

### 1.1 Raphael 的统计路径

入口：[library/src/main/cpp/HookProxy.h](library/src/main/cpp/HookProxy.h)
- 每个 hooked 入口（malloc / calloc / realloc / memalign / mmap / mmap64）成功返回后，调用 `insert_memory_backtrace(address, size)`：
  ```cpp
  static inline void insert_memory_backtrace(void *address, size_t size) {
      Backtrace backtrace;
      backtrace.depth = 0;
  #if defined(__arm__)
      backtrace.depth = libudf_unwind_backtrace(backtrace.trace, 2, depth + 1);
  #elif defined(__aarch64__)
      backtrace.depth = unwind_backtrace(backtrace.trace, depth + 1);
  #endif
      if (backtrace.depth > 1) {
          cache->insert((uintptr_t) address, size, &backtrace);
      }
  }
  ```
- `free / munmap / pthread_exit` 走 `cache->remove(address)`，把对应节点从 hash 表摘掉。

落库：[library/src/main/cpp/MemoryCache.cpp](library/src/main/cpp/MemoryCache.cpp)
- 一张 `alloc_table[ALLOC_INDEX_SIZE]`（`1<<16 = 65536` 桶），地址按 `(addr >> ADDR_HASH_OFFSET) & 0xFFFF` 散列。
- 节点池 `AllocPool`：lock-free CAS free-list + bump index，**容量固定 32768**。满了 `LOGGER("Alloc cache is full!!!!!!!!")` 后**静默丢弃后续分配**。
- 每个节点字段（[library/src/main/cpp/Cache.h](library/src/main/cpp/Cache.h)）：`addr / size / trace[MAX_TRACE_DEPTH=16] / next`。

关键属性：
- **只统计当前未释放的分配**。
- **没有任何全局计数器**：累计分配次数、累计字节数、free 次数、按 so/线程聚合的数据，全部不存。
- **没有 free 时栈**。
- 当 `AllocPool` 满时，新分配直接丢失（不是覆盖旧的，是直接放弃记录），report 会出现"看着没分配但实际泄漏"的盲区。

### 1.2 malloc_debug 的统计路径

入口：bionic 在 libc 内维护一个分发表，`malloc_debug.so` 被 `dlopen` 后用 `debug_malloc / debug_free / ...` 替换整张表。每次调用都走 shim：
- 默认路径只增/减一个 `LeakInfo` 节点（live allocation 链表）。
- 启用 `backtrace` 时，再调 unwinder 抓栈写入节点。
- 启用 `record_allocs` 时，**每一次 alloc/free 都写一条记录**到 ring buffer（默认 8M、上限 50M），收信号 `SIGRTMAX-18` 后写文件。
  - 行格式（README）：`TID: malloc PTR SIZE` / `TID: free PTR` / `TID: realloc NEW OLD SIZE` / `TID: memalign PTR ALIGN SIZE` / `TID: thread_done 0x0`。
  - 写完后 ring buffer **清空**；落盘期间产生的事件被忽略。
- 启用 `free_track[=N]` 时，free 不立即归还给 allocator，而是放进尾部 N 个的"延迟释放队列"，**在 free 当下再抓一遍栈**，同时把内存填 `0xef` 用于 UAF 检查。

关键属性：
- live allocation 表 **没有写死容量**，理论上不丢分配（受系统堆限制）。
- 时序流水：`record_allocs` 提供；可由离线工具按时间复算瞬时占用、累计分配字节、峰值等。
- free 时栈：`free_track` 提供（最近 N 条）。
- guard / fill / expand / verify_pointers 这些是"调试型"特性，Raphael 完全没有对等品。

### 1.3 收集层差异要点

| 项 | Raphael | malloc_debug |
|---|---|---|
| 锁粒度 | 单互斥锁 `alloc_mutex` 保护 `alloc_table` + `print` 时整张表持锁 | 内部哈希分桶，各桶独立锁；`record_allocs` 用无锁 ring buffer |
| 落库容量 | 32768 活分配（写死，溢出丢弃） | 受堆限制；`record_allocs` 8M~50M 条（满后丢弃但不影响活分配统计） |
| 是否覆盖 mmap | **是**（`mmap/mmap64/munmap`） | **否** |
| 是否覆盖 dlopen 引发的 mmap | 是（这是 noise 来源） | 不涉及（不 hook mmap） |
| 累计计数 | 无 | live 表可遍历；`record_allocs` 可重放 |
| 多线程 ID / 时间戳 | **无**，节点没有 TID/timestamp | TID 写在 `record_allocs` 每行行首 |
| 释放后数据 | 丢弃 | 默认丢弃；`free_track` 保留尾部 N；`record_allocs` 保留全量时序 |

---

## 2. 是否计算了已释放的数据

这是用户问题的核心。结论：

### 2.1 Raphael：完全不算

- `MemoryCache::remove` 一旦命中就 `alloc_cache->recycle(p)` 把节点归还给池，**栈和大小同时丢失**。
- `Raphael::print`（[library/src/main/cpp/Raphael.cpp](library/src/main/cpp/Raphael.cpp)）写完 report 后还会 `clean_cache()` 清空 `mSpace` 目录，**历史 report 也会被擦**。
- 这意味着：
  - 拿不到"分配后又释放"的瞬时占用峰值。
  - 拿不到 free 时栈，无法诊断 double-free / UAF。
  - 没法做时间序列：什么时候涨上去、什么时候降下来。
  - 适合"长跑后还残留"的纯泄漏定位，不适合"短期峰值"和"内存抖动"诊断。

这是 Raphael 选择"只保留 live 集"换取低开销的核心权衡。优点是节点池 32768 个就够覆盖大多数业务，O(1) 摘除即可释放。缺点就是上面四条。

### 2.2 malloc_debug：可配置三档

| 档位 | 已释放分配的数据 | 适用场景 |
|---|---|---|
| 默认（只开 `backtrace`） | 不保留（同 Raphael） | 退出时倒泄漏 / `dumpheap -n` |
| `free_track[=N]` | 保留**最近 N 个 free**：完整原始大小 + 原 allocation 栈 + free 时栈 + 内存填 0xef | UAF / double-free / 堆破坏定位 |
| `record_allocs[=M]` | 保留**全量时序**：每条 alloc/free/realloc/memalign + TID | 时间序列分析、峰值复现、对账 |

具体到用户的问题"是否计算了释放的数据"：
- **malloc_debug 在 `record_allocs` 模式下，free 也是一等公民事件**：写入 `TID: free PTR`。离线脚本可以按时间轴跑出"任意时刻的活分配集合"，进而算出**峰值**、**周转**、**短命对象比例**。
- **Raphael 在任何模式下都不计算 free 数据**，只在 dump 那一瞬间快照"现在还活着的"。

### 2.3 改造启示（与 roadmap 衔接）

Raphael 要做"长跑大型 App 内存泄漏工具"，至少要补：
1. 全局原子计数器：累计 alloc 字节、累计 free 字节、当前 live、峰值 live。free 路径必须维护这些计数（即使节点已经丢了也要计数）。
2. 节点上加 `tid / ts_ns / kind(malloc|mmap)`，方便后期按线程 / 时间窗口聚合。
3. 多轮 snapshot（roadmap §3.10）= malloc_debug 的 `dumpheap -n` 多次触发，不擦旧 report，diff 出净增。
4. 可选 `record_allocs`-like 模式：用 spsc / mpsc ring buffer 把 free 也记下来，按窗口落盘后清零；不要永久驻留以免 OOM。
5. `free_track` 思想可借鉴：保留尾部 N 个 free 节点（不真归还到池里）专供 UAF / 双释放排查。

---

## 3. 解析期栈回溯流程对比

"解析期"在这里包括两段：**采集时的 unwind** 和 **dump 后的符号化**。Raphael 把这两段分别放在设备 native 层 和 离线 [library/src/main/python/raphael.py](library/src/main/python/raphael.py)；malloc_debug 把第一段放在设备 native 层，第二段交给 host 工具（`native_heap_dump.py` / `ndk-stack`）。

### 3.1 采集时 unwind

**Raphael — arm64**（[library/src/main/unwind64/backtrace_64.cpp](library/src/main/unwind64/backtrace_64.cpp)）：
- 算法：**纯帧指针链**。
  ```cpp
  auto fp = (uintptr_t) __builtin_frame_address(0);
  while (isValid(fp, st, sb) && depth < max_depth) {
      uintptr_t tt  = *((uintptr_t *) fp + 1); // saved LR
      uintptr_t pre = *((uintptr_t *) fp);     // saved FP
      if (pre & 0xfu || pre < fp + kFrameSize) break;
      if (tt != pc) stack[depth++] = tt;
      pc = tt; sb = fp; fp = pre;
  }
  ```
- 栈范围从 `pthread_getspecific(thread_t_key)` 取缓存，未命中调 `GetStackRange`。
- 优点：极快、无锁、不依赖外部库；malloc 路径上每次只读两个 8 字节即一帧。
- 缺点：
  - 任何 **`-fomit-frame-pointer`** 编译的代码会断链——很多 third-party prebuilt so（含部分 NDK 本身）默认就 omit FP。
  - 不能穿透 JNI 到 Java 帧。
  - 最大深度 16 写死。
  - 主线程通过 static 缓存栈范围，子线程通过 TLS；切线程后第一次会落到 `GetStackRange`。

**Raphael — armv7**（[library/src/main/unwind32/backtrace.c](library/src/main/unwind32/backtrace.c)）：
- 算法：自实现的 `libudf_unwind`，按 ARM **EXIDX**（unwind tables）和简化 CFI 解析；属于 Android L 以前 `libcorkscrew` 的简化复刻。
- 优点：不依赖 FP，可处理 `-fomit-frame-pointer` 编译的代码。
- 缺点：开销大于 FP；不能跨 Java；不支持 arm64 同等机制。

**malloc_debug**：
- 通过 `libunwindstack`（在 P 之前用 `libbacktrace`，Q 之后统一 libunwindstack）。
- 默认开销大约是 FP unwind 的 10x；README 明确写 "This option will slow down allocations by an order of magnitude."
- `backtrace_full` 选项触发**完整 DWARF/CFI unwind**，可以**穿透 Java 帧**（README：v1.2 heap dump 里 `bt_info` 会附 map 名 / 函数名 / 偏移）。
- 上限 256 帧，且每次都按真实可达上限抓。
- 不依赖 FP，任何编译选项的 so 都能 unwind。

### 3.2 采集时 Sample 路径开销

| 步骤 | Raphael (arm64) | malloc_debug (默认 backtrace) | malloc_debug (`backtrace_full`) |
|---|---|---|---|
| 栈底/顶查找 | TLS 缓存 | libunwindstack 内部 | 同左 |
| 每帧解码 | 读 2 个 word | 解一段 .eh_frame / ARM EXIDX | 完整 DWARF state machine |
| 符号 / 行号 | 不在采集时做 | 不在采集时做 | 采集时也不做 |
| 锁 | 仅 `alloc_table` 插入时的 mutex | 内部允许并发 | 同左 |
| 量级 | ~µs 级 | 数 µs ~ 数十 µs | 数十 µs ~ 百 µs |

### 3.3 dump 时栈回溯流程

**Raphael**：
1. 设备侧 `MemoryCache::print` 在锁内遍历整个 `alloc_table`，对每个 live 节点调 `write_trace`：
   - 对每个 PC `xdl_addr(pc, &info, &dl_cache)` → 拿到 `dli_fname / dli_fbase / dli_sname / dli_saddr`。
   - 命中符号则 `__cxa_demangle(dli_sname)` 还原 C++ mangle。
   - 输出格式：`#i pc <offset> <so> (<symbol>+<offset_in_func>)`，已经是接近 logcat 风格。
2. 落地 `report` 文件后，离线 `raphael.py` 再用 `addr2line` 把 offset 翻成行号、按设定的关键词做 grep（如 `libcocos2dcpp.so`）、调用栈合并打印 top-K。
3. 关键差异：**设备侧符号化了一次（xdl_addr），离线又用 addr2line 加一次行号**。如果原始 so 在仓库里被 strip 了，addr2line 会回退到 raw offset。

**malloc_debug**：
1. 设备侧只输出 raw PC 和 `/proc/PID/maps`（v1.2 起还会带 build fingerprint）。
2. 离线 `development/scripts/native_heap_dump.py` 或 `ndk-stack`：
   - 根据 `maps` 段把 PC 切回到 so + offset。
   - 找带符号 / 带 .debug_info 的同 build-id so，调 `llvm-symbolizer` / `addr2line` 出 symbol+line。
3. 如果开了 `backtrace_full`，设备侧已经把每帧附 `bt_info {"map" rel_pc "func" offset}`，离线只补行号即可。

### 3.4 关键差异（解析期）

| 维度 | Raphael | malloc_debug |
|---|---|---|
| 设备侧能否丢符号 so | **不能**（线下要 addr2line 还原行号；如果不在意行号，xdl_addr 已能出符号名） | **能**，设备侧只要 PC + maps |
| Java 帧 | 拿不到（FP unwind 到 art runtime 即断或乱跳） | `backtrace_full` 能拿到 |
| `-fomit-frame-pointer` 代码 | arm64 直接断；armv7 靠 EXIDX 还能走 | 完整 unwinder，不依赖 FP |
| 设备侧延迟 | dump 时整张 hash 表持锁 + 每个节点做 `xdl_addr+demangle+fprintf`，**长跑/大表会撑出 ANR 风险** | dump 不会做符号化，速度可控 |
| 离线工具链 | `raphael.py` 直接对接 `addr2line` + grep，输出已聚合的 top-N | 官方脚本只做 PC→symbol，**不做聚合**；聚合靠用户自写 |
| build fingerprint / build-id 对账 | 无 | v1.2 起带 fingerprint，可严格匹配符号包 |

---

## 4. 优缺点系统对比

### 4.1 Raphael 的优点

1. **可在生产/灰度的 release 包里跑**（debuggable 即可），不需要 root、`setprop`、`wrap.sh` 或重启 zygote。
2. **可精准选定 so 名单**（PLT/GOT 模式），开销和噪声远低于全局 shim。
3. **mmap 全覆盖**，能抓到 SoLoader / 字体 / resources.arsc / `MAP_ANONYMOUS` 大块等 malloc 看不到的占用。
4. **设备侧已基本符号化**，对一次性现场抓栈足够；离线脚本能聚合 top-K。
5. **FP unwind 极快**，对热点 alloc 路径冲击远小于 malloc_debug。
6. **AllocPool 是 lock-free CAS**（[library/src/main/cpp/AllocPool.hpp](library/src/main/cpp/AllocPool.hpp)），单次 alloc 的临界区只剩"链表头插入"。

### 4.2 Raphael 的缺点（对照 malloc_debug）

1. **不保留 free 数据**：无法做 UAF、double-free、瞬时峰值、时间序列分析。
2. **AllocPool 容量写死 32768**：大型 App 长跑必撑满，撑满后**静默丢弃**——report 会撒谎。
3. **栈深度写死 16 + FP unwind**：业务深栈或 `-fomit-frame-pointer` 的 so 会被截/断。
4. **dump 时整张表持锁 + 同步符号化**：表越大，锁占用越久；长跑 App 上有 ANR 风险（roadmap §3.1 已列）。
5. **不分 malloc / mmap 表**：单张 `alloc_table` 同时存两种，noise（dlopen mmap、只读文件 mmap）和真业务泄漏混在一起。
6. **没有 build-id 对账**：离线 `addr2line` 用错版本的 so 是常见踩坑点。
7. **`clean_cache` 每次 print 后擦 mSpace**：天然不支持多轮快照 / diff。
8. **`Raphael::stop` 对 malloc 出来的 `mSpace` 用 `delete`**：UB。
9. **无累计指标**：拿不到累计分配字节、活跃峰值、按 so/TID 的聚合——而 malloc_debug `record_allocs` 拿得到。

### 4.3 malloc_debug 的优点

1. **覆盖所有 native alloc，包括 libc 自己**，且不漏改任何 so。
2. **`record_allocs` 给出完整时序**：能复原任意时刻的内存画像。
3. **`free_track` 给出最近 N 个 free 时栈** + 0xef 填充：是排查 UAF 的事实标准。
4. **`guard / fill_on_alloc / fill_on_free / expand_alloc / verify_pointers`**：堆破坏类问题的"瑞士军刀"。
5. **`backtrace_full` 穿透 Java 帧**，且不依赖 FP。
6. **没有写死容量**（除 `record_allocs` 的 8M~50M，且可调）。
7. **可按分配大小过滤**（`backtrace_min_size / max_size / size`）——只对怀疑泄漏的 size 段抓栈，开销可控。
8. **集成 `libmemunreachable`**（`check_unreachable_on_signal`、`dumpsys meminfo --unreachable`），能做"GC-like"的不可达分析。

### 4.4 malloc_debug 的缺点（相比 Raphael）

1. **必须 root / userdebug / `wrap.sh`**：对在线生产 App 不可用。
2. **全局 shim**：开销大，FP unwinder 单次抓栈"慢一个数量级"。
3. **不 hook mmap**：抓不到 SO 加载、字体、Cocos2d-x texture cache、自管 mmap 池。
4. **设备侧不符号化**：每次都要回 host 跑符号脚本，工程上慢，且要严格管理 build-id 对账。
5. **聚合靠用户**：原始 dump 是事件流，没有按栈聚合的"top-K leak"视图。
6. **配置成本高**：选项多、属性长度限制、API level 差异（很多选项 O/P/Q/U 才有）。
7. **不能做实时 IM @ owner**：是离线诊断工具，不是平台化监控。

### 4.5 何时选哪个

| 场景 | 推荐 |
|---|---|
| 业务 App 灰度抓 native 泄漏，要求 release 也能跑 | **Raphael**（+ roadmap 提到的改造） |
| 平台开发，需要 UAF / 堆破坏 / 双释放定位 | **malloc_debug**（`free_track + guard`） |
| 需要"任意时刻活分配总量 / 峰值 / 周转" | **malloc_debug `record_allocs`** |
| 抓 mmap 类增长（so 加载、texture、字体、自管池） | **Raphael**（malloc_debug 不覆盖） |
| 抓 Java→JNI→native 完整调用链 | **malloc_debug `backtrace_full`** |
| CI 自动化、按 so / owner 分发 | **Raphael 二开**（malloc_debug 没这层） |

---

## 5. 给 Raphael 二开的具体借鉴清单

按"成本/收益比"由高到低（与 [next/optimization-and-automation-roadmap.md](next/optimization-and-automation-roadmap.md) 联动）：

1. **加 free 计数（无需保留栈）**：在 `MemoryCache::remove` / `mmap_proxy::munmap` 路径上维护 `std::atomic<uint64_t> total_alloc_bytes / total_free_bytes / live_peak_bytes`，dump 时一并写入 report 头。**改 5 行，能立刻补上"是否计算释放"的基础能力**，对应 malloc_debug 的累计统计。
2. **可选 `free_track` 模式**：在 `MemoryCache` 里加一个尾部 N=256 的环形缓冲，保留最近 N 个 free 节点（原 alloc 栈 + free 栈），用于 UAF 排查。**不要常开**，仅诊断态打开。
3. **可选事件流模式**（对标 `record_allocs`）：在 hook proxy 后接一个 mpsc ring buffer，写 `TID:OP:PTR:SIZE:TS`，按窗口落盘后清零；窗口大小做 1MB / 10s 二选一。
4. **拆分 malloc / mmap 表 + `AllocNode::kind`**：直接对齐 malloc_debug 的"不 hook mmap"边界，让两类问题各自有干净的视图。
5. **栈深度可配 + 上调到 32/64 + libunwindstack fallback**：对标 `backtrace[=MAX_FRAMES]` 和 `backtrace_full`，业务深栈不再被截。
6. **设备侧不再 demangle / xdl_addr**：把符号化挪到离线（参考 malloc_debug 设备侧只输出 PC + maps 的思路），把 dump 锁时间砍到接近"纯遍历"。同时**保留 build-id**到 report 头，避免离线对错符号。
7. **多轮 snapshot + 不擦 mSpace**：对标 `dumpheap -n` 多次触发；roadmap §3.10 已列。
8. **per-so 归属字段**：malloc_debug 没有，但对大项目自动分发是刚需；roadmap §3.13 已列。

完成 1–4 之后，Raphael 在"是否计算释放数据"这个维度就**有等价于 malloc_debug 的能力**，但仍保留"不需要 root、能 hook mmap、设备侧已聚合"的差异化优势。

---

## 6. 一张速查表

```
┌─────────────────────┬──────────────────────┬──────────────────────────┐
│ 问题                │ Raphael 当前         │ malloc_debug 等价        │
├─────────────────────┼──────────────────────┼──────────────────────────┤
│ 谁记录?             │ insert_memory_back-  │ debug_malloc shim        │
│                     │ trace (HookProxy.h)  │ (libc 分发表替换)        │
│ 记录什么?           │ addr/size/trace[16]  │ LeakInfo + 可选 fill/    │
│                     │                      │ guard/free_track         │
│ free 怎么处理?      │ remove → 节点回池    │ 默认同；可选延迟+栈      │
│ 释放数据保留?       │ 否                   │ 可选 (free_track /       │
│                     │                      │  record_allocs)          │
│ 容量上限?           │ 32768 活分配 (硬编)  │ 堆限制 / 8M~50M 时序     │
│ unwind 算法?        │ FP 链 (arm64) /      │ libunwindstack +         │
│                     │ libudf_unwind (armv7)│ DWARF (可穿 Java)        │
│ 栈深度?             │ 16 (硬编)            │ 16 默认，可到 256        │
│ 设备侧符号化?       │ 是 (xdl_addr +       │ 否 (仅 PC + maps;        │
│                     │ __cxa_demangle)      │ backtrace_full 例外)     │
│ dump 触发?          │ JNI / Broadcast      │ 信号 / 退出 / dumpheap   │
│ 离线工具?           │ raphael.py + addr2-  │ native_heap_dump.py /    │
│                     │ line                 │ ndk-stack                │
│ 适用 release App?   │ 是                   │ 否 (需 root / wrap.sh)   │
│ 覆盖 mmap?          │ 是                   │ 否                       │
│ UAF 检测?           │ 否                   │ free_track + fill_on_free│
│ 堆破坏检测?         │ 否                   │ guard / fill / expand    │
│ 按 so 聚合?         │ 离线脚本可分组       │ 无                       │
└─────────────────────┴──────────────────────┴──────────────────────────┘
```

---

## 附：本文引用的源码定位

- HOOK 入口 / 栈采集：[library/src/main/cpp/HookProxy.h](library/src/main/cpp/HookProxy.h)
- 节点表 / 锁 / dump：[library/src/main/cpp/MemoryCache.cpp](library/src/main/cpp/MemoryCache.cpp)、[library/src/main/cpp/MemoryCache.h](library/src/main/cpp/MemoryCache.h)
- 容量常量：[library/src/main/cpp/Cache.h](library/src/main/cpp/Cache.h)（`ALLOC_INDEX_SIZE`, `ALLOC_CACHE_SIZE`, `MAX_TRACE_DEPTH`）
- 节点池：[library/src/main/cpp/AllocPool.hpp](library/src/main/cpp/AllocPool.hpp)
- arm64 FP unwind：[library/src/main/unwind64/backtrace_64.cpp](library/src/main/unwind64/backtrace_64.cpp)
- armv7 EXIDX unwind：[library/src/main/unwind32/backtrace.c](library/src/main/unwind32/backtrace.c)
- 离线解析：[library/src/main/python/raphael.py](library/src/main/python/raphael.py)
- 入口与状态机：[library/src/main/cpp/Raphael.cpp](library/src/main/cpp/Raphael.cpp)、[library/src/main/java/com/bytedance/raphael/Raphael.java](library/src/main/java/com/bytedance/raphael/Raphael.java)
- 对比来源：[bionic malloc_debug README](https://android.googlesource.com/platform/bionic/+/master/libc/malloc_debug/README.md)
