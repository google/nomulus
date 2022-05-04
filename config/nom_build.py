# Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

"""Script to generate dr-build and the properties file.
"""

import argparse
import dataclasses
import io
import os
import shutil
import subprocess
import sys
from typing import List, Union


@dataclasses.dataclass
class Property:
    name : str = ''
    desc : str = ''
    default : str = ''
    constraints : type = str

    def validate(self, value: str):
        """Verify that "value" is appropriate for the property."""
        if type is bool:
            if value not in ('true', 'false'):
                raise ValidationError('value of {self.name} must be "true" or '
                                      '"false".')

@dataclasses.dataclass
class GradleFlag:
    flags : Union[str, List[str]]
    desc : str
    has_arg : bool = False


PROPERTIES_HEADER = """\
# This file defines properties used by the gradle build.  It must be kept in
# sync with config/nom_build.py.
#
# To regenerate, run ./nom_build --generate-gradle-properties
#
# To view property descriptions (which are command line flags for
# nom_build), run ./nom_build --help.
#
# DO NOT EDIT THIS FILE BY HAND
org.gradle.jvmargs=-Xmx1024m
org.gradle.caching=true
"""

# Help text to be displayed (in addition to the synopsis and flag help, which
# are displayed automatically).
HELP_TEXT = """\
A wrapper around the gradle build that provides the following features:

-   Converts properties into flags to guard against property name spelling errors
    and to provide help descriptions for all properties.
-   Provides pseudo-commands (with the ":nom:" prefix) that encapsulate common
    actions that are difficult to implement in gradle.

Pseudo-commands:
    :nom:generate_golden_file - regenerates the golden file from the current
        set of flyway files.
"""

# Define all of our special gradle properties here.
# TODO(b/169318491): use consistent naming style for properties and variables.
PROPERTIES = [
    Property('mavenUrl',
             'URL to use for the main maven repository (defaults to maven '
             'central).  This can be http(s) or a "gcs" repo.'),
    Property('pluginsUrl',
             'URL to use for the gradle plugins repository (defaults to maven '
             'central, see also mavenUrl'),
    Property('uploaderDestination',
             'Location to upload test reports to.  Normally this should be a '
             'GCS url (see also uploaderCredentialsFile)'),
    Property('uploaderCredentialsFile',
             'json credentials file to use to upload test reports.'),
    Property('uploaderMultithreadedUpload',
             'Whether to enable multithread upload.'),
    Property('verboseTestOutput',
             'If true, show all test output in near-realtime.',
             'false',
             bool),
    Property('flowDocsFile',
             'Output filename for the flowDocsTool command.'),
    Property('enableDependencyLocking',
             'Enables dependency locking.',
             'true',
             bool),
    Property('enableCrossReferencing',
             'generate metadata during java compile (used for kythe source '
             'reference generation).',
             'false'),
    Property('testFilter',
             'Comma separated list of test patterns, if specified run only '
             'these.'),
    Property('environment', 'GAE Environment for deployment and staging.'),

    # Cloud SQL properties
    Property('dbServer',
             'Sets the target database of a Flyway task. This may be a '
             'registry environment name (e.g., alpha) or the host[:port] '
             'of a database that accepts direct IP access.'),
    Property('dbName',
             'Database name to use in connection.',
             'postgres'),
    Property('dbUser', 'Database user name for use in connection'),
    Property('dbPassword', 'Database password for use in connection'),

    Property('publish_repo',
             'Maven repository that hosts the Cloud SQL schema jar and the '
             'registry server test jars. Such jars are needed for '
             'server/schema integration tests. Please refer to <a '
             'href="./integration/README.md">integration project</a> for more '
             'information.'),
    Property('baseSchemaTag',
             'The nomulus version tag of the schema for use in the schema'
             'deployment integration test (:db:schemaIncrementalDeployTest)'),
    Property('schema_version',
             'The nomulus version tag of the schema for use in a database'
             'integration test.'),
    Property('nomulus_version',
             'The version of nomulus to test against in a database '
             'integration test.'),
    Property('dot_path',
             'The path to "dot", part of the graphviz package that converts '
             'a BEAM pipeline to image. Setting this property to empty string '
             'will disable image generation.',
             '/usr/bin/dot'),
    Property('pipeline',
             'The name of the Beam pipeline being staged.')
]

