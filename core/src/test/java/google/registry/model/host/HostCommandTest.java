// Copyright 2017 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.model.host;

import google.registry.model.ResourceCommandTestCase;
import org.junit.jupiter.api.Test;

/** Test xml roundtripping of commands. */
class HostCommandTest extends ResourceCommandTestCase {

  @Test
  void testCreate() throws Exception {
    doXmlRoundtripTest("host_create.xml");
  }

  @Test
  void testDelete() throws Exception {
    doXmlRoundtripTest("host_delete.xml");
  }

  @Test
  void testUpdate() throws Exception {
    doXmlRoundtripTest("host_update.xml");
  }

  @Test
  void testInfo() throws Exception {
    doXmlRoundtripTest("host_info.xml");
  }

  @Test
  void testCheck() throws Exception {
    doXmlRoundtripTest("host_check.xml");
  }
}
