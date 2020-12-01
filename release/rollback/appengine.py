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
"""Helper for using the AppEngine Admin REST API."""

import time
from typing import FrozenSet, Optional, Set, Tuple

from googleapiclient import discovery

import common

# AppEngine services under management.
SERVICES = frozenset(['backend', 'default', 'pubapi', 'tools'])
# Number of times to check the status of an operation before timing out.
_STATUS_CHECK_TIMES = 5
# Delay between status checks of a long-running operation, in seconds
_STATUS_CHECK_INTERVAL = 5


class AppEngineAdmin:
    """Wrapper around the AppEngine Admin REST API client.

    This class provides wrapper methods around the REST API for service and
    version queries and for migrating between versions.
    """
    def __init__(self,
                 project: str,
                 service_lookup: Optional[discovery.Resource] = None,
                 status_check_interval: int = _STATUS_CHECK_INTERVAL) -> None:
        """Initialize this instance for an AppEngine(GCP) project.

        Args:
            project: The GCP project name of this AppEngine instance.
            service_lookup: The GCP discovery handle for service API lookup.
            status_check_interval: The delay in seconds between status queries
                when executing long running operations.
        """
        self._project = project

        if service_lookup is not None:
            apps = service_lookup.apps()
        else:
            apps = discovery.build('appengine', 'v1beta').apps()

        self._services = apps.services()
        self._versions = self._services.versions()
        self._instances = self._versions.instances()
        self._operations = apps.operations()
        self._status_check_interval = status_check_interval

    @property
    def project(self):
        return self._project

    def get_serving_versions(self) -> FrozenSet[common.VersionKey]:
        """Returns the serving versions of every Nomulus service.

        For each service in appengine.SERVICES, gets the version(s) actually
        serving traffic. Services with the 'SERVING' status but no allocated
        traffic are not included. Services not included in appengine.SERVICES
        are also ignored.

        Returns: An immutable collection of the serving versions grouped by
            service.
        """
        services = common.list_all_pages(self._services.list,
                                         'services',
                                         appsId=self._project)

        # Response format is specified at
        # http://googleapis.github.io/google-api-python-client/docs/dyn/appengine_v1beta.apps.services.html#list.

        versions = []
        for service in services:
            if service['id'] in SERVICES:
                # yapf: disable
                versions_with_traffic = (
                    service.get('split', {}).get('allocations', {}).keys())
                # yapf: enable
                for version in versions_with_traffic:
                    versions.append(common.VersionKey(service['id'], version))

        return frozenset(versions)


# yapf: disable  #  argument indent wrong
    def get_version_configs(
            self, versions: Set[common.VersionKey]
    ) -> FrozenSet[common.VersionConfig]:
        # yapf: enable
        """Returns the configuration of requested versions.

        For each version in the request, gets the rollback-related data from
        its static configuration (found in appengine-web.xml).

        Args:
            versions: A set of the VersionKey objects, each containing the
                versions being queried in that service.

        Returns:
            The version configurations in an immutable set.
        """
        requested_services = {version.service_id for version in versions}

        version_configs = []
        # Sort the requested services for ease of testing. For now the mocked
        # AppEngine admin in appengine_test can only respond in a fixed order.
        for service_id in sorted(requested_services):
            response = common.list_all_pages(self._versions.list,
                                             'versions',
                                             appsId=self._project,
                                             servicesId=service_id)

            # Format of version_list is defined at
            # https://googleapis.github.io/google-api-python-client/docs/dyn/appengine_v1beta.apps.services.versions.html#list.

            for version in response:
                if common.VersionKey(service_id, version['id']) in versions:
                    scalings = [
                        s for s in list(common.AppEngineScaling)
                        if s.value in version
                    ]
                    if len(scalings) != 1:
                        raise common.CannotRollbackError(
                            f'Expecting exactly one scaling, found {scalings}')

                    scaling = common.AppEngineScaling(list(scalings)[0])
                    if scaling == common.AppEngineScaling.MANUAL:
                        manual_instances = version.get(
                            scaling.value).get('instances')
                    else:
                        manual_instances = None

                    version_configs.append(
                        common.VersionConfig(service_id, version['id'],
                                             scaling, manual_instances))

        return frozenset(version_configs)

    def list_instances(
            self,
            version: common.VersionKey) -> Tuple[common.VmInstanceInfo, ...]:
        instances = common.list_all_pages(self._versions.instances().list,
                                          'instances',
                                          appsId=self._project,
                                          servicesId=version.service_id,
                                          versionsId=version.version_id)

        # Format of version_list is defined at
        # https://googleapis.github.io/google-api-python-client/docs/dyn/appengine_v1beta.apps.services.versions.instances.html#list

        return tuple([
            common.VmInstanceInfo(
                inst['id'], common.parse_gcp_timestamp(inst['startTime']))
            for inst in instances
        ])

    def set_manual_scaling_num_instance(self, service_id: str, version_id: str,
                                        manual_instances: int) -> None:
        """Creates an request to change an AppEngine version's status."""
        update_mask = 'manualScaling.instances'
        body = {'manualScaling': {'instances': manual_instances}}
        response = self._versions.patch(appsId=self._project,
                                        servicesId=service_id,
                                        versionsId=version_id,
                                        updateMask=update_mask,
                                        body=body).execute()

        operation_id = response.get('name').split('operations/')[1]
        for _ in range(_STATUS_CHECK_TIMES):
            if self.query_operation_status(operation_id):
                return
            time.sleep(self._status_check_interval)

        raise common.CannotRollbackError(
            f'Operation {operation_id} timed out.')

    def query_operation_status(self, operation_id):
        response = self._operations.get(appsId=self._project,
                                        operationsId=operation_id).execute()
        if response.get('response') is not None:
            return True

        if response.get('error') is not None:
            raise common.CannotRollbackError(response['error'])

        assert not response.get('done'), 'Operation done but no results.'
        return False
