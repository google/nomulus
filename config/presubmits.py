# Copyright 2019 The Nomulus Authors. All Rights Reserved.
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
"""Google-style presubmits for the Nomulus project.

These aren't built in to the static code analysis tools we use (e.g. Checkstyle,
Error Prone) so we must write them manually.
"""

import json
import os
from typing import List, Tuple
import sys
import textwrap
import re

# We should never analyze any generated files
UNIVERSALLY_SKIPPED_PATTERNS = {"/build/", "cloudbuild-caches", "/out/", ".git/", ".gradle/"}
# We can't rely on CI to have the Enum package installed so we do this instead.
FORBIDDEN = 1
REQUIRED = 2

# The list of expected json packages and their licenses.
# These should be one of the allowed licenses in:
# config/dependency-license/allowed_licenses.json
EXPECTED_JS_PACKAGES = [
    'google-closure-library',   # Owned by Google, Apache 2.0
]


class PresubmitCheck:

  def __init__(self,
               regex,
               included_extensions,
               skipped_patterns,
               regex_type=FORBIDDEN):
    """Define a presubmit check for a particular set of files,

        The provided prefix should always or never be included in the files.

        Args:
            regex: the regular expression to forbid or require
            included_extensions: a tuple of extensions that define which files
              we run over
            skipped_patterns: a set of patterns that will cause any files that
              include them to be skipped
            regex_type: either FORBIDDEN or REQUIRED--whether or not the regex
              must be present or cannot be present.
    """
    self.regex = regex
    self.included_extensions = included_extensions
    self.skipped_patterns = UNIVERSALLY_SKIPPED_PATTERNS.union(skipped_patterns)
    self.regex_type = regex_type

  def fails(self, file):
    """ Determine whether or not this file fails the regex check.

    Args:
        file: the full path of the file to check
    """
    if not file.endswith(self.included_extensions):
      return False
    for pattern in self.skipped_patterns:
      if pattern in file:
        return False
    with open(file, "r", encoding='utf8') as f:
      file_content = f.read()
      matches = re.match(self.regex, file_content, re.DOTALL)
      if self.regex_type == FORBIDDEN:
        return matches
      return not matches


