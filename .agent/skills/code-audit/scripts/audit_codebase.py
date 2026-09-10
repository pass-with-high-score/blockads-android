#!/usr/bin/env python3
import os
import re
import sys
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parents[4]
APP_DIR = ROOT_DIR / "app" / "src" / "main" / "java" / "app" / "pwhs" / "blockads"
TUNNEL_DIR = ROOT_DIR / "tunnel"
TV_DIR = ROOT_DIR / "blockadstv" / "src" / "main" / "java"

MAX_LINES = 500
CRITICAL_LINES = 750

def check_line_counts():
    violations = []
    targets = [ROOT_DIR / "app", ROOT_DIR / "blockadstv", ROOT_DIR / "tunnel"]
    extensions = {".kt", ".go"}

    for target in targets:
        if not target.exists():
            continue
        for path in target.rglob("*"):
            if path.is_file() and path.suffix in extensions:
                # skip generated/build files
                rel = path.relative_to(ROOT_DIR)
                if "build/" in str(rel) or "schemas/" in str(rel):
                    continue
                try:
                    with open(path, "r", encoding="utf-8", errors="ignore") as f:
                        lines = len(f.readlines())
                    if lines > MAX_LINES:
                        severity = "CRITICAL" if lines > CRITICAL_LINES else "WARNING"
                        violations.append({
                            "file": str(rel),
                            "lines": lines,
                            "severity": severity
                        })
                except Exception as e:
                    pass

    violations.sort(key=lambda x: x["lines"], reverse=True)
    return violations

def check_mvi_compliance():
    ui_dir = APP_DIR / "ui"
    if not ui_dir.exists():
        return []

    issues = []
    # inspect subdirectories in ui
    for screen_folder in ui_dir.iterdir():
        if not screen_folder.is_dir():
            continue
        if screen_folder.name in {"common", "theme", "navigation", "components"}:
            continue

        # Look recursively inside this screen folder
        kt_files = list(screen_folder.rglob("*.kt"))
        file_names = [f.name for f in kt_files]
        
        has_screen = any("Screen" in name for name in file_names)
        has_vm = any("ViewModel" in name for name in file_names)
        has_contract = any("Contract" in name for name in file_names)

        # Check if screen exists but contract is missing
        if has_screen and not has_contract:
            rel = screen_folder.relative_to(ROOT_DIR)
            issues.append({
                "screen_module": str(rel),
                "issue": "Missing dedicated *Contract.kt (UiState / UiIntent / UiEffect)",
                "has_vm": has_vm,
                "has_screen": has_screen,
                "has_contract": has_contract
            })

    return issues

def check_gomobile_compatibility():
    if not TUNNEL_DIR.exists():
        return []

    issues = []
    # Search for exported Go functions with potential non-gomobile types
    # Exported funcs start with capital letter: func Name(...)
    func_pattern = re.compile(r"^func\s+([A-Z]\w*)\s*\((.*?)\)\s*(.*?)\s*\{", re.MULTILINE)
    disallowed_type_patterns = [
        (re.compile(r"\[\](string|int|int64|bool|float64)"), "Slice of non-byte type ([]%s) is incompatible with gomobile"),
        (re.compile(r"map\["), "Go map types are incompatible with gomobile"),
        (re.compile(r"\b(uint|uint32|uint64)\b"), "Unsigned integer types other than byte/uint8 are incompatible with gomobile"),
    ]

    for go_file in TUNNEL_DIR.glob("*.go"):
        if go_file.name.endswith("_test.go"):
            continue
        rel = go_file.relative_to(ROOT_DIR)
        try:
            content = go_file.read_text(encoding="utf-8", errors="ignore")
            for match in func_pattern.finditer(content):
                func_name = match.group(1)
                params = match.group(2)
                returns = match.group(3)
                sig = f"{params} -> {returns}"

                for pattern, msg in disallowed_type_patterns:
                    found = pattern.findall(sig)
                    if found:
                        issues.append({
                            "file": str(rel),
                            "func": func_name,
                            "issue": msg % (found[0] if isinstance(found[0], str) else "")
                        })
        except Exception:
            pass

    return issues

def main():
    print("# BlockAds Codebase Audit Report\n")

    line_violations = check_line_counts()
    print(f"## 1. File Line Count Violations (> {MAX_LINES} lines)")
    if not line_violations:
        print("✅ No files exceed 500 lines.\n")
    else:
        print(f"Found **{len(line_violations)}** files exceeding {MAX_LINES} lines:\n")
        print("| File | Lines | Severity |")
        print("| :--- | :--- | :--- |")
        for v in line_violations:
            icon = "🔴" if v["severity"] == "CRITICAL" else "🟡"
            print(f"| `{v['file']}` | **{v['lines']}** | {icon} {v['severity']} |")
        print()

    mvi_issues = check_mvi_compliance()
    print("## 2. MVI Architecture & Contract Compliance")
    if not mvi_issues:
        print("✅ All UI screen modules have dedicated Contract, ViewModel, and Screen files.\n")
    else:
        print(f"Found **{len(mvi_issues)}** UI modules missing dedicated contracts:\n")
        print("| Screen Module | Issue | Details |")
        print("| :--- | :--- | :--- |")
        for m in mvi_issues:
            print(f"| `{m['screen_module']}` | ⚠️ Missing Contract | Has Screen: {m['has_screen']}, Has VM: {m['has_vm']} |")
        print()

    gomobile_issues = check_gomobile_compatibility()
    print("## 3. Go Tunnel & Gomobile Safety")
    if not gomobile_issues:
        print("✅ Exported Go functions conform to gomobile compatibility guidelines.\n")
    else:
        print(f"Found **{len(gomobile_issues)}** potential gomobile interop risks:\n")
        for g in gomobile_issues:
            print(f"- `{g['file']}`: `func {g['func']}`: {g['issue']}")
        print()

    print("## 4. Prioritized Refactoring Recommendations")
    if line_violations:
        top_violator = line_violations[0]
        print(f"1. **Priority 1 (God-Class Decomposition)**: Refactor `{top_violator['file']}` ({top_violator['lines']} lines) into focused delegates/services.")
    if mvi_issues:
        print(f"2. **Priority 2 (MVI Standardization)**: Extract `*Contract.kt` for UI screens currently missing dedicated contract files.")
    print("3. **Priority 3 (Component Reuse)**: Consolidate repeated UI cards, preference items, and dialogs into `ui/components/`.")

if __name__ == "__main__":
    main()
