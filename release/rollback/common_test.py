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
"""Unit tests for the common module."""
import datetime
import unittest
from unittest import mock
from unittest.mock import call, patch

import common


class CommonTestCase(unittest.TestCase):
    """Unit tests for the common module."""
    def setUp(self) -> None:
        self._mock_request = mock.MagicMock()
        self._mock_factory = mock.MagicMock()
        self._mock_factory.return_value = self._mock_request
        self.addCleanup(patch.stopall)

    def test_list_all_pages_single_page(self):
        self._mock_request.execute.return_value = {'data': [1]}
        response = common.list_all_pages('data', self._mock_factory)
        self.assertSequenceEqual(response, [1])
        self._mock_factory.assert_called_once_with(None)

    def test_list_all_pages_multi_page(self):
        self._mock_request.execute.side_effect = [{
            'data': [1],
            'nextPageToken': 'token'
        }, {
            'data': [2]
        }]
        response = common.list_all_pages('data', self._mock_factory)
        self.assertSequenceEqual(response, [1, 2])
        self.assertSequenceEqual(self._mock_factory.call_args_list,
                                 [call(None), call('token')])

    def test_parse_timestamp(self):
        self.assertEqual(common.parse_gcp_timestamp('2020-01-01T00:00:00Z'),
                         datetime.datetime(2020, 1, 1))

    def test_parse_timestamp_irregular_nano_digits(self):
        # datetime only accepts 3 or 6 digits in fractional second.
        self.assertRaises(
            ValueError,
            lambda: datetime.datetime.fromisoformat('2020-01-01T00:00:00.9'))
        self.assertEqual(common.parse_gcp_timestamp('2020-01-01T00:00:00.9Z'),
                         datetime.datetime(2020, 1, 1, microsecond=900000))


if __name__ == '__main__':
    unittest.main()
