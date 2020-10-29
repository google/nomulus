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
"""Generates a sequence of operations for execution."""
from typing import FrozenSet, Tuple

import appengine
import common
import dataclasses

import gcs
import steps


@dataclasses.dataclass(frozen=True)
class ServiceRollback:
    """Data needed for rolling back one service.

    Holds the configurations of both the currently serving version(s) and the
    rollback target in a service.

    Attributes:
        target_version: The version to roll back to.
        serving_versions: The currently serving versions to be stopped. This
            set may be empty. It may also have multiple versions (when traffic
            is split).
    """

    target_version: common.VersionConfig
    serving_versions: FrozenSet[common.VersionConfig]

    def __post_init__(self):
        """Validates that all versions are for the same service."""

        if self.serving_versions:
            for config in self.serving_versions:
                assert config.service_id == self.target_version.service_id


# yapf: disable
def _get_service_rollback_plan(
        target_configs: FrozenSet[common.VersionConfig],
        serving_configs: FrozenSet[common.VersionConfig]
) -> Tuple[ServiceRollback]:
    # yapf: enable
    """Determines the versions to bring up/down in each service.

    In each service, this method makes sure that at least one version is found
    for the rollback target. If multiple versions are found, which may only
    happen if the target release was deployed multiple times, randomly choose
    one.

    If a target version is already serving traffic, instead of checking if it
    gets 100 percent of traffic, this method still generates operations to
    start it and direct all traffic to it. This is not a problem since these
    operations are idempotent.

    Attributes:
        target_configs: The rollback target versions in each managed service
            (as defined in appengine.SERVICES).
        serving_configs: The currently serving versions in each service.

    Raises:
        CannotRollbackError: Rollback is impossible because a target version
            cannot be found for some service.

    Returns:
        For each service, the versions to bring up/down if applicable.
    """
    targets_by_service = {}
    for version in target_configs:
        targets_by_service.setdefault(version.service_id, set()).add(version)

    serving_by_service = {}
    for version in serving_configs:
        serving_by_service.setdefault(version.service_id, set()).add(version)

    # The target_configs parameter only has configs for managed services.
    # Since targets_by_service is derived from it, its keyset() should equal
    # to appengine.SERVICES.
    if targets_by_service.keys() != appengine.SERVICES:
        cannot_rollback = appengine.SERVICES.difference(
            targets_by_service.keys())
        raise common.CannotRollbackError(
            f'Target version(s) not found for {cannot_rollback}')

    plan = []
    for service_id, versions in targets_by_service.items():
        serving_configs = serving_by_service.get(service_id, set())
        versions_to_stop = serving_configs.difference(versions)
        chosen_target = list(versions)[0]
        plan.append(ServiceRollback(chosen_target,
                                    frozenset(versions_to_stop)))

    return tuple(plan)


# yapf: disable
def _generate_steps(
        gcs_client: gcs.GcsClient,
        appengine_admin: appengine.AppEngineAdmin,
        env: str,
        target_release: str,
        rollback_plan: Tuple[ServiceRollback]
) -> Tuple[steps.RollbackStep, ...]:
    # yapf: enable
    """Generates the sequence of operations for execution.

    A rollback consists of the following steps:
    1. Run schema compatibility test for the target release.
    2. For each service,
       a. If the target version does not use automatic scaling, start it.
          i. If target version uses manual scaling, sets its instances to the
             configured values.
       b. If the target version uses automatic scaling, do nothing.
    3. For each service, immediately direct all traffic to the target version.
    4. For each service, go over its versions to be stopped:
       a. If a version uses automatic scaling, do nothing.
       b. If a version does not use automatic scaling, stop it.
         i. If a version uses manual scaling, sets its instances to 1 (one, the
            lowest value allowed on the REST API) to release the instances.
    5. Update the appropriate deployed tag file on GCS with the target release
        tag.

    Returns:
        The sequence of operations to execute for rollback.
    """
    rollback_steps = [
        steps.check_schema_compatibility(gcs_client.project, target_release,
                                         gcs_client.get_schema_tag(env))
    ]

    for plan in rollback_plan:
        if plan.target_version.scaling != common.AppEngineScaling.AUTOMATIC:
            rollback_steps.append(
                steps.start_or_stop_version(appengine_admin.project, 'start',
                                            plan.target_version))
        if plan.target_version.scaling == common.AppEngineScaling.MANUAL:
            rollback_steps.append(
                steps.set_manual_scaling_instances(
                    appengine_admin, plan.target_version,
                    plan.target_version.manual_scaling_instances))

    for plan in rollback_plan:
        rollback_steps.append(
            steps.direct_service_traffic_to_version(appengine_admin.project,
                                                    plan.target_version))

    for plan in rollback_plan:
        for version in plan.serving_versions:
            if plan.target_version.scaling != common.AppEngineScaling.AUTOMATIC:
                rollback_steps.append(
                    steps.start_or_stop_version(appengine_admin.project,
                                                'stop', version))
            if plan.target_version.scaling == common.AppEngineScaling.MANUAL:
                # Release all but one instances. Cannot set num_instances to 0
                # with this api.
                rollback_steps.append(
                    steps.set_manual_scaling_instances(appengine_admin,
                                                       version, 1))

    rollback_steps.append(
        steps.update_deploy_tags(gcs_client.project, env, target_release))

    return tuple(rollback_steps)


def get_rollback_plan(gcs_client: gcs.GcsClient,
                      appengine_admin: appengine.AppEngineAdmin, env: str,
                      target_release: str) -> Tuple[steps.RollbackStep]:
    """Generates the sequence of rollback operations for execution."""
    target_versions = gcs_client.get_versions_by_release(env, target_release)
    serving_versions = appengine_admin.get_serving_versions()
    all_version_configs = appengine_admin.get_version_configs(
        target_versions.union(serving_versions))

    target_configs = frozenset([
        config for config in all_version_configs if config in target_versions
    ])
    serving_configs = frozenset([
        config for config in all_version_configs if config in serving_versions
    ])
    rollback_plan = _get_service_rollback_plan(target_configs, serving_configs)
    return _generate_steps(gcs_client, appengine_admin, env, target_release,
                           rollback_plan)