PRESUBMITS = {
    # License check
    PresubmitCheck(
        r".*Copyright 20\d{2} The Nomulus Authors\. All Rights Reserved\.",
        ("java", "js", "soy", "sql", "py", "sh", "gradle"), {
            ".git", "/build/", "/generated/", "/generated_tests/",
            "node_modules/", "LocalStorageHelper.java", "FakeStorageRpc.java",
            "registrar_bin.", "registrar_dbg.", "google-java-format-diff.py",
            "nomulus.golden.sql", "soyutils_usegoog.js", "javascript/checks.js"
        }, REQUIRED):
        "File did not include the license header.",

    # Files must end in a newline
    PresubmitCheck(r".*\n$", ("java", "js", "soy", "sql", "py", "sh", "gradle"),
                   {"node_modules/"}, REQUIRED):
        "Source files must end in a newline.",

    # System.(out|err).println should only appear in tools/
    PresubmitCheck(
        r".*\bSystem\.(out|err)\.print", "java", {
            "StackdriverDashboardBuilder.java", "/tools/", "/example/",
            "RegistryTestServerMain.java", "TestServerExtension.java",
            "FlowDocumentationTool.java"
        }):
        "System.(out|err).println is only allowed in tools/ packages. Please "
        "use a logger instead.",

    # ObjectifyService.register is restricted to main/ or AppEngineExtension.
    PresubmitCheck(
        r".*\bObjectifyService\.register", "java", {
            "/build/", "/generated/", "node_modules/", "src/main/",
            "AppEngineExtension.java"
        }):
      "ObjectifyService.register(...) is not allowed in tests. Please use "
      "AppEngineExtension.register(...) instead.",

    # PostgreSQLContainer instantiation must specify docker tag
    PresubmitCheck(
        r"[\s\S]*new\s+PostgreSQLContainer(<[\s\S]*>)?\(\s*\)[\s\S]*",
        "java", {}):
      "PostgreSQLContainer instantiation must specify docker tag.",

    # Various Soy linting checks
    PresubmitCheck(
        r".* (/\*)?\* {?@param ",
        "soy",
        {},
    ):
        "In SOY please use the ({@param name: string} /** User name. */) style"
        " parameter passing instead of the ( * @param name User name.) style "
        "parameter pasing.",
    PresubmitCheck(
        r'.*\{[^}]+\w+:\s+"',
        "soy",
        {},
    ):
        "Please don't use double-quoted string literals in Soy parameters",
    PresubmitCheck(
        r'.*autoescape\s*=\s*"[^s]',
        "soy",
        {},
    ):
        "All soy templates must use strict autoescaping",
    PresubmitCheck(
        r".*noAutoescape",
        "soy",
        {},
    ):
        "All soy templates must use strict autoescaping",

    # various JS linting checks
    PresubmitCheck(
        r".*goog\.base\(",
        "js",
        {"/node_modules/"},
    ):
        "Use of goog.base is not allowed.",
    PresubmitCheck(
        r".*goog\.dom\.classes",
        "js",
        {"/node_modules/"},
    ):
        "Instead of goog.dom.classes, use goog.dom.classlist which is smaller "
        "and faster.",
    PresubmitCheck(
        r".*goog\.getMsg",
        "js",
        {"/node_modules/"},
    ):
        "Put messages in Soy, instead of using goog.getMsg().",
    PresubmitCheck(
        r".*(innerHTML|outerHTML)\s*(=|[+]=)([^=]|$)",
        "js",
        {"/node_modules/", "registrar_bin."},
    ):
        "Do not assign directly to the dom. Use goog.dom.setTextContent to set"
        " to plain text, goog.dom.removeChildren to clear, or "
        "soy.renderElement to render anything else",
    PresubmitCheck(
        r".*console\.(log|info|warn|error)",
        "js",
        {"/node_modules/", "google/registry/ui/js/util.js", "registrar_bin."},
    ):
        "JavaScript files should not include console logging.",
    # SQL injection protection rule for java source file:
    # The sql template passed to createQuery/createNativeQuery methods must be
    # a variable name in UPPER_CASE_UNDERSCORE format, i.e., a static final
    # String variable. This forces the use of parameter-binding on all queries
    # that take parameters.
    # The rule would forbid invocation of createQuery(Criteria). However, this
    # can be handled by adding a helper method in an exempted class to make
    # the calls.
    # TODO(b/179158393): enable the 'ConstantName' Java style check to ensure
    # that non-final variables do not use the UPPER_CASE_UNDERSCORE format.
    PresubmitCheck(
        # Line 1: the method names we check and the opening parenthesis, which
        #    marks the beginning of the first parameter
        # Line 2: The first parameter is a match if is NOT any of the following:
        #    - final variable name: \s*([A-Z_]+
        #    - string literal: "([^"]|\\")*"
        #    - concatenation of literals: (\s*\+\s*"([^"]|\\")*")*
        # Line 3: , or the closing parenthesis, marking the end of the first
        #    parameter
        r'.*\.(query|createQuery|createNativeQuery)\('
        r'(?!(\s*([A-Z_]+|"([^"]|\\")*"(\s*\+\s*"([^"]|\\")*")*)'
        r'(,|\s*\))))',
        "java",
        # ActivityReportingQueryBuilder deals with Dremel queries
        {"src/test", "ActivityReportingQueryBuilder.java",
         # This class contains helper method to make queries in Beam.
         "RegistryJpaIO.java",
         # TODO(b/179158393): Remove everything below, which should be done
         # using Criteria
         "JpaTransactionManager.java",
         "JpaTransactionManagerImpl.java",
         # CriteriaQueryBuilder is a false positive
         "CriteriaQueryBuilder.java",
         "RdapDomainSearchAction.java",
         "RdapNameserverSearchAction.java",
         "ReadOnlyCheckingEntityManager.java",
         "RegistryQuery",
         },
    ):
        "The first String parameter to EntityManager.create(Native)Query "
        "methods must be one of the following:\n"
        "  - A String literal\n"
        "  - Concatenation of String literals only\n"
        "  - The name of a static final String variable"
}

# Note that this regex only works for one kind of Flyway file.  If we want to
# start using "R" and "U" files we'll need to update this script.
FLYWAY_FILE_RX = re.compile(r'V(\d+)__.*')


