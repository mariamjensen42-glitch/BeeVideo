#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
构建 mock spider jar —— 用于验证 `DexClassLoader` 加载链路。

为什么要有这个脚本：jar 源（CatVod 的 `type=3`）是整个接入里唯一无法靠
"编译通过"验证的部分 —— 它要的是**运行时**把外部 DEX 载进来、让它继承宿主
自带的 `com.github.catvod.crawler.Spider`。所以必须真的造一个 jar 出来测。

三步：
  1. javac   把 MockSite.java 编译成 class（classpath = 宿主已编译的 class + android.jar）
  2. d8      把 class 转成 dex 并打成 jar
  3. 产物     产出到 .workbuddy/scripts/mock_spider.jar（mock 服务就是从这里读的）

⚠️ **绝不要把 MockSite 放进 app/src** —— 那是把 mock 代码编进正式包。
它必须只存在于这个 jar 里。

用法:
    python build_mock_jar.py
"""
import os
import shutil
import subprocess
import sys
from pathlib import Path

PROJECT = Path(__file__).resolve().parents[2]
SDK = Path("E:/SoftWare/SDK")
JAVA_HOME = Path("C:/Program Files/Java/jdk-21")
GRADLE_HOME = Path("E:/AndroidDev/Gradle")

# mock jar 必须能编译宿主方法签名里的**第三方类型** —— `Spider.client()` 返回
# `okhttp3.OkHttpClient`、`Spider.safeDns()` 返回 `okhttp3.Dns`。宿主 classes
# 目录里没有这些类（它们在依赖 jar 里），不喂给 javac 就会报
# "找不到okhttp3.OkHttpClient的类文件"。
# 与 app 的 okhttp 版本保持一致；okio 是 okhttp 4.12.0 自己 poms 里声明的那版。
THIRD_PARTY = [
    ("com.squareup.okhttp3", "okhttp", "4.12.0"),
    ("com.squareup.okio", "okio-jvm", "3.6.0"),
]

SRC_DIR = PROJECT / ".workbuddy/mockjar/src"
BUILD_DIR = PROJECT / ".workbuddy/mockjar/build"
OUT_JAR = PROJECT / ".workbuddy/scripts/mock_spider.jar"

# 宿主编译产物的位置（AGP 9 的内置 kotlinc 任务输出）
HOST_CLASSES = (
    PROJECT
    / "app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"
)


def fail(msg: str) -> None:
    sys.stderr.write("[mockjar] 失败：" + msg + "\n")
    sys.exit(1)


def run(cmd: list, label: str) -> subprocess.CompletedProcess:
    """跑子进程并归一化输出。

    ⚠️ 两个坑叠在一起，缺一个就会得到假的失败信息：
      1. `text=True` 默认用 UTF-8 解码，而 javac/d8 在中文 Windows 上吐的是 **GBK**
         —— 报错时先炸 `UnicodeDecodeError`，看不到真正的编译错误。
      2. `capture_output=True` 时 stdout/stderr 在某些路径下是 **None**
         —— 直接拿去做字符串拼接会 `TypeError`，把真错误彻底埋掉。
    """
    r = subprocess.run(
        cmd, env=env(), capture_output=True, text=True,
        encoding="gbk", errors="replace",
    )
    r.stdout = r.stdout or ""
    r.stderr = r.stderr or ""
    if r.returncode != 0:
        fail(
            label + " 失败（exit " + str(r.returncode) + "）\n"
            + "---- stdout ----\n" + r.stdout.rstrip() + "\n"
            + "---- stderr ----\n" + r.stderr.rstrip()
        )
    return r


def env() -> dict:
    e = dict(os.environ)
    e["JAVA_HOME"] = str(JAVA_HOME)
    e["PATH"] = str(JAVA_HOME / "bin") + os.pathsep + e.get("PATH", "")
    return e


def newest_android_jar() -> Path:
    """取版本号最高的一个 platform。按数字段比较，避免 android-9 排到 android-37 后面。"""
    platforms = SDK / "platforms"
    if not platforms.is_dir():
        fail(f"找不到 {platforms}")
    candidates = []
    for d in platforms.iterdir():
        jar = d / "android.jar"
        if not jar.is_file():
            continue
        parts = d.name.replace("android-", "").split(".")
        key = tuple(int(p) if p.isdigit() else 0 for p in parts)
        candidates.append((key, jar))
    if not candidates:
        fail("platforms 下没有任何 android.jar")
    return max(candidates, key=lambda x: x[0])[1]


def d8_jar() -> Path:
    """取 build-tools 里最高的一个 d8.jar。"""
    bt = SDK / "build-tools"
    candidates = []
    for d in bt.iterdir():
        jar = d / "lib/d8.jar"
        if jar.is_file():
            key = tuple(int(p) if p.isdigit() else 0 for p in d.name.split("."))
            candidates.append((key, jar))
    if not candidates:
        fail("build-tools 下没有 d8.jar")
    return max(candidates, key=lambda x: x[0])[1]


def third_party_jars() -> list:
    """在 Gradle 缓存里定位 THIRD_PARTY 列出的 jar。

    只当 build 期的 classpath / `--lib` 用，**绝不被 dex 进产物** ——
    这些类由宿主在运行时提供，打进 jar 反而会让 `instanceof` 失败。
    """
    out = []
    for group, name, ver in THIRD_PARTY:
        base = GRADLE_HOME / "caches/modules-2/files-2.1" / group / name / ver
        hits = sorted(base.glob(f"*/{name}-{ver}.jar"))
        if not hits:
            fail(
                f"Gradle 缓存里找不到 {group}:{name}:{ver}\n"
                f"        找过 {base}\n"
                "        跑一次 build_debug.py 让 Gradle 把依赖下载齐。"
            )
        out.append(hits[0])
    return out


def main() -> None:
    for tool in (JAVA_HOME / "bin/javac.exe", JAVA_HOME / "bin/java.exe"):
        if not tool.is_file():
            fail(f"找不到 {tool}")

    if not HOST_CLASSES.is_dir():
        fail(
            f"找不到宿主编译产物 {HOST_CLASSES}\n"
            "        先跑一次 build_debug.py —— MockSite 要继承的 Spider 类在那里。"
        )

    android_jar = newest_android_jar()
    d8 = d8_jar()
    libs = third_party_jars()
    print(f"[mockjar] android.jar = {android_jar}")
    print(f"[mockjar] d8          = {d8}")
    print(f"[mockjar] 宿主 classes = {HOST_CLASSES}")
    for lib in libs:
        print(f"[mockjar] 第三方 lib  = {lib}")

    if BUILD_DIR.exists():
        shutil.rmtree(BUILD_DIR)
    classes = BUILD_DIR / "classes"
    classes.mkdir(parents=True)

    sources = [str(p) for p in SRC_DIR.rglob("*.java")]
    if not sources:
        fail(f"{SRC_DIR} 下没有 .java")

    # -source/-target 11：d8 对 class file 版本有上限，跟着 app 的 11 走最稳
    cp = os.pathsep.join([str(HOST_CLASSES), str(android_jar)] + [str(p) for p in libs])
    cmd = [
        str(JAVA_HOME / "bin/javac.exe"),
        "-source", "11", "-target", "11",
        "-nowarn",
        "-cp", cp,
        "-d", str(classes),
        *sources,
    ]
    print("[mockjar] javac …")
    run(cmd, "javac")

    class_files = [str(p) for p in classes.rglob("*.class")]
    if not class_files:
        fail("javac 没有产出任何 class")

    if OUT_JAR.exists():
        OUT_JAR.unlink()

    # 宿主 classes 与 android.jar 走 --lib：它们是**库**，不能被 dex 进 jar，
    # 否则 Spider 会在 jar 里再定义一份，`instanceof Spider` 就会因为
    # 两个同名的不同 Class 而失败。
    cmd = [
        str(JAVA_HOME / "bin/java.exe"),
        "-cp", str(d8),
        "com.android.tools.r8.D8",
        "--min-api", "31",
        "--release",
        "--lib", str(android_jar),
        "--lib", str(HOST_CLASSES),
        *[a for p in libs for a in ("--lib", str(p))],
        "--output", str(OUT_JAR),
        *class_files,
    ]
    print("[mockjar] d8 …")
    r = run(cmd, "d8")

    if not OUT_JAR.is_file():
        fail("d8 没有产出 jar")

    size = OUT_JAR.stat().st_size
    print(f"[mockjar] 完成：{OUT_JAR}（{size} 字节）")
    if r.stderr.strip():
        # d8 的 warning 不用当失败，但打出来，免得真有 Missing class 时看不见
        print("[mockjar] d8 警告：\n" + r.stderr.strip())


if __name__ == "__main__":
    main()
