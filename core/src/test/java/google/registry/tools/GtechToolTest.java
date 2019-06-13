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

package google.registry.tools;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.google.common.collect.Sets;
import google.registry.testing.SystemPropertyRule;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link GtechTool}. */
@RunWith(JUnit4.class)
public class GtechToolTest {

  @Rule public final SystemPropertyRule systemPropertyRule = new SystemPropertyRule();

  @Before
  public void init() {
    RegistryToolEnvironment.UNITTEST.setup(systemPropertyRule);
  }

  @Test
  public void test_commandMap_namesAreInAlphabeticalOrder() {
    assertThat(GtechTool.COMMAND_MAP.keySet()).isInStrictOrder();
  }

  @Test
  public void test_commandSet_namesAreSubsetOfRegistryToolCommands() {
    assertWithMessage("commands in GtechTool.COMMAND_SET but not in RegistryTool.COMMAND_MAP")
        .that(Sets.difference(GtechTool.COMMAND_SET, RegistryTool.COMMAND_MAP.keySet()))
        .isEmpty();
  }
}