GRADLE_FLAGS = [
    GradleFlag(['-a', '--no-rebuild'],
               'Do not rebuild project dependencies.'),
    GradleFlag(['-b', '--build-file'], 'Specify the build file.', True),
    GradleFlag(['--build-cache'],
               'Enables the Gradle build cache. Gradle will try to reuse '
               'outputs from previous builds.'),
    GradleFlag(['-c', '--settings-file'], 'Specify the settings file.', True),
    GradleFlag(['--configure-on-demand'],
               'Configure necessary projects only. Gradle will attempt to '
               'reduce configuration time for large multi-project builds. '
               '[incubating]'),
    GradleFlag(['--console'],
               'Specifies which type of console output to generate. Values '
               "are 'plain', 'auto' (default), 'rich' or 'verbose'.",
               True),
    GradleFlag(['--continue'], 'Continue task execution after a task failure.'),
    GradleFlag(['-D', '--system-prop'],
               'Set system property of the JVM (e.g. -Dmyprop=myvalue).',
               True),
    GradleFlag(['-d', '--debug'],
               'Log in debug mode (includes normal stacktrace).'),
    GradleFlag(['--daemon'],
               'Uses the Gradle Daemon to run the build. Starts the Daemon '
               'if not running.'),
    GradleFlag(['--foreground'], 'Starts the Gradle Daemon in the foreground.'),
    GradleFlag(['-g', '--gradle-user-home'],
               'Specifies the gradle user home directory.',
               True),
    GradleFlag(['-I', '--init-script'], 'Specify an initialization script.',
               True),
    GradleFlag(['-i', '--info'], 'Set log level to info.'),
    GradleFlag(['--include-build'],
               'Include the specified build in the composite.',
               True),
    GradleFlag(['-m', '--dry-run'],
               'Run the builds with all task actions disabled.'),
    GradleFlag(['--max-workers'],
               'Configure the number of concurrent workers Gradle is '
               'allowed to use.',
               True),
    GradleFlag(['--no-build-cache'], 'Disables the Gradle build cache.'),
    GradleFlag(['--no-configure-on-demand'],
               'Disables the use of configuration on demand. [incubating]'),
    GradleFlag(['--no-daemon'],
               'Do not use the Gradle daemon to run the build. Useful '
               'occasionally if you have configured Gradle to always run '
               'with the daemon by default.'),
    GradleFlag(['--no-parallel'],
               'Disables parallel execution to build projects.'),
    GradleFlag(['--no-scan'],
               'Disables the creation of a build scan. For more information '
               'about build scans, please visit '
               'https://gradle.com/build-scans.'),
    GradleFlag(['--offline'],
               'Execute the build without accessing network resources.'),
    GradleFlag(['-P', '--project-prop'],
               'Set project property for the build script (e.g. '
               '-Pmyprop=myvalue).',
               True),
    GradleFlag(['-p', '--project-dir'],
               'Specifies the start directory for Gradle. Defaults to '
               'current directory.'),
    GradleFlag(['--parallel'],
               'Build projects in parallel. Gradle will attempt to '
               'determine the optimal number of executor threads to use.'),
    GradleFlag(['--priority'],
               'Specifies the scheduling priority for the Gradle daemon and '
               "all processes launched by it. Values are 'normal' (default) "
               "or 'low' [incubating]",
               True),
    GradleFlag(['--profile'],
               'Profile build execution time and generates a report in the '
               '<build_dir>/reports/profile directory.'),
    GradleFlag(['--project-cache-dir'],
               'Specify the project-specific cache directory. Defaults to '
               '.gradle in the root project directory.',
               True),
    GradleFlag(['-q', '--quiet'], 'Log errors only.'),
    GradleFlag(['--refresh-dependencies'], 'Refresh the state of dependencies.'),
    GradleFlag(['--rerun-tasks'], 'Ignore previously cached task results.'),
    GradleFlag(['-S', '--full-stacktrace'],
               'Print out the full (very verbose) stacktrace for all '
               'exceptions.'),
    GradleFlag(['-s', '--stacktrace'],
               'Print out the stacktrace for all exceptions.'),
    GradleFlag(['--scan'],
               'Creates a build scan. Gradle will emit a warning if the '
               'build scan plugin has not been applied. '
               '(https://gradle.com/build-scans)'),
    GradleFlag(['--status'],
               'Shows status of running and recently stopped Gradle '
               'Daemon(s).'),
    GradleFlag(['--stop'], 'Stops the Gradle Daemon if it is running.'),
    GradleFlag(['-t', '--continuous'],
               'Enables continuous build. Gradle does not exit and will '
               're-execute tasks when task file inputs change.'),
    GradleFlag(['--update-locks'],
               'Perform a partial update of the dependency lock, letting '
               'passed in module notations change version. [incubating]'),
    GradleFlag(['-v', '--version'], 'Print version info.'),
    GradleFlag(['-w', '--warn'], 'Set log level to warn.'),
    GradleFlag(['--warning-mode'],
               'Specifies which mode of warnings to generate. Values are '
               "'all', 'fail', 'summary'(default) or 'none'",
               True),
    GradleFlag(['--write-locks'],
               'Persists dependency resolution for locked configurations, '
               'ignoring existing locking information if it exists '
               '[incubating]'),
    GradleFlag(['-x', '--exclude-task'],
               'Specify a task to be excluded from execution.',
               True),
]

