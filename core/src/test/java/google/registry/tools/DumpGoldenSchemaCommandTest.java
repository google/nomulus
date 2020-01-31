// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

import java.io.File;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class DumpGoldenSchemaCommandTest extends CommandTestCase<DumpGoldenSchemaCommand> {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  public DumpGoldenSchemaCommandTest() {}

  @Test
  public void testSchemaGeneration() throws Exception {
    runCommand(
        "--output=" + tmp.getRoot() + File.separatorChar + "golden.sql", "--start_postgresql");
    assertThat(new File(tmp.getRoot(), "golden.sql").length()).isGreaterThan(1);
  }
}
