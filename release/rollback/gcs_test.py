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
"""Unit tests for gcs."""
import textwrap
import unittest
from unittest import mock

import common
import gcs


def setup_gcs_client(env: str):
    """Sets up a mocked GcsClient.

    Args:
        env:  Name of the Nomulus environment.

    Returns:
        A GcsClient instance and two mocked blobs representing the two schema
        tag file and version map file on GCS.
    """

    schema_tag_blob = mock.MagicMock()
    schema_tag_blob.download_as_text.return_value = 'tag\n'
    version_map_blob = mock.MagicMock()
    blobs_by_name = {
        f'nomulus.{env}.versions': version_map_blob,
        f'sql.{env}.tag': schema_tag_blob
    }

    bucket = mock.MagicMock()
    bucket.get_blob.side_effect = lambda blob_name: blobs_by_name[blob_name]
    google_client = mock.MagicMock()
    google_client.get_bucket.return_value = bucket
    gcs_client = gcs.GcsClient('project', google_client)

    return (gcs_client, schema_tag_blob, version_map_blob)


class GcsTestCase(unittest.TestCase):
    """Unit tests for gcs."""
    _ENV = 'crash'

    def setUp(self) -> None:
        self._client, self._schema_tag_blob, self._version_map_blob = \
            setup_gcs_client(self._ENV)
        self.addCleanup(mock.patch.stopall)

    def test_get_schema_tag(self):
        self.assertEqual(self._client.get_schema_tag(self._ENV), 'tag')

    def test_get_versions_by_release(self):
        self._version_map_blob.download_as_text.return_value = \
            'nomulus-20200925-RC02,backend,nomulus-backend-v008'
        self.assertEqual(
            self._client.get_versions_by_release(self._ENV,
                                                 'nomulus-20200925-RC02'),
            frozenset([common.VersionKey('backend', 'nomulus-backend-v008')]))

    def test_get_versions_by_release_not_found(self):
        self._version_map_blob.download_as_text.return_value = \
            'nomulus-20200925-RC02,backend,nomulus-backend-v008'
        self.assertEqual(
            self._client.get_versions_by_release(self._ENV, 'no-such-tag'),
            frozenset([]))

    def test_get_versions_by_release_multiple_service(self):
        self._version_map_blob.download_as_text.return_value = textwrap.dedent(
            """\
            nomulus-20200925-RC02,backend,nomulus-backend-v008
            nomulus-20200925-RC02,default,nomulus-default-v008
            """)
        self.assertEqual(
            self._client.get_versions_by_release(self._ENV,
                                                 'nomulus-20200925-RC02'),
            frozenset([
                common.VersionKey('backend', 'nomulus-backend-v008'),
                common.VersionKey('default', 'nomulus-default-v008')
            ]))

    def test_get_versions_by_release_multiple_deployment(self):
        self._version_map_blob.download_as_text.return_value = textwrap.dedent(
            """\
            nomulus-20200925-RC02,backend,nomulus-backend-v008
            nomulus-20200925-RC02,backend,nomulus-backend-v018
            """)
        self.assertEqual(
            self._client.get_versions_by_release(self._ENV,
                                                 'nomulus-20200925-RC02'),
            frozenset([
                common.VersionKey('backend', 'nomulus-backend-v008'),
                common.VersionKey('backend', 'nomulus-backend-v018')
            ]))

    def test_get_releases_by_versions(self):
        self._version_map_blob.download_as_text.return_value = textwrap.dedent(
            """\
            nomulus-20200925-RC02,backend,nomulus-backend-v008
            nomulus-20200925-RC02,default,nomulus-default-v008
            """)
        self.assertEqual(
            self._client.get_releases_by_versions(
                self._ENV, {
                    common.VersionKey('backend', 'nomulus-backend-v008'),
                    common.VersionKey('default', 'nomulus-default-v008')
                }), {
                    common.VersionKey('backend', 'nomulus-backend-v008'):
                    'nomulus-20200925-RC02',
                    common.VersionKey('default', 'nomulus-default-v008'):
                    'nomulus-20200925-RC02',
                })

    def test_get_recent_deployments(self):
        file_content = textwrap.dedent("""\
            nomulus-20200925-RC02,backend,nomulus-backend-v008
            nomulus-20200925-RC02,default,nomulus-default-v008
            """)
        self._version_map_blob.download_as_text.return_value = file_content
        self.assertEqual(
            self._client.get_recent_deployments(self._ENV, 2), {
                common.VersionKey('default', 'nomulus-default-v008'):
                'nomulus-20200925-RC02',
                common.VersionKey('backend', 'nomulus-backend-v008'):
                'nomulus-20200925-RC02'
            })

    def test_get_recent_deployments_fewer_lines(self):
        self._version_map_blob.download_as_text.return_value = textwrap.dedent(
            """\
            nomulus-20200925-RC02,backend,nomulus-backend-v008
            nomulus-20200925-RC02,default,nomulus-default-v008
            """)
        self.assertEqual(
            self._client.get_recent_deployments(self._ENV, 1), {
                common.VersionKey('default', 'nomulus-default-v008'):
                'nomulus-20200925-RC02'
            })


if __name__ == '__main__':
    unittest.main()
