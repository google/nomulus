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
"""Script to rolling-restart the Nomulus server on AppEngine.

This script effects a rolling restart of the Nomulus server by deleting VM
instances at a controlled pace and leave it to the AppEngine scaling policy
to bring up new VM instances.

For each service, this script gets a list of VM instances and sequentially
handles each instance as follows:
1. Issue a gcloud delete command for this instance.
2. Poll the AppEngine at fixed intervals until this instance no longer exists.

Instance deletion is not instantaneous. An instance actively processing
requests takes time to shutdown, and its replacement almost always comes
up immediately after the shutdown. For this reason, we believe that our current
implementation is sufficient safe, and will not pursue more sophisticated
algorithms.

Note that for backend instances that may handle large queries, it may take tens
of seconds, even minutes, to shut down one of them.

This script also accepts an optional start_time parameter that serves as a
filter of instances to delete: only those instances that started before this
time will be deleted. This parameter makes error handling easy. When this
script fails, simply rerun with the same start_time until it succeeds.
"""
import argparse
import datetime
import sys
import time
from typing import Iterable, Optional, Tuple

import appengine
import common
import steps

HELP_MAIN = 'Script to rolling-restart the Nomulus server on AppEngine'
HELP_MIN_DELAY = 'Minimum delay in seconds between instance deletions.'
HELP_MIN_LIVE_INSTANCE_PERCENT = (
    'Minimum number of instances to keep, as a percentage '
    'of the total at the beginning of the restart process.')


# yapf: disable
def generate_steps(
        appengine_admin: appengine.AppEngineAdmin,
        version: common.VersionKey,
        started_before: datetime.datetime
) -> Tuple[steps.KillNomulusInstance, ...]:
    # yapf: enable
    instances = appengine_admin.list_instances(version)
    return tuple([
        steps.kill_nomulus_instance(appengine_admin.project, version,
                                    inst.instance_name) for inst in instances
        if inst.start_time <= started_before
    ])


def execute_steps(appengine_admin: appengine.AppEngineAdmin,
                  version: common.VersionKey,
                  cmds: Tuple[steps.KillNomulusInstance, ...], min_delay: int,
                  configured_num_instances: Optional[int]) -> None:
    print(f'Restarting {len(cmds)} instances in {version.service_id}')
    for cmd in cmds:
        print(cmd.info())
        cmd.execute()

        while True:
            time.sleep(min_delay)
            running_instances = [
                inst.instance_name
                for inst in appengine_admin.list_instances(version)
            ]
            if cmd.instance_name in running_instances:
                print('Waiting for VM to shut down...')
                continue
            if (configured_num_instances is not None
                    and len(running_instances) < configured_num_instances):
                print('Waiting for new VM to come up...')
                continue
            break
        print('VM instance has shut down.\n')

    print(f'Done: {len(cmds)} instances in {version.service_id}\n')


# yapf: disable
def restart_one_service(appengine_admin: appengine.AppEngineAdmin,
                        version: common.VersionKey,
                        min_delay: int,
                        started_before: datetime.datetime,
                        fixed_num_instances: Optional[int]) -> None:
    # yapf: enable
    """Restart VM instances in one service according to their start time.

    Args:
        appengine_admin: The client of AppEngine Admin API.
        version: The Nomulus version to restart. This must be the currently
            serving version.
        min_delay: The minimum delay between successive deletions.
        started_before: Only VM instances started before this time are to be
            deleted.
        fixed_num_instances: When present, the constant number of instances
            this version is configured with.
    """
    cmds = generate_steps(appengine_admin, version, started_before)
    # yapf: disable
    execute_steps(
        appengine_admin, version, cmds, min_delay, fixed_num_instances)
    # yapf: enable


# yapf: disable
def rolling_restart(project: str,
                    services: Iterable[str],
                    min_delay: int,
                    started_before: datetime.datetime):
    # yapf: enable
    print(f'Rolling restart {project} at '
          f'{common.to_gcp_timestamp(started_before)}\n')
    appengine_admin = appengine.AppEngineAdmin(project)
    version_configs = appengine_admin.get_version_configs(
        set(appengine_admin.get_serving_versions()))
    restart_versions = [
        version for version in version_configs
        if version.service_id in services
    ]
    # yapf: disable
    for version in restart_versions:
        restart_one_service(appengine_admin,
                            version,
                            min_delay,
                            started_before,
                            version.manual_scaling_instances)
    # yapf: enable


def main() -> int:
    parser = argparse.ArgumentParser(prog='rolling_restart',
                                     description=HELP_MAIN)
    parser.add_argument('--project',
                        '-p',
                        required=True,
                        help='The GCP project of the Nomulus server.')
    parser.add_argument('--services',
                        '-s',
                        nargs='+',
                        choices=appengine.SERVICES,
                        default=appengine.SERVICES,
                        help='The services to rollback.')
    parser.add_argument('--min_delay',
                        '-d',
                        type=int,
                        default=5,
                        choices=range(1, 100),
                        help=HELP_MIN_DELAY)
    parser.add_argument(
        '--started_before',
        '-b',
        type=common.parse_gcp_timestamp,
        default=datetime.datetime.utcnow(),
        help='Only kill VM instances started before this time.')

    args = parser.parse_args()
    rolling_restart(**vars(args))
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception as ex:  # pylint: disable=broad-except
        print(ex)
        sys.exit(1)
