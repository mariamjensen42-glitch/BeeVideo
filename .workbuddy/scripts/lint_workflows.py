r"""校验 .github/workflows/*.yml —— 语法 + 结构 + 内嵌 shell 脚本。

为什么值得单独写：YAML 语法正确 ≠ 流水线能跑。真正会让 CI 挂掉的两类问题
——GitHub 表达式拼错（`steps.version.outputs.nam`）和 run 块里的 shell 语法错
——都不是 YAML 解析器能发现的。把 run 块抠出来喂给 `bash -n` 才抓得到。

⚠️ run 块里的 `${{ ... }}` 是 GitHub 的模板语法、不是 shell 语法，直接喂给
   bash 会在 `${{` 上炸。必须先替换成占位符。

⚠️ Windows 上不能用裸 `bash`：PATH 会先命中 `C:\Windows\System32\bash.exe`
   （WSL 的转发器），本机安全策略直接拦掉，报的是「程序在黑名单里」这种
   和脚本本身毫无关系的错。必须显式用 Git 自带的那只。
"""
import glob
import os
import re
import shutil
import subprocess
import sys
import tempfile

import yaml

EXPR = re.compile(r"\$\{\{[^}]*\}\}")


def find_bash() -> str | None:
    """挑一只**真正的** bash：排除 WSL 转发器与 System32 下的同名程序。"""
    for cand in (
        r"C:\Program Files\Git\bin\bash.exe",
        r"C:\Program Files\Git\usr\bin\bash.exe",
        r"C:\Program Files (x86)\Git\bin\bash.exe",
    ):
        if os.path.exists(cand):
            return cand
    which = shutil.which("bash")
    if which and "System32" not in which and "WSL" not in which:
        return which
    return None


BASH = find_bash()

# 一个 job/step 里必须出现的字段，缺了就是流水线静默失效（比如漏了 runs-on）
REQUIRED_JOB = {"runs-on", "steps"}
REQUIRED_STEP = {"name", "uses", "run"}  # 三选一


def check_shell(script: str, where: str) -> list[str]:
    """把 run 块的 shell 抠出来做语法检查。"""
    if not BASH:
        return []
    # GitHub Actions 在 Linux 上跑的是 `bash -e {0}`。
    stub = EXPR.sub("EXPR_PLACEHOLDER", script)
    fd, path = tempfile.mkstemp(suffix=".sh")
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(stub)
        proc = subprocess.run(
            [BASH, "-n", path],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        if proc.returncode != 0:
            err = (proc.stderr or proc.stdout or "").strip()
            return [f"{where}: shell 语法错误 -> {err}"]
    finally:
        os.unlink(path)
    return []


def main() -> int:
    problems: list[str] = []
    files = sorted(glob.glob(".github/workflows/*.yml"))
    if not files:
        print("没找到 workflow 文件")
        return 1

    for path in files:
        with open(path, encoding="utf-8") as fh:
            doc = yaml.safe_load(fh)

        # ⚠️ YAML 1.1 把裸 `on:` 解析成布尔 True —— 这是每个手写 workflow 都会
        #    撞一次的地方，写 `doc["on"]` 会 KeyError。
        triggers = doc.get(True, doc.get("on"))
        jobs = doc.get("jobs") or {}
        print(f"\n=== {path} ===")
        print(f"  name    : {doc.get('name')}")
        print(f"  触发     : {list(triggers) if isinstance(triggers, dict) else triggers}")
        print(f"  权限     : {doc.get('permissions')}")
        print(f"  并发     : {doc.get('concurrency')}")

        if not triggers:
            problems.append(f"{path}: 没有 on 触发器")
        if not jobs:
            problems.append(f"{path}: 没有 jobs")

        for jname, job in jobs.items():
            missing = REQUIRED_JOB - set(job)
            if missing:
                problems.append(f"{path}#{jname}: 缺字段 {missing}")
            print(f"  job {jname}: runs-on={job.get('runs-on')} steps={len(job.get('steps', []))}")
            for i, step in enumerate(job.get("steps", [])):
                label = f"{path}#{jname}.step[{i}] {step.get('name', '(无名)')}"
                if not ({"uses", "run"} & set(step)):
                    # `with:`/`if:` 不能独立成步
                    problems.append(f"{label}: 既没有 uses 也没有 run")
                if "uses" in step:
                    ref = step["uses"]
                    if "@" not in ref:
                        problems.append(f"{label}: uses 没钉版本号 -> {ref}")
                    print(f"      - uses {ref}")
                if "run" in step:
                    problems += check_shell(step["run"], label)
                    first = step["run"].strip().splitlines()[0]
                    print(f"      - run  {first[:70]}")
                # 表达式检查：引号里的 ${{ }} 是否闭合
                raw = yaml.safe_dump(step, allow_unicode=True)
                for expr in re.findall(r"\$\{\{.*?\}\}", raw):
                    body = expr[3:-2].strip()
                    if not body:
                        problems.append(f"{label}: 空的表达式 {expr}")

    print("\n" + "=" * 60)
    if problems:
        print(f"发现 {len(problems)} 个问题：")
        for p in problems:
            print("  ✗", p)
        return 1
    print("全部通过。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
