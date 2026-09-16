import os
import subprocess
import sys

PROJECT = r"D:\Programming\Kotlin\BeeVideo"
JAVA_HOME = r"C:\Program Files\Java\jdk-21"
WRAPPER = os.path.join(PROJECT, "gradle", "wrapper", "gradle-wrapper.jar")

# 任务列表可覆盖：`python build_debug.py :app:clean :app:assembleDebug`
# 需要排除"增量编译缓存给出了过期产物"这类怀疑时用得上（本项目踩过一次：
# 源码改了、APK 时间戳也是新的，但装到机器上跑的还是旧逻辑）。
tasks = sys.argv[1:] or [":app:assembleDebug"]

env = dict(os.environ)
env["JAVA_HOME"] = JAVA_HOME
env["GRADLE_USER_HOME"] = r"E:\AndroidDev\Gradle"
env["PATH"] = os.path.join(JAVA_HOME, "bin") + os.pathsep + env.get("PATH", "")

cmd = [
    os.path.join(JAVA_HOME, "bin", "java.exe"),
    "-cp",
    WRAPPER,
    "org.gradle.wrapper.GradleWrapperMain",
    *tasks,
    "--console=plain",
    "--no-daemon",
    "--max-workers=1",
]

proc = subprocess.run(
    cmd,
    cwd=PROJECT,
    env=env,
    capture_output=True,
    text=True,
    encoding="utf-8",
    errors="replace",
)
print("EXIT", proc.returncode)
out = proc.stdout or ""
print(out[-25000:])
err = proc.stderr or ""
if err.strip():
    print("=== STDERR ===")
    print(err[-8000:])
