# MemoryLeakDetector 使用指南

## 完整工作流程

```
1. 集成 SDK → 2. 启动监控 → 3. 触发业务场景 → 4. 输出报告 → 5. 拉取文件 → 6. 离线分析
```

## 一、集成 SDK

```gradle
allprojects {
    repositories {
        maven { url 'https://jitpack.io' }
    }
}

dependencies {
    implementation 'com.github.bytedance:memory-leak-detector:0.2.1'
}
```

## 二、启动监控

### 方式一：代码调用

```java
// 监控指定 so（正则匹配）
Raphael.start(
    Raphael.MAP64_MODE | Raphael.ALLOC_MODE | 0x0F0000 | 1024,
    "/sdcard/raphael",
    ".*libxxx\\.so$"
);

// 监控整个进程
Raphael.start(
    Raphael.MAP64_MODE | Raphael.ALLOC_MODE | 0x0F0000 | 1024,
    "/sdcard/raphael",
    null
);
```

### 方式二：广播控制

```shell
# 监控指定 so
adb shell am broadcast -a com.bytedance.raphael.ACTION_START -f 0x01000000 --es configs 0xCF0400 --es regex ".*libXXX\\.so$"

# 监控整个进程
adb shell am broadcast -a com.bytedance.raphael.ACTION_START -f 0x01000000 --es configs 0xCF0400
```

## 三、输出报告

```java
// 代码触发
Raphael.print();
```

```shell
# 广播触发
adb shell am broadcast -a com.bytedance.raphael.ACTION_PRINT -f 0x01000000
```

## 四、拉取文件到本地

```shell
# 拉取 report（内存分配记录）
adb pull /sdcard/raphael/report D:\Code\mem\raphael\report

# 拉取 maps（进程内存映射）
adb shell cat /proc/<pid>/maps > D:\Code\mem\raphael\maps
```

## 五、离线分析

### 1. 分析 report（raphael.py）

```shell
python3 library\src\main\python\raphael.py -r <report文件路径> -o <输出文件> -s <符号表目录>
```

**参数说明：**

| 参数 | 说明 | 必需 |
|------|------|------|
| `-r` | report 文件路径（设备端生成的原始数据） | 是 |
| `-o` | 输出文件名，默认 `leak-doubts.txt` | 否 |
| `-s` | 符号表目录，用于地址符号化（目录中的 .so 需与设备上同名） | 否 |

**示例：**

```shell
# 基本分析（不含符号化）
python3 library\src\main\python\raphael.py -r D:\Code\mem\raphael\report -o D:\Code\mem\raphael\leak-doubts.txt

# 带符号化（需要 NDK 中的 addr2line 工具在 PATH 中）
python3 library\src\main\python\raphael.py -r D:\Code\mem\raphael\report -o D:\Code\mem\raphael\leak-doubts.txt -s D:\Code\mem\symbols\
```

**输出格式解读：**

```
    201,852,591  totals           ← raphael 拦截到的未释放内存总和
    118,212,424  libandroid_runtime.so  ← 按来源 so 分组统计
      28,822,002  libhwui.so
            ...
      5,388,741  extras           ← 未匹配预设规则的记录

bdb11000, 70828032, 66            ← 首次分配地址, 该堆栈内存总和, 分配次数
0x000656cf /system/lib/libc.so (pthread_create + 246)
0x0037c129 /system/lib/libart.so (art::Thread::CreateNativeThread + 448)
```

### 2. 分析 maps（mmap.py）

```shell
python3 library\src\main\python\mmap.py -m <maps文件路径>
```

**参数说明：**

| 参数 | 说明 | 必需 |
|------|------|------|
| `-m` | maps 文件路径 | 是 |

**示例：**

```shell
python3 library\src\main\python\mmap.py -m D:\Code\mem\raphael\maps
```

**输出格式解读：**

```
========== maps ==========
    2,048,000   totals           ← 进程虚拟地址空间总占用
    1,024,000   native           ← .so 库映射
      512,000   dalvik           ← Dalvik 堆
      256,000   malloc           ← libc_malloc 匿名映射
      128,000   thread           ← 线程栈
            ...
       64,000   extras           ← 未分类的映射
```

## 六、停止监控

```java
Raphael.stop();
```

```shell
adb shell am broadcast -a com.bytedance.raphael.ACTION_STOP -f 0x01000000
```

## 参数含义

启动参数 `MAP64_MODE | ALLOC_MODE | 0x0F0000 | 1024`：

- `MAP64_MODE`：使用 64 位内存映射
- `ALLOC_MODE`：监控内存分配
- `0x0F0000`：采样间隔（每 0x0F0000 字节采样一次）
- `1024`：最大栈深度

## 注意事项

- 需要 SD 卡读写权限（Android 10 以下）或使用应用私有目录
- 符号化需要将 `aarch64-linux-android-addr2line` 所在目录加入 PATH（NDK 工具链目录）
- 监控期间会有一定性能开销，建议仅在调试阶段使用
