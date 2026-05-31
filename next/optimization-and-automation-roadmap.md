# MemoryLeakDetector 二次开发优化与自动化工作流规划

## 1. 背景与目标

当前项目可以作为 Android native 内存泄漏采集内核继续二次开发，但不建议直接以现状接入长期自动化或全进程长时间运行。原因是核心链路仍以较早期 Android 运行时、hook 机制和离线脚本为假设，面对 Android 10+ execute-only memory、高版本 jemalloc、`MAP_FIXED`、16KB page size 和长时间压测场景时，存在稳定性、正确性和性能问题。

二次开发目标分为三层：

1. 修复采集内核的正确性和稳定性，让它能在主流 Android 版本、ABI 和厂商 ROM 上稳定运行。
2. 将报告、符号化、基线对比和泄漏判定做成可自动化的 CLI/CI 流程。
3. 将工具从“手工排查脚本”升级为“可持续治理平台”的基础组件。

## 2. 当前架构理解

当前工作流如下：

```text
Java API / Broadcast
        |
        v
Raphael.start(configs, space, regex)
        |
        +-- regex != null: registerSoLoadProxy, 对目标 so 做 PLT/GOT hook
        |
        +-- regex == null: registerInlineProxy, 对 malloc/free/mmap/munmap 等做 inline hook
        |
        v
HookProxy 捕获 malloc/calloc/realloc/memalign/free/mmap/mmap64/munmap/pthread_exit
        |
        v
MemoryCache 记录未释放地址、大小、调用栈
        |
        v
Raphael.print 输出 report 和 maps
        |
        v
python/raphael.py 聚合 report, python/mmap.py 聚合 maps
```

主要源码锚点：

- `library/src/main/java/com/bytedance/raphael/Raphael.java`: Java API 启停入口。
- `library/src/main/java/com/bytedance/raphael/RaphaelReceiver.java`: adb broadcast 自动化入口。
- `library/src/main/cpp/Raphael.cpp`: native 生命周期、输出目录清理、`report/maps` 生成。
- `library/src/main/cpp/HookProxy.h`: 分配、释放、mmap、munmap hook 代理。
- `library/src/main/cpp/MemoryCache.cpp`: 分配记录缓存、删除、打印。
- `library/src/main/cpp/AllocPool.hpp`: 固定容量节点池。
- `library/src/main/inline64/And64InlineHook.cpp`: arm64 inline hook 实现。
- `library/src/main/xDL`: ELF/动态链接符号查找。
- `library/src/main/python/raphael.py`: report 聚合和 addr2line 符号化。
- `library/src/main/python/mmap.py`: `/proc/<pid>/maps` 分类聚合。

## 3. Issue 结论细化

### 3.1 P0: #57 长时间运行 ANR, CPU 100%

现状（按当前代码核对，已修正第一版文档的几处偏差）：

- `MemoryCache` 使用一个全局 `pthread_mutex_t alloc_mutex`，但保护的仅是 `alloc_table[ALLOC_INDEX_SIZE]` 链表头指针的修改，不是节点池本身。
- `alloc_table` 是 `1 << 16` 个链表桶，使用 `(address >> ADDR_HASH_OFFSET) & 0xFFFF` 作为桶索引，`ADDR_HASH_OFFSET` 在 arm 上是 4，arm64 上是 6。
- `AllocPool` 已经是基于 CAS 的 lock-free free-list + 顺序 bump index 分配，容量上限固定为 `1 << 15 = 32768` 个 `AllocNode`，达到上限后 `apply()` 返回 nullptr，`insert()` 直接丢弃并打印 `Alloc cache is full!!!!!!!!`。
- `insert` 的临界区其实很短：申请节点、栈拷贝、写 `addr/size/trace` 都在锁外，临界区只链入桶头，是 O(1)。
- `remove` 先做一次 **未加锁** 的 `alloc_table[hash] == nullptr` 快速路径，再上全局锁做链表线性查找。这是 TOCTOU 竞态，桶在快速路径之后被填充时会漏删，进而表现为“泄漏越跑越多”。
- `print` 在持锁期间遍历所有桶并调用 `write_trace`，里面包含 `xdl_addr` + `__cxa_demangle` + `fprintf`，IO 和符号解析都在临界区里。`print` 期间所有 hook 路径会被阻塞。
- 长时间运行后，如果 mmap 误删（#53）、共享地址表导致 mmap 大对象长期占桶（见 3.11）、栈帧深度不足造成栈聚合不准、或采样阈值过低，未释放记录会持续堆积，最终触发节点池打满 + remove 线性查找变慢 + print 持锁久 的三重叠加。

根因判断：

- 真正瓶颈不是 `insert`，而是 `remove` 的链表线性查找 + `print` 的长持锁。
- `AllocPool` 满载是一种 "silent data loss"：丢弃发生在采集侧，离线脚本完全看不出 report 是否可信。
- 节点池总量 32768 对大型 app 的高频 malloc + 多 so + 多线程场景偏小，但只是放大器，不是直接 ANR 根因。
- TOCTOU 的 fast path 在大多数情况下能省锁，但反过来也是漏删源。

改造方案：

1. 修复 `remove` TOCTOU：将无锁 nullptr 检查改为加锁后再读，或改为 `std::atomic<AllocNode*>` 桶头并使用 release/acquire 语义，至少保证“桶头一旦非空必然加锁查找”。
2. 拆分 `MemoryCache` 为 `AddressIndex` 和 `StackAggregator`。
   - `AddressIndex`: key 是分配地址，value 是大小、栈 id、分配类型（malloc / mmap）、来源 so。
   - `StackAggregator`: key 是归一化调用栈 hash，value 是总大小、次数、样本地址、调用栈、归属 so。
   - 释放时从 `AddressIndex` 删除，再反向扣减 `StackAggregator`。
3. 使用分片锁代替全局锁。
   - 例如 64 或 256 个 shard，每个 shard 一个 mutex 和地址表。
   - 按地址 hash 选择 shard，降低锁竞争。
   - `print` 改为对每个 shard 短临界区拷贝快照到 thread-local buffer，文件写入、xdl 地址解析、demangle 全部在锁外完成。
