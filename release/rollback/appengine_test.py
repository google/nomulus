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
"""Unit tests for appengine."""
from typing import Any, Dict, List, Tuple, Union
import unittest
from unittest import mock
from unittest.mock import patch

import appengine
import common


def setup_appengine_admin() -> Tuple[object, object]:
    """Helper for setting up a mocked AppEngineAdmin instance.

    Returns:
        An AppEngineAdmin instance and a request with which API responses can
        be mocked.
    """

    # Assign mocked API response to mock_request.execute.
    mock_request = mock.Mock('googleapiclient.http.HttpRequest')
    mock_request.uri = mock.Mock(return_value='myuri')
    # Mocked resource shared by services, versions, and operations.
    resource = mock.Mock(object)
    resource.list = mock.Mock(return_value=mock_request)
    resource.get = mock.Mock(return_value=mock_request)
    resource.patch = mock.Mock(return_value=mock_request)

    # Root resource of AppEngine API. Exact type unknown.
    apps = mock.Mock(object)
    apps.services = mock.Mock(return_value=resource)
    apps.services.return_value.versions = mock.Mock(return_value=resource)
    apps.operations = mock.Mock(return_value=resource)
    service_lookup = mock.Mock('discovery.Resource')
    service_lookup.apps = mock.Mock(return_value=apps)
    appengine_admin = appengine.AppEngineAdmin('project', service_lookup, 1)

    return (appengine_admin, mock_request)


class AppEngineTestCase(unittest.TestCase):
    """Unit tests for appengine."""
    def setUp(self) -> None:
        self._client, self._mock_request = setup_appengine_admin()
        self.addCleanup(patch.stopall)

    def _set_mocked_response(
            self, responses: Union[Dict[str, Any], List[Dict[str,
                                                             Any]]]) -> None:
        if isinstance(responses, list):
            self._mock_request.execute = mock.Mock(side_effect=responses)
        else:
            self._mock_request.execute = mock.Mock(return_value=responses)

    def test_checked_request_multipage_raises(self) -> None:
        self._set_mocked_response({'nextPageToken': ''})
        self.assertRaises(appengine.PagingError,
                          self._client.get_serving_versions)

    def test_get_serving_versions(self) -> None:
        self._set_mocked_response({
            'services': [{
                'split': {
                    'allocations': {
                        'my_version': 3.14,
                    }
                },
                'id': 'pubapi'
            }, {
                'split': {
                    'allocations': {
                        'another_version': 2.71,
                    }
                },
                'id': 'error_dashboard'
            }]
        })
        self.assertEqual(
            self._client.get_serving_versions(),
            frozenset([common.VersionKey('pubapi', 'my_version')]))

    def test_get_version_configs(self):
        self._set_mocked_response({
            'versions': [{
                'basicScaling': {
                    'maxInstances': 10
                },
                'id': 'version'
            }]
        })
        self.assertEqual(
            self._client.get_version_configs(
                frozenset([common.VersionKey('default', 'version')])),
            frozenset([
                common.VersionConfig('default', 'version',
                                     common.AppEngineScaling.BASIC)
            ]))

    def test_async_update(self):
        self._set_mocked_response([
            {
                'name': 'project/operations/op_id',
                'done': False
            },
            {
                'name': 'project/operations/op_id',
                'done': False
            },
            {
                'name': 'project/operations/op_id',
                'response': {},
                'done': True
            },
        ])
        self._client.set_manual_scaling_num_instance('service', 'version', 1)
        self.assertEqual(self._mock_request.execute.call_count, 3)


if __name__ == '__main__':
    unittest.main()
