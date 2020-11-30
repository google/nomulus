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
"""Unit tests of rolling_restart."""

import datetime
import unittest
from unittest import mock

import common
import rolling_restart
import steps

import appengine_test


class RollingRestartTestCase(unittest.TestCase):
    """Tests for rolling_restart."""
    def setUp(self) -> None:
        self._appengine_admin, self._appengine_request = (
            appengine_test.setup_appengine_admin())
        self._version = common.VersionKey('my_service', 'my_version')
        self.addCleanup(mock.patch.stopall)

    def _setup_execute_steps_tests(self):
        self._appengine_request.execute.side_effect = [
            # First list_instance response.
            {
                'instances': [{
                    'id': 'vm_to_delete',
                    'startTime': '2019-01-01T00:00:00Z'
                }, {
                    'id': 'vm_to_stay',
                    'startTime': '2019-01-01T00:00:00Z'
                }]
            },
            # Second list_instance response
            {
                'instances': [{
                    'id': 'vm_to_stay',
                    'startTime': '2019-01-01T00:00:00Z'
                }]
            },
            # Third list_instance response
            {
                'instances': [{
                    'id': 'vm_to_stay',
                    'startTime': '2019-01-01T00:00:00Z'
                }, {
                    'id': 'vm_new',
                    'startTime': '2019-01-01T00:00:00Z'
                }]
            }
        ]

    def _setup_generate_steps_tests(self):
        self._appengine_request.execute.side_effect = [
            # First page of list_instance response.
            {
                'instances': [{
                    'id': 'vm_2019',
                    'startTime': '2019-01-01T00:00:00Z'
                }],
                'nextPageToken':
                'token'
            },
            # Second and final page of list_instance response
            {
                'instances': [{
                    'id': 'vm_2020',
                    'startTime': '2020-01-01T00:00:00Z'
                }]
            }
        ]

    def test_kill_vm_command(self) -> None:
        cmd = steps.kill_nomulus_instance(
            'my_project', common.VersionKey('my_service', 'my_version'),
            'my_inst')
        self.assertEqual(cmd.instance_name, 'my_inst')
        self.assertIn(('gcloud app instances delete my_inst --quiet '
                       '--user-output-enabled=false --service my_service '
                       '--version my_version --project my_project'),
                      cmd.info())

    def _generate_kill_vm_command(self, version: common.VersionKey,
                                  instance_name: str):
        return steps.kill_nomulus_instance(self._appengine_admin.project,
                                           version, instance_name)

    def test_generate_commands(self):
        self._setup_generate_steps_tests()
        commands = rolling_restart.generate_steps(self._appengine_admin,
                                                  self._version,
                                                  datetime.datetime.utcnow())
        self.assertSequenceEqual(commands, [
            self._generate_kill_vm_command(self._version, 'vm_2019'),
            self._generate_kill_vm_command(self._version, 'vm_2020')
        ])

    def test_generate_commands_older_vm(self):
        self._setup_generate_steps_tests()
        version = common.VersionKey('my_service', 'my_version')
        # yapf: disable
        commands = rolling_restart.generate_steps(
            self._appengine_admin,
            version,
            common.parse_gcp_timestamp('2019-12-01T00:00:00Z'))
        # yapf: enable
        self.assertSequenceEqual(
            commands, [self._generate_kill_vm_command(version, 'vm_2019')])

    def test_execute_steps_variable_instances(self):
        self._setup_execute_steps_tests()
        cmd = mock.MagicMock()
        cmd.instance_name = 'vm_to_delete'
        cmds = tuple([cmd])  # yapf does not format (cmd,) correctly.
        rolling_restart.execute_steps(appengine_admin=self._appengine_admin,
                                      version=self._version,
                                      cmds=cmds,
                                      min_delay=0,
                                      fixed_num_instances=None)
        self.assertEqual(self._appengine_request.execute.call_count, 2)

    def test_execute_steps_fixed_instances(self):
        self._setup_execute_steps_tests()
        cmd = mock.MagicMock()
        cmd.instance_name = 'vm_to_delete'
        cmds = tuple([cmd])  # yapf does not format (cmd,) correctly.
        rolling_restart.execute_steps(appengine_admin=self._appengine_admin,
                                      version=self._version,
                                      cmds=cmds,
                                      min_delay=0,
                                      fixed_num_instances=2)
        self.assertEqual(self._appengine_request.execute.call_count, 3)


if __name__ == '__main__':
    unittest.main()