4. 节点池可配置 + 暴露指标。
   - 配置最大记录数、最大总内存、满载策略（丢弃 / 抽样保留 / 触发自动 snapshot）。
   - 暴露 dropped count、pool usage、max bucket length、insert/remove 延迟直方图，通过 native 接口或 logcat 周期输出。
5. 加入运行时保护。
   - 采样阈值动态调高，比如根据 `limit` 和 pool usage 自动调整。
   - `print` 冷却时间限制，防止业务被频繁阻塞。
   - watchdog 发现 cache 接近上限时自动 snapshot 并降级为只聚合（不再插入新地址，只更新 StackAggregator 计数）。

验收标准：

- 在 8 小时 soak test 中无 ANR、无 `Alloc cache is full` 持续刷屏。
- `malloc/free` 高频压测下 CPU 开销低于设定阈值，例如小于 5%-10%。
- 多线程分配释放压测下锁等待时间可观测且不随运行时间线性恶化。
- `print` 期间业务线程分配/释放延迟不会出现明显尖刺。

### 3.2 P0: #54 `je_free` 无法 hook

现状（修正第一版中夸大的影响）：

- `invoke_je_free()` 仅在 inline hook 模式（`registerInlineProxy`）被调用；PLT/GOT 模式（`registerSoLoadProxy`）不会触发，因此 PLT/GOT 模式下不会出现 `invoke failed at xdl_sym` 日志。
- 在 inline 模式下，`invoke_je_free` 根据 API level 打开 libc，再 `xdl_sym(handle, "je_free")`：
  - 成功：用 `je_free` 地址替换 `sInline[4][1]`，inline hook 落在 jemalloc 内部释放函数上。
  - 失败：保持 `sInline[4][1] = free`，inline hook 落在 `free` 自身。
- Android Q+ 的 `free` 是 trampoline，会跳到 scudo/jemalloc 内部，因此 inline hook `free` **依然能拦截到所有应用层 free 调用**。`je_free` hook 主要解决“想覆盖 libc 内部互相调用”这一类极端情况。
- 因此 #54 的真实影响是：
  1. 日志噪声 `invoke failed at xdl_sym`，对自动化判定 hook 健康度带来干扰；
  2. 极少数 libc 内部互相 free 的场景可能漏删；
  3. 用户错以为这是泄漏堆积根因，实际堆积更可能来自 mmap 区间统计错误（#53）、共享地址表（3.11）、`pthread_exit` stack 删除不准（3.14）。
- 社区 PR #55 的思路（`xdl_sym` 失败后 fallback `xdl_dsym`）值得合入，但定位是“减少误报警 + 让 inline 模式更稳健”，不是单点修复 ANR。

风险：

- 失败时只打日志、不产生健康度信号，CI/自动化无法判断报告是否可信。
- 对 libc 内部实现强绑定，跨 API 和厂商 ROM 风险较高。
- 全进程 inline hook 在 XOM 设备上 crash 优先级远高于 `je_free` 命中率。

改造方案：

1. 合入 `xdl_sym` + `xdl_dsym` 双查找。
2. 增加候选符号列表。
   - `je_free`
   - `free`
   - `__libc_free`
   - Android 版本相关 allocator 符号需要用设备矩阵确认。
3. hook 初始化后输出结构化状态。
   - API level、ABI、libc path、命中的 symbol、hook backend、是否成功。
4. 如果关键释放符号无法 hook，禁止进入长期采集模式。
   - 返回错误码给 Java。
   - CLI/CI 标记为环境不支持，而不是生成不可信报告。
5. 降低全进程 inline hook 依赖。
   - 目标 so 优先使用 PLT/GOT hook。
   - 全进程模式根据设备兼容矩阵启用。

验收标准：

- Android 8-15, armv7/arm64 主流设备上输出 hook 成功矩阵。
- 在高频 malloc/free 压测中，稳定状态下未释放记录不持续增长。
- `je_free` 查找失败时能明确输出 fallback 或 unsupported 状态。

### 3.3 P0: #53 mmap 统计泄漏不正确

现状：

- `mmap_proxy` 和 `mmap64_proxy` 成功后直接 `insert_memory_backtrace(address, size)`，并且和 malloc 共用同一个 `MemoryCache::alloc_table`。
- `munmap_proxy` 成功后只调用 `cache->remove((uintptr_t) address)`，按地址精确匹配。
- 记录是按起始地址保存，不理解 mmap 区间。
- `MAP_FIXED` 会覆盖已有映射，但当前逻辑不会删除被覆盖的旧记录。
- 部分 `munmap(address + offset, length)` 会拆分或移除子区间，但当前逻辑只按完整起始地址删除，子区间会变“常驻”泄漏。
- 任何成功的 mmap 都会被记录，包括 so 加载、文件 mmap、bionic 内部 mmap，因此 report 中 mmap 类记录会被加载行为大量污染（见 3.14）。

典型误报场景：

```c
void *address = mmap(NULL, getpagesize() * 200, PROT_NONE,
                     MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE, -1, 0);

void *storage = mmap((char *)address + getpagesize(), getpagesize() * 100,
                     PROT_READ | PROT_WRITE,
                     MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED, -1, 0);

munmap(address, getpagesize() * 200);
```

改造方案：

1. 新增 `MmapTracker`，独立于 malloc 的地址表，避免 3.11 的污染。
2. 记录区间 `[start, end)`，而不是只记录起始地址；区间元数据包含 `prot/flags/fd_kind(anon/file/dmabuf/stack)`。
3. 支持以下操作：
   - `insertRange(start, size, stack, flags, fd_kind)`
   - `eraseRange(start, size)`
   - `replaceRangeForMapFixed(start, size, stack, flags)`
   - `splitRange` 和 `mergeAdjacent`。
4. `mmap` 如果带 `MAP_FIXED` 或 `MAP_FIXED_NOREPLACE`：
   - `MAP_FIXED`: 先删除覆盖区间，再插入新区间。
   - `MAP_FIXED_NOREPLACE`: 只有系统调用成功才插入新区间。
5. `munmap` 成功后按区间删除。
   - 删除整个区间。
   - 删除头部或尾部时缩短记录。
   - 删除中间时拆成两个区间。
