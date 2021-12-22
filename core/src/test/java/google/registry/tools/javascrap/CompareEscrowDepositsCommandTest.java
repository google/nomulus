// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

package google.registry.tools.javascrap;

import static com.google.common.truth.Truth.assertThat;

import google.registry.rde.RdeTestData;
import google.registry.tools.CommandTestCase;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link CompareEscrowDepositsCommand}. */
class CompareEscrowDepositsCommandTest extends CommandTestCase<CompareEscrowDepositsCommand> {

  @Test
  void testSuccess_sameContentDifferentOrder() throws Exception {
    String file1 = writeToNamedTmpFile("file1", RdeTestData.loadBytes("deposit_full.xml").read());
    String file2 =
        writeToNamedTmpFile("file2", RdeTestData.loadBytes("deposit_full_out_of_order.xml").read());
    runCommand("-i1=" + file1, "-i2=" + file2);
    assertThat(getStdoutAsString())
        .contains("The two deposits contain the same domains and registrars.");
  }

  @Test
  void testSuccess_differentContent() throws Exception {
    String file1 = writeToNamedTmpFile("file1", RdeTestData.loadBytes("deposit_full.xml").read());
    String file2 =
        writeToNamedTmpFile("file2", RdeTestData.loadBytes("deposit_full_different.xml").read());
    runCommand("-i1=" + file1, "-i2=" + file2);
    assertThat(getStdoutAsString())
        .isEqualTo(
            "domains only in deposit1:\n"
                + "example2.test\n"
                + "domains only in deposit2:\n"
                + "example3.test\n"
                + "registrars only in deposit2:\n"
                + "RegistrarY\n"
                + "The two deposits differ.\n");
  }
}
