#!/usr/bin/env python3
import subprocess
import re
import sys
import datetime

# Color codes
RED = "\03.3[91m"
YELLOW = "\03.3[93m"
GREEN = "\03.3[92m"
RESET = "\03.3[0m"

errors_found = 0
warnings_found = 0

def log_error(msg):
    global errors_found
    errors_found += 1
    print(f"{RED}[ERROR]{RESET} {msg}")

def log_warning(msg):
    global warnings_found
    warnings_found += 1
    print(f"{YELLOW}[WARNING]{RESET} {msg}")

def log_success(msg):
    print(f"{GREEN}[OK]{RESET} {msg}")

def run_cmd(cmd):
    return subprocess.check_output(cmd, shell=True, text=True).strip()

def check_commit_message():
    print("--- Checking Commit Message ---")
    try:
        msg = run_cmd("git log -1 --pretty=format:%B")
        lines = msg.split('\n')
        subject = lines[0]
        if len(subject) > 50:
            log_error(f"Commit subject exceeds 50 characters ({len(subject)} chars): '{subject}'")
        if not subject[0].isupper():
            log_error(f"Commit subject must be capitalized: '{subject}'")
        if subject[-1] in ['.', '!', '?']:
            log_error(f"Commit subject must not end with punctuation: '{subject}'")
        else:
            log_success("Commit message format looks good.")
    except Exception as e:
        log_error(f"Failed to check commit message: {e}")

def check_workspace_clean():
    print("\n--- Checking Workspace State ---")
    status = run_cmd("git status --porcelain")
    if status:
        log_error("Workspace is not clean. Uncommitted changes found:\n" + status)
    else:
        log_success("Working directory is clean.")

def check_package_lock():
    print("\n--- Checking package-lock.json ---")
    diff_files = run_cmd("git diff HEAD^ --name-only").split('\n')
    if "console-webapp/package-lock.json" in diff_files:
        log_error("console-webapp/package-lock.json is modified in the diff. Unless NPM dependencies were explicitly changed, revert this file using: git checkout console-webapp/package-lock.json")
    else:
        log_success("console-webapp/package-lock.json is untouched.")

def check_license_headers():
    print("\n--- Checking License Headers on New Files ---")
    current_year = str(datetime.datetime.now().year)
    added_files = run_cmd("git diff HEAD^ --name-status --diff-filter=A").split('\n')
    added_java_files = [f.split('\t')[-1] for f in added_files if f.endswith('.java')]
    
    expected_header = f"// Copyright {current_year} The Nomulus Authors. All Rights Reserved."
    for f in added_java_files:
        try:
            with open(f, 'r') as file:
                content = file.read()
                if expected_header not in content:
                    log_error(f"Missing or incorrect copyright year in {f}. Expected: {expected_header}")
        except FileNotFoundError:
            pass
    if not added_java_files:
         log_success("No new Java files added.")