6. hook `mremap`：参数包含 `old_address/old_size/new_size/flags(MREMAP_FIXED/MREMAP_MAYMOVE)`，需要先 erase 旧区间，再 insert 新区间，并复制原栈到新记录。
7. 默认忽略 fd-backed read-only file mapping、`/dev/ashmem/*`、`/system/lib*` so 加载相关 mmap（详见 3.14）。

验收标准：

- #53 复现代码不误报。
- 覆盖完整 unmap、部分 unmap、MAP_FIXED 覆盖、相邻 mmap、失败 mmap/munmap 的 native 单测。
- `report` 中 mmap 类型记录与 `/proc/self/maps` 快照差异可解释。

### 3.4 P1: #49 Python 脚本遗漏 addr2line 函数信息

现状：

- `raphael.py` 使用 `aarch64-linux-android-addr2line -C -e <so> -f <addr>`。
- `addr2line -f` 输出第一行是函数名，第二行是文件行。
- 当前 `addr_to_line()` 只返回 `output.split('\n')[1]`，函数名被丢弃。

改造方案：

1. 返回函数名和文件行。
   - 示例格式：`function at file:line`。
   - 原始字段也保留为结构化 JSON 字段。
2. 使用 `subprocess.run([...])` 参数数组替代字符串拼接，避免路径空格和 shell 注入问题。
3. 按 `(abi, so_name, address)` 缓存符号化结果。
4. 支持 `--addr2line` 参数，不再要求用户改脚本里的 NDK 路径。

验收标准：

- 对包含函数名但文件行为 `??:0` 的地址，报告仍保留函数名。
- Windows 路径、带空格路径、UTF-8 输出都能正常处理。
- 原 txt 格式兼容保留，新增 JSON 输出用于自动化。

### 3.5 P1: #50 addr2line 获取函数名错误

现状判断：

- issue 认为这不是项目单点 bug，而是 `addr2line` 对 inline、优化和虚函数场景可能给出误导性函数名。
- 当前 report 中的 pc 已经是 `pc - dli_fbase` 风格，离线脚本再次符号化时没有结合 maps、build-id、load bias 做严谨校验。
- 当前脚本不支持 inline 展开链，也不保留符号化工具的多行输出。

改造方案：

1. 优先使用 LLVM 工具链。
   - `llvm-symbolizer --inlines --demangle --obj=<so> <addr>`。
   - 或 `llvm-addr2line -C -f -i -e <so> <addr>`。
2. 建立符号文件索引。
   - key: build-id。
   - value: unstripped so path, git revision, abi, app version。
3. 明确地址模型。
   - report 中保存 raw pc、relative pc、so base、symbol source。
   - 离线符号化时按 ELF load bias 计算，不依赖模糊路径匹配。
4. 保留 inline call chain。
   - 报告 UI/文本中展示主帧和 inline 子帧。
   - 聚合时可选择按 top frame 或 full inline chain 聚合。

验收标准：

- 对已知 inline 复现场景，报告能展示完整 inline 链。
- 相同 report 在不同机器上符号化结果一致。
- 找不到精确 build-id 时，报告标记为 `symbol_untrusted`。

### 3.6 P1: #37/#44 Vivo 和 Galaxy 设备启动崩溃

现状：

- demo 默认 `Raphael.start(..., regex = null)`，会进入 `registerInlineProxy()`。
- arm64 inline hook 会读取并改写目标函数 text 段。
- issue 崩溃显示 `execute-only memory access error; likely due to data in .text`，栈在 `A64HookFunctionV/A64HookFunction/registerInlineProxy/Raphael::start`。
- 这类设备和 Android 10+ XOM 策略下，读取可执行段可能触发崩溃。

改造方案：

1. 改默认策略。
   - demo 和文档默认使用 regex 目标 so 模式。
   - Android 10+ 默认禁用全进程 inline hook。
2. 引入 hook backend 抽象。
   - `PLT_GOT`: 用于目标 so，稳定性优先。
   - `INLINE`: 仅在白名单设备/API 使用。
   - `BYTEHOOK` 或 `SHADOWHOOK`: 作为后续可替代后端。
3. 启动前做环境探测。
   - API level、ABI、ROM manufacturer、page size、XOM 行为、libc 符号查找。
   - 不满足条件时返回明确错误码。
4. 建立设备黑白名单。
   - Vivo PD2011 Android 10。
   - Samsung Galaxy A51 Android 10。
   - Xiaomi MI 8 SE Android 10。

验收标准：

- 上述问题设备不再启动崩溃。
- unsupported 设备能降级为 PLT/GOT 或直接拒绝全进程模式。
- CI 每次构建输出兼容矩阵。

### 3.7 P2: #56 16KB page size 支持

现状：

- `library/CMakeLists.txt` 已加入 `-Wl,-z,max-page-size=16384`。
- 但 inline hook 仍有硬编码 4096：`And64InlineHook.cpp` 中 `__page_size 4096`，32 位 inline hook 中 `PAGE_SIZE 4096`。
- `mprotect`、trampoline pool 对齐、page start/end 计算仍可能在 16KB page device 上出错。

改造方案：

1. 所有 page size 使用运行时值。
   - `sysconf(_SC_PAGESIZE)` 或 `getpagesize()`。
   - 缓存到全局只读变量。
2. 替换所有 `PAGE_SIZE` 和 `__page_size` 参与的对齐计算。
3. trampoline pool 改为 `mmap` 分配并按运行时 page size 对齐。
4. 增加构建检查。
   - `readelf -l libraphael.so` 验证 LOAD segment alignment。
   - Android 15 16KB page emulator/device smoke test。

验收标准：

- 16KB page emulator 可加载 `libraphael.so`。
- hook 初始化、print、stop 正常。
- `readelf` 检查通过。

### 3.8 P2: #43/#40 Runtime performance 和 libudf 耗时

现状：

- #43 指出节点池太小、`remove_alloc` 查找回收慢。
- #40 指出 32 位 libudf 首次获取主线程栈范围耗时很大。
- PR #42 的思路是优化主线程栈范围读取方式，但需要结合当前代码重新验证。

改造方案：

1. `MemoryCache v2` 解决 #43。
2. 32 位 unwind 侧缓存主线程和工作线程栈范围。
3. 减少每次采样的回溯深度，默认深度从配置中明确限制。
4. 支持按大小采样和按频率采样。
   - 例如只记录 `size >= threshold`。
   - 对高频小分配按 reservoir sampling 或周期采样。

