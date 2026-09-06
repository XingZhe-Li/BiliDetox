#!/usr/bin/env python3
"""
跨平台一键构建脚本：编译 BiliDetox 模块并把它嵌入 Bilibili APK。

替代原先仅限 Windows 的 patch.bat。只用 Python 3 标准库，Windows /
Linux / macOS 均可运行。运行前提：

- Python 3.7+
- JDK 17+（java / javac 在 PATH 里）
- Android SDK（local.properties 或 ANDROID_HOME，由 Gradle 读取）
- 未修改的 Bilibili APK（本项目针对 9.6.0 / 9060300 验证）

用法：

  python patch.py [--libpatch] [path-to-original-bilibili.apk]

  --libpatch  对 libbili.so 打网络探测补丁。默认不打：该补丁与 B 站
              版本强绑定（按 BuildId 校验，当前仅支持 9.6.0 /
              9060300），换版本会直接报错退出，所以做成显式开启。
              背景：B 站自带的这个 native 探测线程在读不到
              /proc/net/tcp 的机型（Android 10+ 的 SELinux 限制）上
              走的兜底路径有栈溢出 bug，会直接把应用崩掉，取证与
              补丁细节见 tools/patch_libbili.py 的文档注释。

最终 APK 路径在脚本结束时打印，文件名由 NPatch 根据输入名派生。
"""

import argparse
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent
NPATCH_JAR = ROOT / "tools" / "npatch.jar"
LAUNCHER_SRC = ROOT / "tools" / "NPatchLauncher.java"
LAUNCHER_OUT = ROOT / "tools" / "build"
MODULE_APK = ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
DEFAULT_ORIG = ROOT / "original" / "iBiliPlayer-bili.apk"
OUT_DIR = ROOT / "output"

STEP = 0
STEP_TOTAL = 3


def step(title: str) -> None:
    global STEP
    STEP += 1
    print()
    print(f"[{STEP}/{STEP_TOTAL}] {title}")


def run(cmd) -> int:
    """执行子命令，输出直通终端（Gradle / NPatch 的日志需要实时滚动）。"""
    printable = " ".join(f'"{c}"' if " " in str(c) else str(c) for c in cmd)
    print(f"+ {printable}")
    return subprocess.run([str(c) for c in cmd]).returncode


def die(msg: str, code: int = 1) -> None:
    print(f"[ERROR] {msg}")
    sys.exit(code)


def build_module() -> None:
    step("编译模块 (:app:assembleDebug)")
    gradlew = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    if not gradlew.exists():
        die(f"找不到 Gradle wrapper: {gradlew}")
    if os.name != "nt":
        # git clone 出来的 gradlew 可能没有可执行位
        mode = gradlew.stat().st_mode
        if not mode & 0o111:
            gradlew.chmod(mode | 0o111)
    rc = run([gradlew, "-p", ROOT, ":app:assembleDebug", "--console=plain"])
    if rc != 0:
        die("模块编译失败", rc)
    if not MODULE_APK.exists():
        die(f"模块 APK 未生成: {MODULE_APK}")


def compile_launcher() -> None:
    step("编译 NPatchLauncher（桌面 JDK 的 BKS 修复）")
    LAUNCHER_OUT.mkdir(parents=True, exist_ok=True)
    rc = run(["javac", "-cp", NPATCH_JAR, "-d", LAUNCHER_OUT, LAUNCHER_SRC])
    if rc != 0:
        die("NPatchLauncher 编译失败", rc)


def apply_libbili_patch(orig_apk: Path) -> Path:
    step("补丁 libbili.so（禁用触发栈溢出的网络探测线程）")
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    sys.path.insert(0, str(ROOT / "tools"))
    import patch_libbili

    libpatched = OUT_DIR / f"{orig_apk.stem}-libpatched{orig_apk.suffix}"
    rc = patch_libbili.patch_apk(str(orig_apk), str(libpatched))
    if rc != 0:
        die("libbili.so 补丁失败", rc)
    return libpatched


def embed_module(npatch_input: Path) -> Path:
    step("NPatch 嵌入模块（-l 0，原因见 docs/BUILD.md）")
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    classpath = f"{NPATCH_JAR}{os.pathsep}{LAUNCHER_OUT}"
    rc = run([
        "java", "-cp", classpath, "NPatchLauncher",
        npatch_input, "-m", MODULE_APK, "-o", OUT_DIR, "-f", "-l", "0",
    ])
    if rc != 0:
        die("NPatch 失败", rc)

    # NPatch 的输出名形如 <输入名去后缀>-<版本>-npatched.apk，版本号在 jar
    # 内部，不写死；按修改时间挑最新的产物即可（刚生成的必然最新）。
    candidates = list(OUT_DIR.glob("*-npatched.apk"))
    if not candidates:
        die("NPatch 未产出 *-npatched.apk")
    return max(candidates, key=lambda p: p.stat().st_mtime)


def main() -> int:
    # Windows 控制台常为 GBK，个别字符编码失败时降级替换，别让脚本整个崩掉。
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(errors="replace")

    parser = argparse.ArgumentParser(
        description="编译 BiliDetox 模块并嵌入 Bilibili APK（跨平台，替代 patch.bat）",
    )
    parser.add_argument(
        "apk", nargs="?", default=None,
        help=f"原版 Bilibili APK 路径（默认 {DEFAULT_ORIG.name}）",
    )
    parser.add_argument(
        "--libpatch", action="store_true",
        help="对 libbili.so 打网络探测补丁（版本锁定 9.6.0，详见 tools/patch_libbili.py）",
    )
    args = parser.parse_args()

    global STEP_TOTAL
    STEP_TOTAL = 4 if args.libpatch else 3

    if sys.version_info < (3, 7):
        die("需要 Python 3.7+")

    orig_apk = Path(args.apk) if args.apk else DEFAULT_ORIG
    if not orig_apk.exists():
        die(f"原版 Bilibili APK 不存在: {orig_apk}\n"
            "        把未修改的 APK 放到 original/ 下，或作为参数传入。")
    if not NPATCH_JAR.exists():
        die(f"NPatch jar 不存在: {NPATCH_JAR}")
    for tool in ("java", "javac"):
        if shutil.which(tool) is None:
            die(f"PATH 里找不到 {tool}，请安装 JDK 17+ 并配置好环境变量")

    started = time.monotonic()
    build_module()
    compile_launcher()
    npatch_input = orig_apk
    if args.libpatch:
        npatch_input = apply_libbili_patch(orig_apk)
    final_apk = embed_module(npatch_input)

    print()
    print(f"完成，用时 {time.monotonic() - started:.0f}s。最终 APK：")
    print(f"  {final_apk}")
    print()
    print("安装：")
    print(f'  adb install -r "{final_apk}"')
    return 0


if __name__ == "__main__":
    sys.exit(main())