def generate_gradle_properties() -> str:
    """Returns the expected contents of gradle.properties."""
    out = io.StringIO()
    out.write(PROPERTIES_HEADER)

    for prop in PROPERTIES:
        out.write(f'{prop.name}={prop.default}\n')

    return out.getvalue()


def get_root() -> str:
    """Returns the root of the nomulus build tree."""
    cur_dir = os.getcwd()
    if not os.path.exists(os.path.join(cur_dir, 'buildSrc')) or \
       not os.path.exists(os.path.join(cur_dir, 'core')) or \
       not os.path.exists(os.path.join(cur_dir, 'gradle.properties')):
        raise Exception('You must run this script from the root directory')
    return cur_dir


class Abort(Exception):
    """Raised to terminate the process with a non-zero error code.

    Parameters are ignored.
    """


def do_pseudo_task(task: str) -> None:
    root = get_root()
    if task == ':nom:generate_golden_file':
        if not subprocess.call([f'{root}/gradlew', ':db:test']):
            print('\033[33mWARNING:\033[0m Golden schema appears to be '
                  'up-to-date.  If you are making schema changes,  be sure to '
                  'add a flyway file for them.')
            return
        print('\033[33mWARNING:\033[0m Ignore the above failure, it is '
              'expected.')

        # Copy the new schema into place.
        shutil.copy(f'{root}/db/build/resources/test/testcontainer/'
                    'mount/dump.txt',
                    f'{root}/db/src/main/resources/sql/schema/'
                    'nomulus.golden.sql')

        # Rerun :db:test and regenerate the ER diagram (at "warning" log
        # level so it doesn't generate pages of messaging)
        if subprocess.call([f'{root}/gradlew', ':db:test', 'devTool',
                            '--args=-e localhost --log_level=WARNING '
                            'generate_sql_er_diagram -o '
                            f'{root}/db/src/main/resources/sql/er_diagram']):
            print('\033[31mERROR:\033[0m Golden file test or ER diagram '
                  'generation failed after copying schema.  Please check your '
                  'flyway files.')
            raise Abort()
    else:
        print(f'\033[31mERROR:\033[0m Unknown task {task}')
        raise Abort()