验收标准：

- 首次回溯耗时有指标。
- 高频分配 benchmark 可比较优化前后耗时。
- 默认配置下对业务帧率/启动耗时影响可控。

### 3.10 P0: 多轮采集与历史快照（来自代码 review，新增）

现状：

- `Raphael::print` 第一步是 `clean_cache(env)`，遍历 `mSpace` 目录把里面所有文件 `remove` 掉，然后再写 `report` 和 `maps`。
- 这意味着每次 `print()` 都会清空之前的 report/maps，无法做“多轮采集 + 差分”。
- `RaphaelReceiver` 的 `getSpace(ctx)` 用 `getExternalFilesDir("raphael")`，每次都指向同一目录。
- 没有 snapshot API，只有 `start / stop / print` 三个状态。

影响：

- 无法在不停服务的情况下，对“场景 A 前后” “场景 B 前后”进行 diff。
- 无法做长时间运行 + 周期 snapshot。
- 自动化里只能用单点 report，丢失趋势信息。
- 大项目中“随时拿到分配栈”的能力被这一行 `clean_cache` 直接砍断。

改造方案：

1. 拆分 `clean_cache` 和 `dump`：
   - 启动时执行一次 `clean_cache`（或者完全不清理，由调用方决定）。
   - `print` 不再删历史，按 `report-<unix_ts>-<seq>.txt` 命名。
2. 新增 `Raphael.snapshot(tag)` API：
   - Java 暴露 `snapshot(String tag)`，native 写入 `snapshot-<tag>-<ts>/report`、`maps`。
   - 输出 metadata：包含当前 alive bytes 总量、cache usage、丢弃计数、env。
3. 增加 `diff` 接口（native 或离线脚本）：
   - 输入两次 snapshot，输出新增/消失/增长的栈聚合。
4. 广播扩展：
   - `com.bytedance.raphael.ACTION_SNAPSHOT --es tag <name>`。
   - `com.bytedance.raphael.ACTION_DIFF --es from <tag> --es to <tag>`。
5. 提供“增量 print”模式：只输出自上次 print 以来新增的未释放记录。

验收标准：

- 一次 `start` 之后可以连续 `snapshot("login_before")`、`snapshot("login_after")`、`diff login_before login_after`，无需 `stop`。
- 历史 snapshot 全部保留，目录按时间戳排序。
- `report` 文件名包含时间戳与场景 tag。

### 3.11 P0: malloc / mmap 共用地址表（来自代码 review，新增）

现状：

- `MemoryCache::alloc_table` 同时存放 malloc 与 mmap 记录，AllocNode 结构没有“类型”字段。
- `free_proxy` 在 `(isVss | isPss)` 任一开启时都会 `cache->remove(address)`：单开 mmap 模式时，对每一次 free 也会去地址表里查（虽然大概率查不到对应记录）。
- `munmap_proxy` 用 `cache->remove(address)` 删 mmap，但语义是“按精确起始地址”，无法处理区间。
- 报告里 mmap 项和 malloc 项混在一起，离线脚本只能按大小、路径粗启发分组。

影响：

- 任何 mmap/munmap 改造（3.3）都被绑定在同一张表里，无法独立优化。
- 锁竞争和 remove 链表长度被两类工作负载同时拉高。
- per-so 归属、retained 排行、按类型差分都做不到。

改造方案：

1. 在 `AllocNode` 增加 `kind` 字段：`MALLOC / MMAP / MEMALIGN / REALLOC`。
2. 拆分为两套子结构：
   - `MallocIndex`: 按地址 hash 的分片表。
   - `MmapTracker`: 按区间的红黑树或 interval set。
3. `free_proxy` 只查 `MallocIndex`；`munmap_proxy` 只查 `MmapTracker`。
4. `realloc_proxy` 在 `MallocIndex` 内执行 remove + insert。
5. `pthread_exit_proxy` 不再直接调用 cache->remove，由 `MmapTracker` 提供“按 stack_base 删除关联线程栈区间”的接口（见 3.14）。
6. 报告中显式标注 `kind`，供脚本/平台直接区分。

### 3.12 P0: stop/restart 路径的内存与 hook 完整性（来自代码 review，新增）

现状：

- `Raphael::start` 用 `(char *) malloc(length+1)` 分配 `mSpace`。
- `Raphael::stop` 里 `delete mSpace`：是 `malloc` 的内存却用 `delete` 释放，行为未定义。应该 `free(mSpace)`。
- `Raphael` 在 `xloader.cpp` 中是全局静态单例 `new Raphael()`，进程内只有一份。
- `Java Raphael.start/stop` 用 `AtomicBoolean sIsRunning` 限制重入，但 `stop` 之后再 `start`：
  - inline hook 没有卸载流程，只调用了 `xh_core_clear()`（仅清 xHook 状态），inline trampoline 仍然在原符号上。
  - `pthread_key_delete(guard)` 之后其它线程的 hook 路径若同时进入 `_proxy`，会访问已删除 key，行为未定义。
  - 重启时 `pthread_key_create(&guard, nullptr)` 再次创建 key，但 inline hook 内部 origin 函数指针未重置。

影响：

- 长期采集 + 远程重启场景下，stop/start 路径很容易踩 UB。
- 用 broadcast 反复触发也是潜在崩溃源。

改造方案：

1. `mSpace` 统一用 `new char[]` + `delete[]` 或 `std::string`，与 `delete` 配对；推荐改为 `std::string mSpace;`。
2. 明确状态机：`UNINITIALIZED / RUNNING / STOPPED`，禁止重启或显式实现完整的 hook 卸载。
3. 对 `pthread_key_delete(guard)` 改为只置位 `is_running = false`，hook 路径先判 flag 再用 guard。
4. `xh_core_clear()` 之外，inline hook 提供 `unhook` 路径（短期可不实现，但需要在 Java 层明确不支持 restart）。
5. 增加初始化失败回退：start 中途任何一步失败立即 `stop` + 释放资源，避免半启动状态。

### 3.13 P0: per-so 分配归属与模块负责人指派（来自需求，新增）

现状：

- AllocNode 只记录 `pc` 列表，没有“分配主导 so”字段。
- 离线 `raphael.py` 的 `group_record` 只是按帧路径优先级 + 一个写死的 `system_group` 列表分组，不是按业务 so 归属。
- 没有 owner 配置；定位“这个泄漏属于哪个团队”依赖人工读栈。

