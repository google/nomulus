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

import io
import os
import unittest
from unittest import mock
import nom_build
import subprocess

FAKE_PROPERTIES = [
    nom_build.Property('foo', 'help text'),
    nom_build.Property('bar', 'more text', 'true', bool),
]

FAKE_PROP_CONTENTS = nom_build.PROPERTIES_HEADER + 'foo=\nbar=true\n'
PROPERTIES_FILENAME = '/tmp/rootdir/gradle.properties'
GRADLEW = '/tmp/rootdir/gradlew'


class FileFake(io.StringIO):
    """File fake that writes file contents to the dictionary on close."""
    def __init__(self, contents_dict, filename):
        self.dict = contents_dict
        self.filename = filename
        super(FileFake, self).__init__()

    def close(self):
        self.dict[self.filename] = self.getvalue()
        super(FileFake, self).close()


class MyTest(unittest.TestCase):

    def open_fake(self, filename, action='r'):
        if action == 'r':
            return io.StringIO(self.file_contents.get(filename, ''))
        elif action == 'w':
            result = self.file_contents[filename] = (
                FileFake(self.file_contents, filename))
            return result
        else:
            raise Exception(f'Unexpected action {action}')

    def print_fake(self, data):
        self.printed.append(data)

    def setUp(self):
        self.addCleanup(mock.patch.stopall)
        self.exists_mock = mock.patch.object(os.path, 'exists').start()
        self.getcwd_mock = mock.patch.object(os, 'getcwd').start()
        self.getcwd_mock.return_value = '/tmp/rootdir'
        self.open_mock = (
            mock.patch.object(nom_build, 'open', self.open_fake).start())
        self.print_mock = (
            mock.patch.object(nom_build, 'print', self.print_fake).start())

        self.call_mock = mock.patch.object(subprocess, 'call').start()

        self.file_contents = {
            # Prefil with the actual file contents.
            PROPERTIES_FILENAME: nom_build.generate_gradle_properties()
        }
        self.printed = []

    @mock.patch.object(nom_build, 'PROPERTIES', FAKE_PROPERTIES)
    def test_property_generation(self):
        self.assertEqual(nom_build.generate_gradle_properties(),
                         FAKE_PROP_CONTENTS)

    @mock.patch.object(nom_build, 'PROPERTIES', FAKE_PROPERTIES)
    def test_property_file_write(self):
        nom_build.main(['nom_build', '--generate-gradle-properties'])
        self.assertEqual(self.file_contents[PROPERTIES_FILENAME],
                         FAKE_PROP_CONTENTS)

    def test_property_file_incorrect(self):
        self.file_contents[PROPERTIES_FILENAME] = 'bad contents'
        nom_build.main(['nom_build'])
        self.assertIn('', self.printed[0])

    def test_no_args(self):
        nom_build.main(['nom_build'])
        self.assertEqual(self.printed, [])
        self.call_mock.assert_called_with([GRADLEW])

    def test_property_calls(self):
        nom_build.main(['nom_build', '--testFilter=foo'])
        self.call_mock.assert_called_with([GRADLEW, '-P', 'testFilter=foo'])

    def test_gradle_flags(self):
        nom_build.main(['nom_build', '-d', '-b', 'foo'])
        self.call_mock.assert_called_with([GRADLEW, '--build-file', 'foo',
                                          '--debug'])

unittest.main()


