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
from typing import Any, Dict, FrozenSet

from googleapiclient import discovery
from googleapiclient import http

import common


class PagingError(Exception):
    """Error for unexpected partial results.

    List calls in this module do not handle pagination. This error is raised
    when a partial result is received.
    """
    def __init__(self, uri: str):
        super().__init__(
            self, f'Received paged response unexpectedly when calling {uri}. '
            'Consider increase PAGE_SIZE.')


class AppEngineAdmin:
    """Wrapper around the AppEngine Admin REST API client.

    This class provides wrapper methods around the RESET API for service and
    version queries and for migrating between versions.
    """

    # AppEngine services under management.
    SERVICES = frozenset(['backend', 'default', 'pubapi', 'tools'])

    # Forces 'list' calls (for services and versions) to return all
    # results in one shot, to avoid having to handl pagination. This values
    # should be greater than the maximum allowed services and versions in any
    # project (
    # https://cloud.google.com/appengine/docs/standard/python/an-overview-of-app-engine#limits).
    PAGE_SIZE = 250

    # Number of times to check the status of an operation before timing out.
    STATUS_CHECK_TIMES = 5
    # Delay between status checks of a long-running operation, in seconds
    STATUS_CHECK_INTERVAL = 5

    def __init__(self,
                 project: str,
                 service_lookup: discovery.Resource = None,
                 status_check_interval: int = STATUS_CHECK_INTERVAL) -> None:
        """Initialize this instance for an AppEngine(GCP) project."""
        self._project = project
        apps = service_lookup.apps(
        ) if service_lookup is not None else discovery.build(
            'appengine', 'v1beta').apps()
        self._services = apps.services()
        self._operations = apps.operations()
        self._status_check_interval = status_check_interval

    @property
    def project(self):
        return self._project

    def _checked_request(self, request: http.HttpRequest) -> Dict[str, Any]:
        """Verifies that all results are returned for a request."""
        response = request.execute()
        if 'nextPageToken' in response:
            raise PagingError(request.uri)

        return response

    def get_serving_versions(self) -> FrozenSet[common.VersionKey]:
        """Returns the serving versions of every Nomulus service.

        For each service in AppEngineAdmin.SERVICES, gets the version(s)
        actually serving traffic. Services with the 'SERVING' status but no
        allocated traffic are not included. Services not included in
        AppEngineAdmin.SERVICES are also ignored.

        Returns: An immutable collection of the serving versions grouped by
            service.
        """
        response = self._checked_request(
            self._services.list(appsId=self._project,
                                pageSize=AppEngineAdmin.PAGE_SIZE))

        # Response format is specified at
        # http://googleapis.github.io/google-api-python-client/docs/dyn/appengine_v1beta5.apps.services.html#list.

        versions = []
        for service in response.get('services', []):
            if service['id'] in AppEngineAdmin.SERVICES:
                versions_with_traffic = service.get('split',
                                                    {}).get('allocations',
                                                            {}).keys()
                for version in versions_with_traffic:
                    versions.append(common.VersionKey(service['id'], version))

        return frozenset(versions)


# yapf: disable  #  argument indent wrong
    def get_version_configs(
            self, versions: FrozenSet[common.VersionKey]
    ) -> FrozenSet[common.VersionConfig]:
        # yapf: enable
        """Returns the configuration of requested versions.

        For each version in the request, gets the rollback-related data from
        its static configuration (found in appengine-web.xml).

        Args:
            versions: A collection of the Service objects, each containing the
                versions being queried in that service.

        Returns:
            The version configurations in an immutable collection.
        """
        requested_services = {version.service_id for version in versions}

        version_configs = []
        # Sort the requested services for ease of testing. For now the mocked
        # AppEngine admin in appengine_test can only respond in a fixed order.
        for service_id in sorted(requested_services):
            response = self._checked_request(self._services.versions().list(
                appsId=self._project,
                servicesId=service_id,
                pageSize=AppEngineAdmin.PAGE_SIZE))

            # Format of version_list is defined at
            # https://googleapis.github.io/google-api-python-client/docs/dyn/appengine_v1beta5.apps.services.versions.html#list.

            for version in response.get('versions', []):
                if common.VersionKey(service_id, version['id']) in versions:
                    scalings = [
                        s for s in list(common.AppEngineScaling)
                        if s.value in version
                    ]
                    if len(scalings) != 1:
                        raise common.CannotRollbackError(
                            f'Expecting exactly one scaling, found {scalings}')

                    scaling = common.AppEngineScaling(next(iter(scalings)))
                    if scaling == common.AppEngineScaling.MANUAL:
                        manual_instances = version.get(
                            scaling.value).get('instances')
                    else:
                        manual_instances = None

                    version_configs.append(
                        common.VersionConfig(service_id, version['id'],
                                             scaling, manual_instances))

        return frozenset(version_configs)

    def set_manual_scaling_num_instance(self, service_id: str, version_id: str,
                                        manual_instances: int) -> None:
        """Creates an request to change an AppEngine version's status."""
        update_mask = 'manualScaling.instances'
        body = {'manualScaling': {'instances': manual_instances}}
        response = self._services.versions().patch(appsId=self._project,
                                                   servicesId=service_id,
                                                   versionsId=version_id,
                                                   updateMask=update_mask,
                                                   body=body).execute()

        operation_id = response.get('name').split('operations/')[1]
        for _ in range(AppEngineAdmin.STATUS_CHECK_TIMES):
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
