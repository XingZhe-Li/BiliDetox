"""
对 Bilibili 9.6.0 (9060300) 的 lib/arm64-v8a/libbili.so 做定点二进制补丁。

## 背景与取证结论（详见崩溃分析记录）

libbili.so 是 B 站的 native 网络探测库（OLLVM 混淆）。它周期性新建一个
native 线程去读 /proc/net/tcp 统计 TCP 连接。Android 10+ 上 targetSdk>=28
的应用落 SELinux untrusted_app 域，该读取被 AVC 拒绝（avc: denied
{ read } name="tcp"）。读不到时它走的兜底路径存在栈溢出 bug，触发
libc 栈保护：'stack corruption detected (-fstack-protector)' → SIGABRT。

vivo V2453A (Android 16, SM8735) 上多次复现，所有致命崩溃的 backtrace
完全一致：

    #02 libbili.so +0x338cc   <- __stack_chk_fail 调用点（栈被写坏）
    #03 libbili.so +0x328a0
    #04 libbili.so +0x34278
    #05 libbili.so +0x2e1cc
    #06 libbili.so +0x2497c   <- 位于函数 0x2465c 内
    #07 libbili.so +0x2461c   <- 线程入口 0x24570 内，`bl 0x2465c`

线程由 pthread_create(调用点 0x24548) 创建，start routine = 0x24570；
该线程的全部工作就是调用函数 0x2465c（探测+兜底全在这条链上），
返回值被调用方忽略。全库对 0x2465c 仅此一个调用点，无其他跳转。

## 补丁内容

把函数 0x2465c 的第一条指令替换为 `ret`（c0 03 5f d6），探测线程
变成空操作。代价：B 站丢失一项网络质量探测数据，无用户可见功能
损失。第一个 LOAD 段 off==vaddr，文件偏移即虚拟地址。

## 防呆

仅接受 BuildId 41c22b6877c1daf5357ed3146c530e1bf02e6cf7 且补丁点字节
匹配原序言（stp x28, x27, [sp, #-0x60]!）的 libbili.so，其余情况报错
退出，避免对其他版本误伤。

用法: python patch_libbili.py <in.apk> <out.apk>
      也可被同仓库的 patch.py 导入，调用 patch_apk(in_apk, out_apk)。
"""

import sys
import struct
import zipfile

TARGET_ENTRY = "lib/arm64-v8a/libbili.so"
PATCH_OFFSET = 0x2465C
EXPECTED_PROLOGUE = bytes.fromhex("fc6fbaa9")  # stp x28, x27, [sp, #-0x60]!
RET_INSN = bytes.fromhex("c0035fd6")           # ret
EXPECTED_BUILD_ID = "41c22b6877c1daf5357ed3146c530e1bf02e6cf7"


def read_build_id(so: bytes) -> str:
    """解析 .note.gnu.build-id（namesz<=8, 'GNU\\0', desc=20 字节）。"""
    # ELF64 header: e_shoff at 0x28, e_shnum at 0x3c, e_shentsize at 0x3a
    e_shoff = struct.unpack_from("<Q", so, 0x28)[0]
    e_shentsize = struct.unpack_from("<H", so, 0x3A)[0]
    e_shnum = struct.unpack_from("<H", so, 0x3C)[0]
    for i in range(e_shnum):
        base = e_shoff + i * e_shentsize
        sh_type = struct.unpack_from("<I", so, base + 0x04)[0]
        if sh_type != 7:  # SHT_NOTE
            continue
        sh_offset = struct.unpack_from("<Q", so, base + 0x18)[0]
        sh_size = struct.unpack_from("<Q", so, base + 0x20)[0]
        pos = sh_offset
        end = sh_offset + sh_size
        while pos + 12 <= end:
            namesz, descsz, ntype = struct.unpack_from("<III", so, pos)
            name = so[pos + 12 : pos + 12 + namesz]
            desc_off = pos + 12 + ((namesz + 3) & ~3)
            desc = so[desc_off : desc_off + descsz]
            pos = desc_off + descsz + ((4 - descsz % 4) % 4) if descsz else desc_off
            if name.rstrip(b"\x00") == b"GNU" and ntype == 3 and namesz <= 8:
                return desc.hex()
    return ""


def patch_apk(in_apk: str, out_apk: str) -> int:
    src = zipfile.ZipFile(in_apk, "r")
    try:
        info = src.getinfo(TARGET_ENTRY)
        so = src.read(TARGET_ENTRY)

        build_id = read_build_id(so)
        if build_id != EXPECTED_BUILD_ID:
            print(f"[ERROR] libbili.so BuildId 不匹配: {build_id}")
            print(f"        期望: {EXPECTED_BUILD_ID}")
            print("        本补丁只针对 Bilibili 9.6.0 (9060300)，请勿强改。")
            return 1

        current = so[PATCH_OFFSET : PATCH_OFFSET + 4]
        if current == RET_INSN:
            print("[INFO] libbili.so 已打过补丁，跳过")
        elif current != EXPECTED_PROLOGUE:
            print(f"[ERROR] 补丁点字节不匹配: {current.hex()}")
            print(f"        期望序言: {EXPECTED_PROLOGUE.hex()}")
            return 1
        else:
            so = so[:PATCH_OFFSET] + RET_INSN + so[PATCH_OFFSET + 4 :]
            print(f"[OK] 已将 libbili.so+0x{PATCH_OFFSET:x} 的探测函数入口替换为 ret")

        if in_apk == out_apk:
            print("[ERROR] 输入输出不能是同一个文件")
            return 2

        with zipfile.ZipFile(out_apk, "w") as dst:
            for item in src.infolist():
                if item.filename == TARGET_ENTRY:
                    dst.writestr(item, so)
                else:
                    # 逐条目原样搬运，保留压缩方式与时间戳
                    dst.writestr(item, src.read(item.filename))
        print(f"[OK] 已生成 {out_apk}")
    finally:
        src.close()
    return 0


def main() -> int:
    if len(sys.argv) != 3:
        print("Usage: python patch_libbili.py <in.apk> <out.apk>")
        return 2
    return patch_apk(sys.argv[1], sys.argv[2])


if __name__ == "__main__":
    sys.exit(main())