需求：

- 大项目要求“随时拿到各个 so 的分配大小和分配栈，且能立马指派给单独的模块负责人”。
- 必须支持按 so 聚合、按 owner 聚合，并和监控/工单系统对接。

改造方案：

1. 采集时记录 caller so：
   - 在 `insert_memory_backtrace` 收到栈后，从 `trace[0]` 起跳过 raphael 自身和 libc/libm 等基础库，找到第一个业务 so，把它的 `dli_fname` hash 后存入 `AllocNode::owner_so_id`。
   - 建立全局 `SoTable`：so 名 ↔ id ↔ build-id ↔ 路径。
2. native 层 `StackAggregator` 按 (owner_so_id, stack_hash) 维度聚合，提供：
   - `getSoUsage(so_name)`：返回 retained bytes、count、top stacks。
   - `dumpBySo()`：按 retained 大小排序输出。
3. 离线 / 平台侧 OWNERS：
   - 仓库 `next/` 增加 `owners.yaml`，结构例如：
     ```yaml
     - so: libfeed.so
       owner: feed-team
       contacts: [feed-oncall@example.com]
       paths:
         - src/feed/*
     ```
   - 离线工具读取 owners，输出 `summary.md` 时按 owner 分组，附带 retained、top stack、负责人邮箱。
4. 平台侧接入：
   - 报告入库时打 `owner` 标签，超阈值自动派单。
   - 对未匹配到 owner 的 so 标记为 `unowned`，触发治理任务。

验收标准：

- `raphaelctl report --by so` 能输出按 so 排序的 retained 列表。
- `raphaelctl report --by owner` 能输出按 owner 排序的 retained 列表。
- 模块负责人能从 dashboard 一键查看本团队所有 leak 栈，无需读全量 report。

### 3.14 P1: mmap 噪声、线程栈与 fd-backed 映射（来自代码 review，新增）

现状：

- `mmap/mmap64_proxy` 不区分 `flags`、`prot`、`fd`，所有成功的 mmap 都进 cache。
- bionic 在 `pthread_create` 时通过 mmap 分配线程栈，会被 hook 抓到；`pthread_exit_proxy` 用 `attr.stack_base` 调 `cache->remove`，但 `stack_base` 不一定等于 mmap 真实返回地址（页对齐 / guard page），存在删除失败、长期残留的可能。
- so 加载、字体加载、resources.arsc、`/dev/ashmem`、anon dmabuf 都会触发 mmap，被记录后会污染 report。
- 当前没有任何 fd-backed 过滤。

改造方案：

1. `MmapTracker` 默认排除：
   - 由 dlopen 调用栈触发的 mmap（在 `try_pltgot_hook_on_soload` 入口/出口标记 thread-local guard）。
   - `fd >= 0` 且 `flags & MAP_PRIVATE` 且 `prot == PROT_READ` 的只读文件 mmap。
   - 路径前缀位于 `/system/`, `/apex/`, `/vendor/`, `/data/app/.../base.apk` 的映射。
2. 提供配置项：用户可选保留 fd-backed mmap，用于排查字体/资源类问题。
3. `pthread_exit_proxy` 改为：
   - 在 `pthread_create` 路径插入 thread-local“当前线程栈区间”记录。
   - `pthread_exit` 时根据线程 id / TLS 回查并删除区间。
   - 不再依赖 `attr.stack_base` 直接 remove。

### 3.15 P1: 调用栈深度与回溯质量（来自代码 review，新增）

现状：

- `MAX_TRACE_DEPTH = 16`，写死在 `Cache.h`。
- `insert` 默认丢弃前 2 帧（raphael hook 自身），实际业务栈最多 14 帧。
- arm64 `unwind_backtrace` 完全依赖 FP（frame pointer）链：`__builtin_frame_address(0)` 起跳，按 `*((uintptr_t *) fp)` 与 `*((uintptr_t *) fp + 1)` 走链，遇到 FP 断链或没开启 -fno-omit-frame-pointer 的 so 会停止。
- 大型 app 普遍 20-40 帧，FP unwind 在 libart、ART JIT、部分系统库容易断链。

改造方案：

1. `MAX_TRACE_DEPTH` 改为运行时可配置（通过 configs 字段位预留），上限提升到 32 / 64。
2. arm64 加入备用 unwinder：
   - 短期：保留 FP unwind，作为快路径。
   - 中期：引入 libunwindstack 或 `_Unwind_Backtrace`（dwarf）作为慢路径，用于深栈与断链兜底。
   - 长期：评估接入 perfetto / simpleperf 的 unwinder 组件。
3. 调用栈归一化：
   - PC → so + offset 后做 hash；同一 so 在不同进程/版本下偏移一致。
   - 对栈 prefix 做归一化（去除随 hook 链增加/减少的固定帧）。
4. 提供深度统计：每次 unwind 记录“是否到栈底”，离线侧能识别“因深度截断导致的同栈合并”。

### 3.9 Info: #48 是否仍推荐使用

结论：

- upstream 维护活跃度不足以支撑生产级依赖。
- 但项目设计仍有二开价值，尤其是已有 xHook/xDL、hook 代理、report 格式和离线聚合基础。
- 推荐作为内部 fork 继续演进，不建议直接依赖 JitPack 旧版本。

治理建议：

1. 内部维护独立 artifact 坐标。
2. 所有社区 PR 先进入内部验证分支。
3. 每个 release 绑定设备兼容矩阵和符号化工具版本。
4. 文档明确支持范围：API、ABI、hook mode、Android page size。

## 4. 二次开发项目列表

### 4.1 Raphael Core v2

范围：

- `MemoryCache v2`：分片锁 + AddressIndex/StackAggregator + per-so 字段
- `MmapTracker`：独立区间表 + fd-backed 过滤 + mremap 支持
- hook backend 抽象（INLINE / PLT_GOT / BYTEHOOK / SHADOWHOOK）
- 可观测指标：hook 健康度、cache usage、dropped count、unwind 截断率
- 错误码和降级策略
- stop/restart 路径修复（mSpace 释放、状态机、unhook 或显式不支持 restart）
- 多轮 snapshot 与 diff（详见 3.10）