def get_seqnum(filename: str, location: str) -> int:
  """Extracts the sequence number from a filename."""
  m = FLYWAY_FILE_RX.match(filename)
  if m is None:
    raise ValueError('Illegal Flyway filename: %s in %s' % (filename, location))
  return int(m.group(1))


def files_by_seqnum(files: List[str], location: str) -> List[Tuple[int, str]]:
  """Returns the list of seqnum, filename sorted by sequence number."""
  return [(get_seqnum(filename, location), filename) for filename in files]


def has_valid_order(indexed_files: List[Tuple[int, str]], location: str) -> bool:
  """Verify that sequence numbers are in order without gaps or duplicates.

  Args:
    files: List of seqnum, filename for a list of Flyway files.
    location: Where the list of files came from (for error reporting).

  Returns:
    True if the file list is valid.
  """
  last_index = 0
  valid = True
  for seqnum, filename in indexed_files:
    if seqnum == last_index:
      print('duplicate Flyway file sequence number found in %s: %s' %
            (location, filename))
      valid = False
    elif seqnum < last_index:
      print('File %s in %s is out of order.' % (filename, location))
      valid = False
    elif seqnum != last_index + 1:
      print('Missing Flyway sequence number %d in %s.  Next file is %s' %
            (last_index + 1, location, filename))
      valid = False
    last_index = seqnum
  return valid


def verify_flyway_index():
  """Verifies that the Flyway index file is in sync with the directory."""
  success = True

  # Sort the files in the Flyway directory by their sequence number.
  files = sorted(
      files_by_seqnum(os.listdir('db/src/main/resources/sql/flyway'),
                      'Flyway directory'))

  # Make sure that there are no gaps and no duplicate sequence numbers in the
  # files themselves.
  if not has_valid_order(files, 'Flyway directory'):
    success = False

  # Remove the sequence numbers and compare against the index file contents.
  files = [filename[1] for filename in sorted(files)]
  with open('db/src/main/resources/sql/flyway.txt', encoding='utf8') as index:
    indexed_files = index.read().splitlines()
  if files != indexed_files:
    unindexed = set(files) - set(indexed_files)
    if unindexed:
      print('The following Flyway files are not in flyway.txt: %s' % unindexed)

    nonexistent = set(indexed_files) - set(files)
    if nonexistent:
      print('The following files are in flyway.txt but not in the Flyway '
            'directory: %s' % nonexistent)

    # Do an ordering check on the index file (ignore the result, we're failing
    # anyway).
    has_valid_order(files_by_seqnum(indexed_files, 'flyway.txt'), 'flyway.txt')
    success = False

  if not success:
    print('Please fix any conflicts and run "./nom_build :db:generateFlywayIndex"')

  return not success


def verify_javascript_deps():
  """Verifies that we haven't introduced any new javascript dependencies."""
  with open('package.json') as f:
    package = json.load(f)

  deps = list(package['dependencies'].keys())
  if deps != EXPECTED_JS_PACKAGES:
    print('Unexpected javascript dependencies.  Was expecting '
          '%s, got %s.' % (EXPECTED_JS_PACKAGES, deps))
    print(textwrap.dedent("""
        * If the new dependencies are intentional, please verify that the
        * license is one of the allowed licenses (see
        * config/dependency-license/allowed_licenses.json) and add an entry
        * for the package (with the license in a comment) to the
        * EXPECTED_JS_PACKAGES variable in config/presubmits.py.
        """))
    return True
  return False


def get_files():
  for root, dirnames, filenames in os.walk("."):
    for filename in filenames:
      yield os.path.join(root, filename)


if __name__ == "__main__":
  failed = False
  for file in get_files():
    error_messages = []
    for presubmit, error_message in PRESUBMITS.items():
      if presubmit.fails(file):
        error_messages.append(error_message)

    if error_messages:
      failed = True
      print("%s had errors: \n  %s" % (file, "\n  ".join(error_messages)))

  # And now for something completely different: check to see if the Flyway
  # index is up-to-date.  It's quicker to do it here than in the unit tests:
  # when we put it here it fails fast before all of the tests are run.
  failed |= verify_flyway_index()

  # Make sure we haven't introduced any javascript dependencies.
  failed |= verify_javascript_deps()

  if failed:
    sys.exit(1)
