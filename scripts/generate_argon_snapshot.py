import os
import subprocess
from datetime import datetime

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCE_DIR = os.path.join(REPO_ROOT, "app", "src", "main", "java")
OUTPUT_FILE = os.path.join(REPO_ROOT, "argon_snapshot.md")

def generate_snapshot():
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    
    # 1. Get git status
    try:
        git_status = subprocess.check_output(
            ["git", "status"],
            cwd=REPO_ROOT,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace"
        )
    except Exception as e:
        git_status = f"Error retrieving git status: {e}"

    lines = [
        "# Argon Codebase Snapshot (مصحف القيام — Path B: Zipformer)",
        f"- **Timestamp:** `{now}`",
        f"- **Working Directory:** `{REPO_ROOT}`",
        "",
        "## Git Status",
        "```text",
        git_status.strip(),
        "```",
        "",
        "---",
        ""
    ]

    # 2. Collect all .kt files
    kt_files = []
    for root, _, files in os.walk(SOURCE_DIR):
        for f in files:
            if f.endswith(".kt"):
                kt_files.append(os.path.join(root, f))
    
    kt_files.sort()

    for file_path in kt_files:
        rel_path = os.path.relpath(file_path, REPO_ROOT).replace("\\", "/")
        lines.append(f"## File: `{rel_path}`")
        lines.append("```kotlin")
        try:
            with open(file_path, "r", encoding="utf-8", errors="replace") as kf:
                content = kf.read()
            lines.append(content)
        except Exception as e:
            lines.append(f"// Error reading file: {e}")
        lines.append("```")
        lines.append("")

    with open(OUTPUT_FILE, "w", encoding="utf-8") as out:
        out.write("\n".join(lines))

    file_size_kb = os.path.getsize(OUTPUT_FILE) / 1024
    print(f"Generated {OUTPUT_FILE} ({len(kt_files)} files, {file_size_kb:.1f} KB)")

if __name__ == "__main__":
    generate_snapshot()
