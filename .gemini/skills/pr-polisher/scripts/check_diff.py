#!/usr/bin/env python3
# Copyright 2026 The Nomulus Authors. All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

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

def check_single_commit():
    print("--- Checking Commit Count ---")
    try:
        count = int(run_cmd("git rev-list --count HEAD ^master"))
        if count > 1:
            log_error(f"Branch contains {count} commits ahead of master. All changes for a single PR must be squashed into a single commit.")
        else:
            log_success("Branch contains a single commit.")
    except Exception:
        # Ignore errors if the git command fails (e.g. not a git repo or no master branch)
        pass

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

        has_body = False
        for line in lines[1:]:
            if line.strip() != "":
                has_body = True
                break
        if not has_body:
            log_error("Commit message must contain a comprehensive body detailing the 'what' and 'why'.")

        if errors_found == 0:
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

    java_header = f"// Copyright {current_year} The Nomulus Authors. All Rights Reserved."
    script_header = f"# Copyright {current_year} The Nomulus Authors. All Rights Reserved."
    sql_header = f"-- Copyright {current_year} The Nomulus Authors. All Rights Reserved."
    ftl_header = f"<#-- Copyright {current_year} The Nomulus Authors. All Rights Reserved."

    files_checked = 0

    for f_line in added_files:
        if not f_line:
            continue
        f = f_line.split('\t')[-1]

        expected_header = None
        if f.endswith('.java') or f.endswith('.js') or f.endswith('.gradle') or f.endswith('.ts'):
            expected_header = java_header
        elif f.endswith('.py') or f.endswith('.sh'):
            expected_header = script_header
        elif f.endswith('.sql'):
            expected_header = sql_header
        elif f.endswith('.ftl'):
            expected_header = ftl_header

        if expected_header:
            files_checked += 1
            try:
                with open(f, 'r') as file:
                    content = file.read()
                    if expected_header not in content:
                        log_error(f"Missing or incorrect copyright year in {f}. Expected: {expected_header}")
            except FileNotFoundError:
                # File might have been deleted or renamed; ignore missing files.
                pass

    if files_checked == 0:
         log_success("No new files requiring license headers added.")

def check_formatting():
    print("\n--- Checking Project Formatting ---")
    try:
        run_cmd("./gradlew spotlessCheck javaIncrementalFormatCheck")
        log_success("All formatting checks (spotless and javaIncrementalFormat) passed.")
    except Exception as e:
        log_error("Formatting checks failed. Run './gradlew spotlessApply javaIncrementalFormatApply' to fix.")

def check_diff_anti_patterns():
    print("\n--- Checking Code Anti-Patterns in Diff ---")
    diff = run_cmd("git diff HEAD^ -U0")
    current_file = ""

    # Regex Patterns
    fqn_pattern = re.compile(r'(?<!import\s)(java|google\.registry|com\.google|org)\.[a-z0-9.]+\.[A-Z][a-zA-Z0-9]+')
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
    trailing_space_pattern = re.compile(r'[ \t]+$')
    debug_print_pattern = re.compile(r'(System\.(out|err)\.print|\.printStackTrace\(\))')
    todo_pattern = re.compile(r'\b(TODO|FIXME)\b')
    unnecessary_cast_pattern = re.compile(r'\(\s*(?:Instant|ImmutableSet|ImmutableList|ImmutableMap)(?:<[^>]+>)?\s*\)')
    instant_tostring_pattern = re.compile(r'(?i)(?:instant|time|now|clock\.now\(\))\.toString\(\)')
    dao_transact_pattern = re.compile(r'tm\(\)\.transact\(')
    retransact_txtime_pattern = re.compile(r'tm\(\)\.reTransact\(\s*(?:\(\)\s*->\s*)?tm\(\)(?:\.|::)get(?:Transaction|Tx)Time\(\)?\s*\)')
    inject_command_pattern = re.compile(r'inject\(Command\s+[a-zA-Z0-9_]+\)')
    clock_now_pattern = re.compile(r'clock\.now\(\)')

    suppress_count = 0

    for line in diff.split('\n'):
        if line.startswith('+++ b/'):
            current_file = line[6:]
            suppress_count = 0
            continue

        if line.startswith('+') and not line.startswith('+++'):
            code_line = line[1:]

            # Trailing whitespace
            if trailing_space_pattern.search(code_line):
                log_error(f"[{current_file}] Found trailing whitespace.")

            # Skip regex definitions in this script from triggering false positives
            if 're.compile' not in code_line:
                # Debug prints
                if debug_print_pattern.search(code_line):
                    log_error(f"[{current_file}] Found leftover debug print or stack trace (System. out. println / printStackTrace).")

                # TODOs / FIXMEs
                if todo_pattern.search(code_line):
                    log_warning(f"[{current_file}] Found new T" "ODO or F" "IXME. Ensure this is intentional before completing the PR.")

            if not current_file.endswith('.java'):
                continue

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

            # Unnecessary casts
            if unnecessary_cast_pattern.search(code_line):
                log_warning(f"[{current_file}] Potential unnecessary cast to Instant or Guava Immutable type. Remove if it compiles without it.")

            # Instant toString
            if instant_tostring_pattern.search(code_line):
                log_error(f"[{current_file}] Found potential Instant.toString(). Use DateTimeUtils.formatInstant(...) to preserve .000Z precision.")

            # DAO transactions
            if current_file.lower().endswith('dao.java') and dao_transact_pattern.search(code_line):
                log_error(f"[{current_file}] Found tm().transact(...) inside a DAO. Use tm().assertInTransaction() instead.")

            # reTransact around getTxTime in production code
            if 'src/main/' in current_file and retransact_txtime_pattern.search(code_line):
                log_error(f"[{current_file}] Unnecessary reTransact() around getTxTime() in production code. Wrap the caller in a transaction instead.")

            # inject(Command)
            if inject_command_pattern.search(code_line):
                log_error(f"[{current_file}] Generic inject(Command) methods do not work with Dagger. Use explicit concrete types.")

            # clock.now() in tests
            if 'src/test/' in current_file and clock_now_pattern.search(code_line):
                log_warning(f"[{current_file}] Prefer using a fixed, static constant Instant over capturing clock.now() in tests to prevent flakiness.")

def main():
    print("========================================")
    print("     NOMULUS PR POLISHER CHECKLIST      ")
    print("========================================\n")

    check_single_commit()
    check_commit_message()
    check_workspace_clean()
    check_package_lock()
    check_license_headers()
    check_formatting()
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
