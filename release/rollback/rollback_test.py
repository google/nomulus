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
"""End-to-end test of rollback."""
import textwrap
from typing import Any, Dict
import unittest
from unittest import mock

import appengine_test
import gcs_test
import plan


def _make_serving_version(service: str, version: str) -> Dict[str, Any]:
    """Creates description of one serving version in API response."""
    return {
        'split': {
            'allocations': {
                version: 1,
            }
        },
        'id': service
    }


def _make_version_config(version,
                         scaling: str,
                         instance_tag: str,
                         instances: int = 10) -> Dict[str, Any]:
    """Creates one version config as part of an API response."""
    return {scaling: {instance_tag: instances}, 'id': version}


class RollbackTestCase(unittest.TestCase):
    """End-to-end test of rollback."""
    def setUp(self) -> None:
        self._appengine_admin, self._appengine_request = (
            appengine_test.setup_appengine_admin())
        self._gcs_client, self._schema_tag, self._version_map = (
            gcs_test.setup_gcs_client('crash'))
        self.addCleanup(mock.patch.stopall)

    def test_rollback_success(self):
        self._schema_tag.download_as_text.return_value = (
            'nomulus-2010-1014-RC00')
        self._version_map.download_as_text.return_value = textwrap.dedent("""\
        nomulus-20201014-RC00,backend,nomulus-backend-v009
        nomulus-20201014-RC00,default,nomulus-default-v009
        nomulus-20201014-RC00,pubapi,nomulus-pubapi-v009
        nomulus-20201014-RC00,tools,nomulus-tools-v009
        nomulus-20201014-RC01,backend,nomulus-backend-v011
        nomulus-20201014-RC01,default,nomulus-default-v010
        nomulus-20201014-RC01,pubapi,nomulus-pubapi-v010
        nomulus-20201014-RC01,tools,nomulus-tools-v010
        """)
        self._appengine_request.execute.side_effect = [
            # Response to get_serving_versions:
            {
                'services': [
                    _make_serving_version('backend', 'nomulus-backend-v011'),
                    _make_serving_version('default', 'nomulus-default-v010'),
                    _make_serving_version('pubapi', 'nomulus-pubapi-v010'),
                    _make_serving_version('tools', 'nomulus-tools-v010')
                ]
            },
            # Responses to get_version_configs. AppEngineAdmin queries the
            # services by alphabetical order to facilitate this test.
            {
                'versions': [
                    _make_version_config('nomulus-backend-v009',
                                         'basicScaling', 'maxInstances'),
                    _make_version_config('nomulus-backend-v011',
                                         'basicScaling', 'maxInstances')
                ]
            },
            {
                'versions': [
                    _make_version_config('nomulus-default-v009',
                                         'basicScaling', 'maxInstances'),
                    _make_version_config('nomulus-default-v010',
                                         'basicScaling', 'maxInstances')
                ]
            },
            {
                'versions': [
                    _make_version_config('nomulus-pubapi-v009',
                                         'manualScaling', 'instances'),
                    _make_version_config('nomulus-pubapi-v010',
                                         'manualScaling', 'instances')
                ]
            },
            {
                'versions': [
                    _make_version_config('nomulus-tools-v009',
                                         'automaticScaling',
                                         'maxTotalInstances'),
                    _make_version_config('nomulus-tools-v010',
                                         'automaticScaling',
                                         'maxTotalInstances')
                ]
            }
        ]

        steps = plan.get_rollback_plan(self._gcs_client, self._appengine_admin,
                                       'crash', 'nomulus-20201014-RC00')
        self.assertEqual(len(steps), 14)
        self.assertRegex(steps[0].info(),
                         '.*nom_build :integration:sqlIntegrationTest.*')
        self.assertRegex(steps[1].info(), '.*gcloud app versions start.*')
        self.assertRegex(steps[5].info(),
                         '.*gcloud app services set-traffic.*')
        self.assertRegex(steps[9].info(), '.*gcloud app versions stop.*')
        self.assertRegex(steps[13].info(),
                         '.*echo nomulus-20201014-RC00 | gsutil cat -.*')


if __name__ == '__main__':
    unittest.main()
