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
"""Helper for managing Nomulus deployment records on GCS."""

from typing import Dict, FrozenSet, Set

from google.cloud import storage

import common


def _get_version_map_name(env: str):
    return f'nomulus.{env}.versions'


def _get_schema_tag_file(env: str):
    return f'sql.{env}.tag'


class GcsClient:
    """Manages Nomulus deployment records on GCS."""
    def __init__(self, project: str, gcs_client=None) -> None:
        """Initializes the instance for a GCP project.

        Args:
            project: The GCP project with Nomulus deployment records.
            gcs_client: Optional API client to use.
        """

        self._project = project

        if gcs_client is not None:
            self._client = gcs_client
        else:
            self._client = storage.Client(self._project)

    @property
    def project(self):
        return self._project

    def _get_deploy_bucket_name(self):
        return f'{self._project}-deployed-tags'

    def _get_release_to_version_mapping(
            self, env: str) -> Dict[common.VersionKey, str]:
        """Returns the content of the release to version mapping file.

        File content is returned in utf-8 encoding. Each line in the file is
        in this format:
        '{RELEASE_TAG},{APP_ENGINE_SERVICE_ID},{APP_ENGINE_VERSION}'.
        """
        file_content = self._client.get_bucket(
            self._get_deploy_bucket_name()).get_blob(
                _get_version_map_name(env)).download_as_text()

        mapping = {}
        for line in file_content.splitlines(False):
            tag, service_id, version_id = line.split(',')
            mapping[common.VersionKey(service_id, version_id)] = tag

        return mapping

    def get_versions_by_release(self, env: str,
                                nom_tag: str) -> FrozenSet[common.VersionKey]:
        """Returns AppEngine version ids of a given Nomulus release tag.

        Fetches the version mapping file maintained by the deployment process
        and parses its content into a collection of VersionKey instances.

        A release may map to multiple versions in a service if it has been
        deployed multiple times. This is not intended behavior and may only
        happen by mistake.

        Args:
            env: The environment of the deployed release, e.g., sandbox.
            nom_tag: The Nomulus release tag.

        Returns:
            An immutable set of VersionKey instances.
        """
        mapping = self._get_release_to_version_mapping(env)
        return frozenset(
            [version for version in mapping if mapping[version] == nom_tag])

    def get_releases_by_versions(
            self, env: str,
            versions: Set[common.VersionKey]) -> Dict[common.VersionKey, str]:
        """Gets the release tags of the AppEngine versions.

        Args:
            env: The environment of the deployed release, e.g., sandbox.
            versions: The AppEngine versions.

        Returns:
            A mapping of versions to release tags.
        """
        mapping = self._get_release_to_version_mapping(env)
        return {
            version: tag
            for version, tag in mapping.items() if version in versions
        }

    def get_recent_deployments(
            self, env: str, num_records: int) -> Dict[common.VersionKey, str]:
        """Gets the most recent deployment records.

        Deployment records are stored in a file, with one line per service.
        Caller should adjust num_records according to the number of services
        in AppEngine.

        Args:
            env: The environment of the deployed release, e.g., sandbox.
            num_records: the number of lines to go back.
        """
        file_content = self._client.get_bucket(
            self._get_deploy_bucket_name()).get_blob(
                _get_version_map_name(env)).download_as_text()

        mapping = {}
        for line in file_content.splitlines(False)[-num_records:]:
            tag, service_id, version_id = line.split(',')
            mapping[common.VersionKey(service_id, version_id)] = tag

        return mapping

    def get_schema_tag(self, env: str) -> str:
        """Gets the release tag of the SQL schema in the given environment.

        This tag is needed for the server/schema compatibility test.
        """
        file_content = self._client.get_bucket(
            self._get_deploy_bucket_name()).get_blob(
                _get_schema_tag_file(env)).download_as_text().splitlines(False)
        assert len(
            file_content
        ) == 1, f'Unexpected content in {_get_schema_tag_file(env)}.'
        return file_content[0]