交付物：

- `library` 新版本 AAR。
- native 单测和 instrumentation smoke test。
- 设备兼容矩阵。

优先级：P0。

### 4.2 Symbolizer v2

范围：

- 重构 `raphael.py`，拆为 parser / symbolizer / aggregator / reporter。
- 支持 `llvm-symbolizer`、`llvm-addr2line`、传统 `addr2line`。
- 输出 txt、json、ndjson。
- 支持 build-id 符号索引。
- 内建 per-so 与 OWNERS 聚合（消费 `owners.yaml`）。
- 输出按 so / 按 owner 两种视图，并附自动派单元数据（owner 邮箱、issue tracker 字段）。

交付物：

- `tools/symbolizer` 或 `library/src/main/python` 新脚本。
- 单测覆盖 report parse、symbol cache、inline chain。
- 文档化符号目录结构。

优先级：P1。

### 4.3 Leak Workflow CLI

范围：

- 封装 adb 命令。
- 自动 start、run scenario、print、pull、analyze、compare。
- 生成 artifacts 目录。
- 支持 YAML 配置。

建议命令形态：

```shell
raphaelctl run \
  --package com.example.app \
  --regex ".*libtarget\\.so$" \
  --config 0xCF0400 \
  --scenario scenarios/login.yaml \
  --symbols symbols/ \
  --baseline baselines/main.json \
  --out artifacts/raphael/<build-id>/
```

交付物：

- `raphaelctl` CLI。
- CI 示例配置。
- baseline compare 规则。

优先级：P1。

### 4.4 CI Native Leak Gate

范围：

- Jenkins/GitHub Actions/GitLab CI 集成。
- 设备池调度。
- PR/nightly 两种模式。
- 阈值判定和报告归档。

判定规则：

- 总 native retained bytes 超过 baseline 一定比例。
- Top stack 持续增长。
- 同一场景多轮后未释放大小斜率大于阈值。
- mmap retained 与 maps 不一致时标记为不可信。

交付物：

- CI pipeline 模板。
- HTML/Markdown 报告。
- PR comment 或告警通知。

优先级：P1。

### 4.5 Device Compatibility Lab

范围：

- API 21-35。
- armv7、arm64。
- Android 10+ XOM。
- Android 15+ 16KB page size。
- 重点厂商：Samsung、Vivo、Xiaomi、OPPO、Pixel。

交付物：

- 设备兼容矩阵。
- hook backend 策略表。
- 自动 smoke test。

优先级：P1。

### 4.6 Dashboard 和治理平台

范围：

- 报告入库。
- 趋势图、Top stack、diff、owner 映射。
- build-id 与 git commit 关联。
- 问题分派和关闭状态。
- 按 so / 按 owner 看板，支持“此次发布相比上次，X 团队 retained 增长 N MB”这种维度。
- 与工单/IM 集成：超阈值自动建单或@owner。

交付物：

- Web dashboard。
- 数据库 schema。
- 符号化结果查询 API。

优先级：P2。

## 5. 自动化内存泄漏工作流设计

### 5.0 大项目场景下的核心使用模式

面向大项目的目标使用方式：

1. 测试包默认集成 Raphael，**不自动 start**，避免对普通 QA 影响。
2. 通过 broadcast 或开发者面板按需 `start / snapshot(tag) / stop`。
3. 自动化场景脚本在关键节点调用 `snapshot("phase-N")`。
4. 设备端不做符号化，仅产出原始 `report` + `maps` + `env.json`。
5. 上传到符号服务器后做 per-so / per-owner 聚合，并回写 dashboard。
6. dashboard 按 owner 派单；超阈值自动@对应团队。

### 5.1 单次本地调试流程

前置条件：

- 已安装带 Raphael 的测试包。
- adb 连接设备。
- 已准备 unstripped symbols。
- Android 10+ 优先使用 regex 模式监控目标 so。

命令流程：

```shell
adb shell am force-stop <pkg>
adb shell monkey -p <pkg> 1

adb shell am broadcast -a com.bytedance.raphael.ACTION_START -f 0x01000000 \
  --es configs 0xCF0400 \
  --es regex ".*libTarget\\.so$"

# 执行业务场景，例如 uiautomator/maestro/appium/monkey
adb shell dumpsys meminfo <pkg> > artifacts/meminfo-before.txt

adb shell am broadcast -a com.bytedance.raphael.ACTION_PRINT -f 0x01000000

adb pull /sdcard/Android/data/<pkg>/files/raphael/report artifacts/report
adb shell cat /proc/$(adb shell pidof <pkg>)/maps > artifacts/maps

python3 library/src/main/python/raphael.py \
  -r artifacts/report \
  -o artifacts/leak-doubts.txt \
  -s symbols/

python3 library/src/main/python/mmap.py -m artifacts/maps > artifacts/maps-summary.txt
```

注意：当前 `Raphael::print` 每次都会先清空 `space` 目录里旧文件（见 3.10）。Core v2 之前，单设备做多轮采集需要在每次 print 后立刻 `adb pull` 到本地另存，再做多轮 diff。

### 5.1.1 多轮 snapshot + diff（Core v2 之后）

```shell
adb shell am broadcast -a com.bytedance.raphael.ACTION_START -f 0x01000000 \
  --es configs 0xCF0400 --es regex ".*libTarget\\.so$"

# 执行场景 A
adb shell am broadcast -a com.bytedance.raphael.ACTION_SNAPSHOT --es tag phaseA

# 执行场景 B
adb shell am broadcast -a com.bytedance.raphael.ACTION_SNAPSHOT --es tag phaseB

adb pull /sdcard/Android/data/<pkg>/files/raphael/ artifacts/raphael/

raphaelctl diff \
  --from artifacts/raphael/snapshot-phaseA-*/ \
  --to   artifacts/raphael/snapshot-phaseB-*/ \
  --owners owners.yaml \
  --out artifacts/diff-phaseA-phaseB/
```

输出包含：按 owner 排序的 retained 增量、新出现的栈、消失的栈、被截断的栈占比。

### 5.2 CI 自动化流程

