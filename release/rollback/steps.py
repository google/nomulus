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
"""Definition of rollback steps and factory methods to create them."""

import dataclasses
import subprocess
import textwrap
from typing import Tuple

import appengine
import common


@dataclasses.dataclass(frozen=True)
class RollbackStep:
    """One rollback step.

    Most steps are implemented using commandline tools, e.g., gcloud and
    gsutil, and execute their commands by forking a subprocess. Each step
    also has a info method that returns its command with a description.

    Two steps are handled differently. The _UpdateDeployTag step gets a piped
    shell command, which needs to be handled differently. The
    _SetManualScalingNumInstances step uses the AppEngine Admin API client in
    this package to set the number of instances. The Nomulus set_num_instances
    command is not working right now.
    """

    description: str
    command: Tuple[str, ...]

    def info(self) -> str:
        return f'# {self.description}\n' f'{" ".join(self.command)}'

    def execute(self) -> None:
        """Executes the step.

        Raises:
            CannotRollbackError if command fails.
        """
        if subprocess.call(self.command) != 0:
            raise common.CannotRollbackError(f'Failed: {self.description}')


def check_schema_compatibility(dev_project: str, nom_tag: str,
                               sql_tag: str) -> RollbackStep:

    return RollbackStep(description='Check compatibility with SQL schema.',
                        command=(f'{common.get_nomulus_root()}/nom_build',
                                 ':integration:sqlIntegrationTest',
                                 f'--schema_version={sql_tag}',
                                 f'--nomulus_version={nom_tag}',
                                 '--publish_repo='
                                 f'gcs://{dev_project}-deployed-tags/maven'))


@dataclasses.dataclass(frozen=True)
class _SetManualScalingNumInstances(RollbackStep):
    """Sets the number of instances for a manual scaling version.

     The Nomulus set_num_instances command is currently broken. This step uses
     the AppEngine REST API to update the version.
     """

    appengine_admin: appengine.AppEngineAdmin
    version: common.VersionKey
    num_instance: int

    def execute(self) -> None:
        self.appengine_admin.set_manual_scaling_num_instance(
            self.version.service_id, self.version.version_id,
            self.num_instance)


def set_manual_scaling_instances(appengine_admin: appengine.AppEngineAdmin,
                                 version: common.VersionConfig,
                                 num_instances: int) -> RollbackStep:

    cmd_description = textwrap.dedent("""\
    Nomulus set_num_instances command is currently broken.
    This script uses the AppEngine REST API to update the version.
    To set this value without using this tool, you may use the REST API at
    https://cloud.google.com/appengine/docs/admin-api/reference/rest/v1beta/apps.services.versions/patch
    """)
    return _SetManualScalingNumInstances(
        f'Set number of instance for manual-scaling version '
        f'{version.version_id} in {version.service_id} to {num_instances}.',
        (cmd_description, ''), appengine_admin, version, num_instances)


def start_or_stop_version(project: str, action: str,
                          version: common.VersionKey) -> RollbackStep:
    """Creates a rollback step that starts or stops an AppEngine version.

    Args:
        project: The GCP project of the AppEngine application.
        action: Start or Stop.
        version: The version being managed.
    """
    return RollbackStep(
        f'{action.title()} {version.version_id} in {version.service_id}',
        ('gcloud', 'app', 'versions', action, version.version_id, '--quiet',
         '--service', version.service_id, '--project', project))


def direct_service_traffic_to_version(
        project: str, version: common.VersionKey) -> RollbackStep:
    return RollbackStep(
        f'Direct all traffic to {version.version_id} in {version.service_id}.',
        ('gcloud', 'app', 'services', 'set-traffic', version.service_id,
         '--quiet', f'--splits={version.version_id}=1', '--project', project))


@dataclasses.dataclass(frozen=True)
class _UpdateDeployTag(RollbackStep):
    """Updates the deployment tag on GCS."""

    nom_tag: str
    destination: str

    def execute(self) -> None:
        with subprocess.Popen(('gsutil', 'cp', '-', self.destination),
                              stdin=subprocess.PIPE) as p:
            try:
                p.communicate(self.nom_tag.encode('utf-8'))
                if p.wait() != 0:
                    raise common.CannotRollbackError(
                        f'Failed: {self.description}')
            except:
                p.kill()
                raise


def update_deploy_tags(dev_project: str, env: str,
                       nom_tag: str) -> RollbackStep:
    destination = f'gs://{dev_project}-deployed-tags/nomulus.{env}.tag'

    return _UpdateDeployTag(
        f'Update Nomulus tag in {env}',
        (f'echo {nom_tag} | gsutil cp - {destination}', ''), nom_tag,
        destination)
