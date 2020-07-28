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

package google.registry.model.translators;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import google.registry.model.eppcommon.EppXmlTransformer;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppinput.EppInput;
import google.registry.model.eppinput.EppInput.ResourceCommandWrapper;
import google.registry.model.eppoutput.EppOutput;
import google.registry.model.eppoutput.EppResponse;
import google.registry.model.host.HostCommand;
import google.registry.model.host.HostInfoData;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.EppLoader;
import google.registry.xml.ValidationMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

public class StatusValueAdapterTest {

  // Needed to create HostResources.
  @RegisterExtension
  public AppEngineExtension appEngine =
      new AppEngineExtension.Builder().withDatastoreAndCloudSql().build();

  @Test
  void testMarshalling() throws Exception {
    // Mangle the status value through marshalling by stuffing it in a host info response and then
    // ripping it out of the marshalled xml. Use lenient marshalling so we can omit other fields.
    String marshalled = new String(
        EppXmlTransformer.marshal(
            EppOutput.create(new EppResponse.Builder()
                .setResData(HostInfoData.newBuilder()
                    .setCreationClientId("")
                    .setCreationTime(START_OF_TIME)
                    .setCurrentSponsorClientId("")
                    .setFullyQualifiedHostName("")
                    .setInetAddresses(ImmutableSet.of())
                    .setRepoId("")
                    .setStatusValues(ImmutableSet.of(StatusValue.CLIENT_UPDATE_PROHIBITED))
                    .build())
                .build()),
            ValidationMode.LENIENT),
        UTF_8);
    assertThat(marshalled).contains("<host:status s=\"clientUpdateProhibited\"/>");
  }

  private StatusValue unmarshal(String statusValueXml) throws Exception {
    // Mangle the status value through unmarshalling by stuffing it in a simple host command and
    // then ripping it out of the unmarshalled EPP object.
    EppInput eppInput =
        new EppLoader(this, "host_update.xml", ImmutableMap.of("STATUS", statusValueXml)).getEpp();
    ResourceCommandWrapper wrapper =
        (ResourceCommandWrapper) eppInput.getCommandWrapper().getCommand();
    HostCommand.Update update = (HostCommand.Update) wrapper.getResourceCommand();
    return update.getInnerAdd().getStatusValues().asList().get(0);
  }

  @Test
  void testNoOptionalFields_unmarshallsWithoutException() throws Exception {
    assertThat(unmarshal("<host:status s=\"clientUpdateProhibited\"/>"))
        .isEqualTo(StatusValue.CLIENT_UPDATE_PROHIBITED);
  }

  @Test
  void testHasLang_unmarshallsWithoutException() throws Exception {
    assertThat(unmarshal("<host:status s=\"clientUpdateProhibited\" lang=\"fr\"/>"))
        .isEqualTo(StatusValue.CLIENT_UPDATE_PROHIBITED);
  }

  @Test
  void testHasMessage_unmarshallsWithoutException() throws Exception {
    assertThat(unmarshal("<host:status s=\"clientUpdateProhibited\">my message</host:status>"))
        .isEqualTo(StatusValue.CLIENT_UPDATE_PROHIBITED);
  }
}