def main(args) -> int:
    parser = argparse.ArgumentParser('nom_build', description=HELP_TEXT,
                                     formatter_class=argparse.RawTextHelpFormatter)
    for prop in PROPERTIES:
        parser.add_argument('--' + prop.name, default=prop.default,
                            help=prop.desc)

    # Add Gradle flags.  We set 'dest' to the first flag to get a name that is
    # predictable for getattr (even though it will have a leading '-' and thus
    # we can't use normal python attribute syntax to get it).
    for flag in GRADLE_FLAGS:
        if flag.has_arg:
            parser.add_argument(*flag.flags, dest=flag.flags[0],
                                help=flag.desc)
        else:
            parser.add_argument(*flag.flags, dest=flag.flags[0],
                                help=flag.desc,
                                action='store_true')

    # Add a flag to regenerate the gradle properties file.
    parser.add_argument('--generate-gradle-properties',
                        help='Regenerate the gradle.properties file.  This '
                        'file must be regenerated when changes are made to '
                        'config/nom_build.py, and should not be updated by '
                        'hand.',
                        action='store_true')

    # Consume the remaining non-flag arguments.
    parser.add_argument('non_flag_args', nargs='*')

    # Parse command line arguments. Note that this exits the program and
    # prints usage if either of the help options (-h, --help) are specified.
    args = parser.parse_args(args)

    gradle_properties = generate_gradle_properties()
    root = get_root()

    # If we're regenerating properties, do so and exit.
    if args.generate_gradle_properties:
        with open(f'{root}/gradle.properties', 'w') as dst:
            dst.write(gradle_properties)
        return 0

    # Verify that the gradle properties file is what we expect it to be.
    with open(f'{root}/gradle.properties') as src:
        if src.read() != gradle_properties:
            print('\033[33mWARNING:\033[0m Gradle properties out of sync '
                  'with nom_build.  Run with --generate-gradle-properties '
                  'to regenerate.')

    # Add properties to the gradle argument list.
    gradle_command = [f'{root}/gradlew']
    for prop in PROPERTIES:
        arg_val = getattr(args, prop.name)
        if arg_val != prop.default:
            prop.validate(arg_val)
            gradle_command.extend(['-P', f'{prop.name}={arg_val}'])

    # Add Gradle flags to the gradle argument list.
    for flag in GRADLE_FLAGS:
        arg_val = getattr(args, flag.flags[0])
        if arg_val:
            gradle_command.append(flag.flags[-1])
            if flag.has_arg:
                gradle_command.append(arg_val)

    # See if there are any special ":nom:" pseudo-tasks specified.
    got_non_pseudo_tasks = False
    got_pseudo_tasks = False
    for arg in args.non_flag_args[1:]:
        if arg.startswith(':nom:'):
            if got_non_pseudo_tasks:
                # We can't currently deal with the situation of gradle tasks
                # before pseudo-tasks.  This could be implemented by invoking
                # gradle for only the set of gradle tasks before the pseudo
                # task, but that's overkill for now.
                print(f'\033[31mERROR:\033[0m Pseudo task ({arg}) must be '
                      'specified prior to all actual gradle tasks.  Aborting.')
                return 1
            do_pseudo_task(arg)
            got_pseudo_tasks = True
        else:
            got_non_pseudo_tasks = True
    non_flag_args = [
        arg for arg in args.non_flag_args[1:] if not arg.startswith(':nom:')]

    if not non_flag_args:
        if not got_pseudo_tasks:
            print('\033[33mWARNING:\033[0m No tasks specified.  Not '
                  'doing anything')
        return 0

    # Add the non-flag args (we exclude the first, which is the command name
    # itself) and run.
    gradle_command.extend(non_flag_args)
    return subprocess.call(gradle_command)


if __name__ == '__main__':
    try:
        sys.exit(main(sys.argv))
    except Abort as ex:
        sys.exit(1)