def check_diff_anti_patterns():
    print("\n--- Checking Code Anti-Patterns in Diff ---")
    diff = run_cmd("git diff HEAD^ -U0")
    current_file = ""
    
    # Regex Patterns
    fqn_pattern = re.compile(r'(?<!import\s)(java\.[a-z0-9.]+\.[A-Z][a-zA-Z0-9]+|google\.registry\.[a-z0-9.]+\.[A-Z][a-zA-Z0-9]+)')
    visibility_pattern = re.compile(r'/\*\s*package\s*\*/')
    utc_pattern = re.compile(r'ZoneId\.of\("UTC"\)')
    now_pattern = re.compile(r'(Instant\.now\(\)|OffsetDateTime\.now\(\)|System\.currentTimeMillis\(\))')
    catch_generic_pattern = re.compile(r'catch\s*\(\s*(Exception|Throwable)\s+[a-zA-Z0-9_]+\s*\)')
    is_equal_optional_pattern = re.compile(r'\.isEqualTo\(Optional\.of\(')
    sleep_pattern = re.compile(r'Thread\.sleep\(')
    suppress_pattern = re.compile(r'@SuppressWarnings\(')
    wrong_nullable_pattern = re.compile(r'import\s+(?!javax\.annotation\.Nullable;)[a-zA-Z0-9_.]+\.Nullable;')
    utility_class_pattern = re.compile(r'\b(DateTimeUtils|CacheUtils)\.[a-z]')
    redundant_tx_pattern = re.compile(r'tm\(\)\.transact\(\s*\(\)\s*->\s*tm\(\)\.reTransact')
    mutable_collection_pattern = re.compile(r'new\s+(ArrayList|HashMap|HashSet)\s*[<()]')

    suppress_count = 0

    for line in diff.split('\n'):
        if line.startswith('+++ b/'):
            current_file = line[6:]
            suppress_count = 0
            continue
        
        if line.startswith('+') and not line.startswith('+++') and current_file.endswith('.java'):
            code_line = line[1:]
            
            # FQN Check
            fqn_matches = fqn_pattern.findall(code_line)
            if fqn_matches:
                 # Skip if the match is exactly part of an import or package declaration
                 if not code_line.strip().startswith('import') and not code_line.strip().startswith('package'):
                     log_warning(f"[{current_file}] Potential Fully-Qualified Name found: {fqn_matches}. Use imports instead.")

            # Package visibility
            if visibility_pattern.search(code_line):
                log_error(f"[{current_file}] Found '/* package */' modifier. Leave modifier blank instead.")

            # Time zones
            if utc_pattern.search(code_line):
                log_error(f"[{current_file}] Found ZoneId.of(\"UTC\"). Use statically imported ZoneOffset.UTC instead.")

            # System clocks
            if now_pattern.search(code_line):
                log_error(f"[{current_file}] Found un-injected clock (Instant.now / System.currentTimeMillis). Inject Clock instead.")

            # Catch generic
            if catch_generic_pattern.search(code_line):
                log_warning(f"[{current_file}] Catching generic Exception/Throwable. Use specific exceptions.")

            # Truth Optionals
            if is_equal_optional_pattern.search(code_line):
                log_warning(f"[{current_file}] Found .isEqualTo(Optional.of(...)). Use Truth's .hasValue(...) instead.")

            # Thread.sleep
            if sleep_pattern.search(code_line):
                log_warning(f"[{current_file}] Found Thread.sleep(). Use Sleeper instead in tests.")

            # SuppressWarnings
            if suppress_pattern.search(code_line):
                suppress_count += 1
                if suppress_count > 1:
                    log_error(f"[{current_file}] Multiple @SuppressWarnings detected. They must be merged (e.g. {{\"unchecked\", \"foo\"}}).")
            else:
                suppress_count = 0

            # Wrong Nullable
            if wrong_nullable_pattern.search(code_line):
                log_error(f"[{current_file}] Found incorrect Nullable import. Always use javax.annotation.Nullable.")

            # Missing static imports for utilities
            if utility_class_pattern.search(code_line):
                if not code_line.strip().startswith('import'):
                    log_warning(f"[{current_file}] Found un-statically imported method from DateTimeUtils/CacheUtils. Use static imports.")

            # Redundant transaction wrapping
            if redundant_tx_pattern.search(code_line):
                log_error(f"[{current_file}] Found redundant transaction wrapping (tm().transact(() -> tm().reTransact(...))).")

            # Mutable collection instantiation
            if mutable_collection_pattern.search(code_line):
                log_warning(f"[{current_file}] Found mutable collection instantiation (ArrayList/HashMap/HashSet). Prefer Guava Immutable collections.")

def main():
    print("========================================")
    print("     NOMULUS PR POLISHER CHECKLIST      ")
    print("========================================\n")
    
    check_commit_message()
    check_workspace_clean()
    check_package_lock()
    check_license_headers()
    check_diff_anti_patterns()
    
    print("\n========================================")
    if errors_found == 0 and warnings_found == 0:
        print(f"{GREEN}SUCCESS: All checks passed. PR is polished!{RESET}")
    else:
        print(f"RESULTS: {RED}{errors_found} ERRORS{RESET}, {YELLOW}{warnings_found} WARNINGS{RESET}")
        print("Please address the above issues before declaring the PR complete.")
        sys.exit(1 if errors_found > 0 else 0)

if __name__ == "__main__":
    main()