```text
Build APK/AAB
    |
    v
Upload unstripped symbols by build-id
    |
    v
Install app on device pool
    |
    v
Start Raphael with safe hook mode
    |
    v
Run deterministic scenario N rounds
    |
    v
Print and collect report/maps/meminfo/logcat
    |
    v
Symbolize and normalize stacks
    |
    v
Compare with baseline
    |
    v
Generate report and fail/pass CI gate
```

### 5.3 产物目录规范

```text
artifacts/raphael/<app>/<version>/<device>/<timestamp>/
  env.json
  report.raw.txt
  maps.raw.txt
  meminfo.before.txt
  meminfo.after.txt
  logcat.txt
  leak.normalized.json
  leak-doubts.txt
  maps-summary.txt
  compare.json
  summary.md
```

`env.json` 建议字段：

- app package、versionName、versionCode、git commit。
- device id、manufacturer、model、Android version、API level、ABI。
- page size。
- hook backend。
- configs、regex、limit、depth。
- symbols build-id。

### 5.4 泄漏判定策略

建议不要只看一次 `report` 的总量。更稳妥的是多轮场景和 baseline diff：

1. 预热阶段不计入最终判定。
2. 执行场景 3-5 轮，每轮后 `print` 一次。
3. 对每个归一化调用栈计算 retained bytes 趋势。
4. 同时比较当前分支和 baseline 分支。
5. 满足任一条件则判定失败：
   - total retained bytes 大于 baseline + 固定阈值。
   - 某个 top stack 增量超过阈值。
   - 多轮 retained bytes 单调增长且斜率超过阈值。
   - hook 初始化失败但仍产出报告，标记为 invalid report。

示例阈值：

```yaml
gate:
  total_retained_bytes_delta: 10485760
  stack_retained_bytes_delta: 2097152
  min_growth_rounds: 3
  max_runtime_overhead_percent: 10
  require_symbol_trust: true
```

### 5.5 场景脚本建议

场景应覆盖高风险 native 内存路径：

- 启动和首屏。
- 图片加载、解码、缩放、释放。
- 视频播放、seek、退出。
- 相机预览、拍摄、关闭。
- WebView 打开、关闭。
- 地图、OpenGL、Vulkan 或 GPU buffer 场景。
- 业务页面反复进入退出。

每个场景需要定义：

- 前置状态。
- 操作步骤。
- 重复次数。
- 等待条件。
- 清理动作。
- 允许的 native retained 增量。

## 6. 推荐里程碑

### M1: 可信采集内核 + per-so 基础

内容：

- 修复 `Raphael::stop` `delete mSpace` UB，完善状态机。
- 修复 `MemoryCache::remove` TOCTOU。
- `MemoryCache` 改为分片锁、锁内 snapshot 锁外写文件。
- 拆分 `MallocIndex` / `MmapTracker`，`MmapTracker` 支持区间 / MAP_FIXED / 部分 munmap。
- `insert_memory_backtrace` 记录 caller so（per-so 基础设施）。
- 取消 `clean_cache` 在每次 print 删文件，新增 `snapshot(tag)` API + broadcast。
- 合入 `je_free` `xdl_dsym` fallback + 结构化日志。
- demo 默认改为 regex 安全模式。
- mmap 噪声过滤（dlopen 触发 mmap、只读文件 mmap）。

验收：

- #53 复现通过。
- 8 小时 soak test 无 ANR，不出现记录无限增长，不出现 `Alloc cache is full` 刷屏。
- Samsung/Vivo/Xiaomi Android 10 不启动崩溃。
- 一次 `start` 后可连续 `snapshot(tag1)` / `snapshot(tag2)`，旧快照不被覆盖。
- report 中每条记录携带 `kind` 与 `owner_so`。

### M2: 可自动化报告 + owner 聚合

内容：

- `raphael.py` 重构为 parser/symbolizer/aggregator/reporter。
- 支持函数名、文件行、inline chain，输出 JSON/NDJSON。
- 仓库营造 `owners.yaml`，离线工具按 so / owner 聚合输出。
- 提供 `raphaelctl diff` 对两份 snapshot 做增量分析，报告按 owner 排序。
- 引入 baseline compare。

验收：

- #49 修复；#50 相关 inline 场景结果可解释。
- `raphaelctl report --by so` / `--by owner` 工作正常。
- CI 可消费 JSON 并产生按 owner 分组的 summary。

### M3: CI 设备池接入与指派闭环

内容：

- `raphaelctl` CLI、设备池 smoke test、PR/nightly pipeline、artifacts 归档。
- CI gate 失败时自动在 PR/issue 中 @ 对应 owner。
- 提供 owner-aware HTML/Markdown 报告。

验收：

- 对指定 app 和场景，一条命令完成采集、分析、判定。
- 出现超阈值时能在 30 分钟内自动跟进到模块负责人。
- 环境不支持时明确标记 invalid/unsupported。

### M4: 平台化治理 + 生产采集

内容：

- Dashboard、符号服务器、趋势分析、owner 归因。
- 生产采集采样策略（按大小阈值 + reservoir）。
- 多进程支持（:push 等）。
- 符号上传集成到 Gradle。
- 隐私/安全：路径清洗、地址脱敏。

验收：

- 泄漏趋势可按版本 + owner 追踪。
- 同类泄漏可聚合去重。
- 修复后可自动关闭或标记回归。

## 7. 风险与决策点

### 7.1 是否继续使用 inline hook

建议：默认不使用全进程 inline hook。只有在兼容矩阵通过且确实需要全进程采集时启用。

原因：

- Android 10+ execute-only memory 风险明确存在。
- 厂商 ROM 行为差异大。
- PLT/GOT 或成熟 hook 库更适合自动化稳定运行。

### 7.2 是否继续保留原 txt report 格式

建议：保留兼容，但新增 JSON/NDJSON 作为自动化主格式。

原因：

- txt 对人工阅读友好。
- 自动化需要结构化字段、可信状态、build-id、inline frames 和 compare 结果。

### 7.3 是否直接依赖 upstream

建议：内部 fork 独立维护。

原因：

- issue 中已有“不维护”反馈。
- 关键 PR 未必合入主线。
- 自动化治理需要稳定 release 和兼容矩阵。

## 8. 近期可执行 TODO

按代码 review 后的优先级重排：

