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
"""Script to rollback the Nomulus server on AppEngine."""

import argparse
import dataclasses
import sys
import textwrap
from typing import Any, Optional, Tuple

import appengine
import gcs
import plan

MAIN_HELP = 'Script to roll back the Nomulus server on AppEngine.'
ROLLBACK_HELP = 'Rolls back Nomulus to the target release.'
GET_SERVING_RELEASE_HELP = 'Shows the release tag(s) of the serving versions.'
GET_RECENT_DEPLOYMENTS_HELP = 'Shows recently deployed versions and their '\
                              'release tags.'
ROLLBACK_MODE_HELP = textwrap.dedent("""\
                     The execution mode.
                     - dryrun: Prints descriptions of all steps.
                     - interactive: Prompts for confirmation before executing
                                    each step.
                     - auto: Executes all steps in one go.
                     """)


@dataclasses.dataclass(frozen=True)
class Argument:
    """Describes a command line argument.

    This class is for use with argparse.ArgumentParser. Except for the
    'arg_names' attribute which specifies the argument name and/or flags, all
    other attributes must match an accepted parameter in the parser's
    add_argument() method.
    """

    arg_names: Tuple[str, ...]
    help: str
    default: Optional[Any] = None
    required: bool = True
    choices: Optional[Tuple[str, ...]] = None

    def get_arg_attrs(self):
        return dict((k, v) for k, v in vars(self).items() if k != 'arg_names')


ARGUMENTS = (Argument(('--dev_project', '-d'),
                      'The GCP project with Nomulus deployment records.'),
             Argument(('--project', '-p'),
                      'The GCP project where the Nomulus server is deployed.'),
             Argument(('--env', '-e'),
                      'The name of the Nomulus server environment.',
                      choices=('production', 'sandbox', 'crash', 'alpha')))

ROLLBACK_ARGUMENTS = (Argument(('--target_release', '-t'),
                               'The release to be deployed.'),
                      Argument(('--run_mode', '-m'),
                               ROLLBACK_MODE_HELP,
                               required=False,
                               default='dryrun',
                               choices=('dryrun', 'interactive', 'auto')))


def rollback(dev_project: str, project: str, env: str, target_release: str,
             run_mode: str) -> None:
    """Rolls back a Nomulus server to the target release.

    Args:
        dev_project: The GCP project with deployment records.
        project: The GCP project of the Nomulus server.
        env: The environment name of the Nomulus server.
        target_release: The tag of the release to be brought up.
        run_mode: How to handle the rollback steps: print-only (dryrun)
                  one step at a time with user confirmation (interactive),
                  or all steps in one shot (automatic).
    """
    steps = plan.get_rollback_plan(gcs.GcsClient(dev_project),
                                   appengine.AppEngineAdmin(project), env,
                                   target_release)

    print('Rollback steps:\n\n')

    for step in steps:
        print(f'{step.info()}\n')

        if run_mode == 'dryrun':
            continue

        if run_mode == 'interactive':
            confirmation = input(
                'Do you wish to (c)ontinue, (s)kip, or (a)bort? ')
            if confirmation == 'a':
                return
            if confirmation == 's':
                continue

        step.execute()


def show_serving_release(dev_project: str, project: str, env: str) -> None:
    """Shows the release tag(s) of the currently serving versions."""
    serving_versions = appengine.AppEngineAdmin(project).get_serving_versions()
    versions_to_tags = gcs.GcsClient(dev_project).get_releases_by_versions(
        env, serving_versions)
    print(f'{project}:')
    for version, tag in versions_to_tags.items():
        print(f'{version.service_id}\t{version.version_id}\t{tag}')


def show_recent_deployments(dev_project: str, project: str, env: str) -> None:
    """Show release and version of recent deployments."""
    num_services = len(appengine.AppEngineAdmin.SERVICES)
    num_records = 3 * num_services
    print(f'{project}:')
    for version, tag in gcs.GcsClient(dev_project).get_recent_deployments(
            env, num_records).items():
        print(f'{version.service_id}\t{version.version_id}\t{tag}')


def main() -> int:
    parser = argparse.ArgumentParser(prog='nom_rollback',
                                     description=MAIN_HELP)
    subparsers = parser.add_subparsers(dest='command',
                                       help='Supported commands')

    rollback_parser = subparsers.add_parser(
        'rollback',
        help=ROLLBACK_HELP,
        formatter_class=argparse.RawTextHelpFormatter)
    for flag in ARGUMENTS:
        rollback_parser.add_argument(*flag.arg_names, **flag.get_arg_attrs())
    for flag in ROLLBACK_ARGUMENTS:
        rollback_parser.add_argument(*flag.arg_names, **flag.get_arg_attrs())

    show_serving_release_parser = subparsers.add_parser(
        'show_serving_release', help=GET_SERVING_RELEASE_HELP)
    for flag in ARGUMENTS:
        show_serving_release_parser.add_argument(*flag.arg_names,
                                                 **flag.get_arg_attrs())

    show_recent_deployments_parser = subparsers.add_parser(
        'show_recent_deployments', help=GET_RECENT_DEPLOYMENTS_HELP)
    for flag in ARGUMENTS:
        show_recent_deployments_parser.add_argument(*flag.arg_names,
                                                    **flag.get_arg_attrs())

    args = parser.parse_args()
    command = args.command
    args = {k: v for k, v in vars(args).items() if k != 'command'}

    {
        'rollback': rollback,
        'show_recent_deployments': show_recent_deployments,
        'show_serving_release': show_serving_release
    }[command](**args)

    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception as ex:  # pylint: disable=broad-except
        print(str(ex))
        sys.exit(1)
