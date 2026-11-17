#!/usr/bin/env python3
"""给 libnx1.so（arm64-v8a）打两个补丁，使重签名构建也能使用原生加密。

背景
----
libnx1.so 首次调用 stCheck 时会反射拿 Application → PackageManager →
android/content/pm/Signature，把当前 APK 的签名与原版比对。本项目的 APK 是自行
构建并用调试密钥签名的，比对必然失败，随后库会走向「不匹配」分支并最终调用一个
故意写空指针的桩函数，直接杀死整个进程：

    signal 11 (SIGSEGV), fault addr 0x0
    #00  libnx1.so (+0xa84)      ; str w0, [x8]，x8 == 0
    #01  libnx1.so stCheck+548   ; bl 0xa80

补丁内容（均为 arm64-v8a）
--------------------------
1. 0x454c  b.lo 0x4588  ->  nop
   签名比对结果 cmp w29,#0x100 之后的「不匹配」跳转。改成无条件顺序执行，
   走「视为匹配」分支：清掉初始化标志、释放临时缓冲，然后用库内置的默认密钥
   继续。实测密文与在无 Application 的 app_process 环境下完全一致，且
   encrypt/decrypt 往返可还原原文。

2. 0x4034  cbz x0, 0x41f4  ->  nop
   紧跟在 malloc(17) 之后的自杀分支。保留默认密钥路径时若分配失败也不再
   直接写空指针。

其他 ABI（armeabi / x86）未改动：这些架构上探针会超时，主进程按
CryptoSupport 的结论回退为不调用 native，不会闪退（但也无法加密）。
"""

import shutil
import sys
from pathlib import Path

# (文件偏移, 期望的原始字节, 补丁后字节, 说明)
PATCHES = [
    (0x454C, "e3010054", "1f2003d5", "签名比对不匹配分支 -> nop"),
    (0x4034, "000e00b4", "1f2003d5", "malloc 失败自杀分支 -> nop"),
]

NOP = "1f2003d5"


def patch_library(path: Path) -> bool:
    data = bytearray(path.read_bytes())
    changed = False
    for offset, expected, replacement, note in PATCHES:
        current = data[offset:offset + len(expected) // 2].hex()
        if current == replacement:
            print("  [跳过] 0x%04X 已是补丁状态：%s" % (offset, note))
            continue
        if current != expected:
            print("  [失败] 0x%04X 期望 %s，实际 %s（库版本不匹配？）"
                  % (offset, expected, current))
            return False
        data[offset:offset + len(replacement) // 2] = bytes.fromhex(replacement)
        print("  [完成] 0x%04X  %s -> %s  %s" % (offset, expected, replacement, note))
        changed = True

    if changed:
        path.write_bytes(data)
    return True


def main() -> int:
    root = Path(__file__).resolve().parent.parent
    library = root / "app/src/main/jniLibs/arm64-v8a/libnx1.so"
    # 备份放在 jniLibs 之外，否则会被一起打包进 APK
    backup = root / "tools/native-original/libnx1.so.orig"

    if not library.is_file():
        print("找不到 %s" % library, file=sys.stderr)
        return 1

    if not backup.is_file():
        backup.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(library, backup)
        print("已备份原始库 -> %s" % backup.relative_to(root))

    print("修补 %s" % library)
    return 0 if patch_library(library) else 1


if __name__ == "__main__":
    sys.exit(main())