1. 修复 `Raphael::stop` 中 `delete mSpace` 的 UB（应使用 `free` 或改为 `std::string`），并完善 stop/restart 状态机。
2. 修复 `MemoryCache::remove` 的 unlocked nullptr 快速路径 TOCTOU。
3. `Raphael::print` 改为锁内 snapshot、锁外写文件 + 符号化。
4. 拆分 malloc / mmap 地址表（`MallocIndex` + `MmapTracker`）。
5. 取消 `clean_cache` 在每次 print 强制删旧文件，改为按时间戳/tag 落盘。
6. 新增 `snapshot(tag)` Java API + broadcast，支持多轮采集。
7. 在 `insert_memory_backtrace` 中记录 caller so（per-so 归属基础设施）。
8. 给 `invoke_je_free` 增加 `xdl_dsym` fallback 和结构化日志。
9. 将 demo 默认启动参数改为目标 so regex 模式，并在 README 中给出大项目推荐参数。
10. `raphael.py` 修复函数名丢失，新增 JSON 输出，并加 OWNERS 聚合。
11. mmap 噪声过滤：默认排除 dlopen 触发的 mmap 与只读文件 mmap。
12. 将 `MAX_TRACE_DEPTH` 改为可配置，并评估接入 libunwindstack。
13. 扫描所有 4096 page size 硬编码并替换为运行时 page size。
14. 定义 `raphaelctl` YAML 配置格式和 artifacts 目录。
15. 起草 `owners.yaml` 与匹配规则，覆盖主要业务 so。
16. 准备 3 台设备的 smoke test：Pixel/Android 15、Samsung/Android 10、Vivo/Android 10。

## 9. 第一版文档错误修正清单（review 留痕）

本节列出第一版文档中与代码事实不符或描述不准确处，以及当前修正：

| # | 第一版表述 | 实际情况 | 修正位置 |
|---|---|---|---|
| 1 | `insert` 每次分配都要在全局锁内复制栈、插入链表，是锁热点 | `apply()` 与栈拷贝都在锁外，临界区只是 O(1) 链表 head 插入；锁热点其实在 `remove` 线性查找 + `print` 长持锁 | 3.1 现状 |
| 2 | `remove` 先无锁读桶头再加全局锁，并在链表内线性查找 | 描述方向对，但漏掉了 unlocked nullptr fast path 是 TOCTOU，会漏删导致“泄漏越跑越多” | 3.1 现状 |
| 3 | 固定节点池满后只打印日志，缺少扩容能力 | `AllocPool` 已是 lock-free CAS free-list，问题是容量上限 32768 写死且无观测指标 | 3.1 现状 |
| 4 | `je_free` 未 hook 会导致分配记录无法在释放时移除，是 #57 堆积根因 | inline hook 失败时 fallback 到 `free` 本身，Android Q+ 上 `free` 是 trampoline，依然能 catch 所有应用层 free。真正的堆积根因更可能是 #53、3.11、3.14 | 3.2 现状 |
| 5 | `je_free` 失败影响“所有模式” | 仅 inline 模式调用 `invoke_je_free`，PLT/GOT 模式不会出现该日志 | 3.2 现状 |
| 6 | mmap 记录是独立的区间统计 | 实际与 malloc 共用 `alloc_table`，无 `kind` 字段；MmapTracker 改造前提是先拆表 | 3.3 / 3.11 |
| 7 | demo 默认配置可直接用于自动化采集 | demo 默认 `regex == null` 走全进程 inline hook，在 XOM 设备会启动崩溃；自动化默认应改为 regex 模式 | 3.6 / 8 |
| 8 | 离线脚本可以按 so 归属泄漏 | `raphael.py` 的 `group_record` 只是按帧路径优先级 + 写死系统 so 列表分组，不是按业务 so 归属；per-so 归属需要在采集和离线两侧同时建设 | 3.13 / 4.2 |
| 9 | 当前可做多轮采集 | `Raphael::print` 会先 `clean_cache` 删空目录；不改造前每次 print 只剩最后一份 | 3.10 |
| 10 | （未提及）`Raphael::stop` `delete mSpace` UB、stop 后 restart 无 unhook | 是真实风险，长期采集 + broadcast 反复触发会踩 | 3.12 |
| 11 | （未提及）mmap 噪声、pthread 栈区间删除不准 | 实际会显著影响 report 信噪比与长跑稳定性 | 3.14 |
| 12 | （未提及）`MAX_TRACE_DEPTH = 16` 对大项目偏小 | 大型 app 业务栈常 20-40 帧，FP unwind 在系统库容易断链；需要可配置 + 备用 unwinder | 3.15 |

## 10. 大项目能力对齐表

用户目标拆解到具体改造点：

| 用户能力诉求 | 直接依赖的改造点 | 关键代码位置 | 里程碑 |
|---|---|---|---|
| 随时拿到各个 so 的分配大小 | per-so 归属（3.13）+ 多轮 snapshot（3.10）+ malloc/mmap 拆表（3.11） | `HookProxy.h::insert_memory_backtrace`, `MemoryCache.cpp`, `Raphael.cpp::print/clean_cache` | M1 |
| 随时拿到分配栈 | snapshot/diff（3.10）+ 栈深度与质量（3.15）+ Symbolizer v2（4.2） | `Cache.h::MAX_TRACE_DEPTH`, `backtrace_64.cpp`, `python/raphael.py` | M1-M2 |
| 立马指派给单独的模块负责人 | OWNERS 配置（3.13）+ Symbolizer v2 owner 聚合（4.2）+ Dashboard 派单（4.6） | `next/owners.yaml`（新增）, `tools/symbolizer`（新增）, dashboard 后端 | M2-M4 |
| 长时间运行不 ANR | MemoryCache v2（3.1）+ mmap 区间（3.3）+ stop/restart 修复（3.12）+ mmap 噪声过滤（3.14） | `MemoryCache.cpp`, `HookProxy.h::mmap*_proxy`, `Raphael.cpp::stop` | M1 |
| 自动化 CI gate | Workflow CLI（4.3）+ CI Gate（4.4）+ snapshot/diff（3.10） | `raphaelctl`（新增）, CI pipeline 模板 | M3 |
| 设备兼容性可控 | hook backend 抽象（3.6）+ Device Lab（4.5）+ 16KB page（3.7） | `inline64/`, `xHook/`, `CMakeLists.txt`, smoke test 矩阵 | M1-M3 |
