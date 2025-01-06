// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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
import static org.junit.jupiter.api.Assertions.assertThrows;

import google.registry.rde.RdeTestData;
import google.registry.xml.XmlException;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ValidateEscrowDepositCommand}. */
class ValidateEscrowDepositCommandTest extends CommandTestCase<ValidateEscrowDepositCommand> {

  @Test
  void testRun_plainXml() throws Exception {
    String file = writeToTmpFile(RdeTestData.loadBytes("deposit_full.xml").read());
    runCommand("--input=" + file);
    assertThat(getStdoutAsString())
        .isEqualTo(
            """
        ID: 20101017001
        Previous ID: 20101010001
        Type: FULL
        Watermark: 2010-10-17T00:00:00.000Z
        RDE Version: 1.0

        RDE Object URIs:
          - urn:ietf:params:xml:ns:rdeContact-1.0
          - urn:ietf:params:xml:ns:rdeDomain-1.0
          - urn:ietf:params:xml:ns:rdeEppParams-1.0
          - urn:ietf:params:xml:ns:rdeHeader-1.0
          - urn:ietf:params:xml:ns:rdeHost-1.0
          - urn:ietf:params:xml:ns:rdeIDN-1.0
          - urn:ietf:params:xml:ns:rdeNNDN-1.0
          - urn:ietf:params:xml:ns:rdeRegistrar-1.0

        Contents:
          - XjcRdeContact: 1 entry
          - XjcRdeDomain: 2 entries
          - XjcRdeEppParams: 1 entry
          - XjcRdeHeader: 1 entry
          - XjcRdeHost: 2 entries
          - XjcRdeIdn: 1 entry
          - XjcRdeNndn: 1 entry
          - XjcRdePolicy: 1 entry
          - XjcRdeRegistrar: 1 entry

        RDE deposit is XML schema valid
        """);
  }

  @Test
  void testRun_plainXml_badReference() throws Exception {
    String file = writeToTmpFile(RdeTestData.loadBytes("deposit_full_badref.xml").read());
    runCommand("--input=" + file);
    assertThat(getStdoutAsString())
        .isEqualTo(
            """
        ID: 20101017001
        Previous ID: 20101010001
        Type: FULL
        Watermark: 2010-10-17T00:00:00.000Z
        RDE Version: 1.0

        RDE Object URIs:
          - urn:ietf:params:xml:ns:rdeContact-1.0
          - urn:ietf:params:xml:ns:rdeDomain-1.0
          - urn:ietf:params:xml:ns:rdeEppParams-1.0
          - urn:ietf:params:xml:ns:rdeHeader-1.0
          - urn:ietf:params:xml:ns:rdeHost-1.0
          - urn:ietf:params:xml:ns:rdeIDN-1.0
          - urn:ietf:params:xml:ns:rdeNNDN-1.0
          - urn:ietf:params:xml:ns:rdeRegistrar-1.0

        Contents:
          - XjcRdeContact: 1 entry
          - XjcRdeDomain: 2 entries
          - XjcRdeEppParams: 1 entry
          - XjcRdeHeader: 1 entry
          - XjcRdeHost: 2 entries
          - XjcRdeIdn: 1 entry
          - XjcRdeNndn: 1 entry
          - XjcRdePolicy: 1 entry
          - XjcRdeRegistrar: 1 entry

        Bad host refs: ns1.LAFFO.com
        RDE deposit is XML schema valid but has bad references
        """);
  }

  @Test
  void testRun_badXml() throws Exception {
    String file = writeToTmpFile(RdeTestData.loadFile("deposit_full.xml").substring(0, 2000));
    XmlException thrown = assertThrows(XmlException.class, () -> runCommand("--input=" + file));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "Syntax error at line 46, column 38: "
                + "XML document structures must start and end within the same entity.");
  }
}
