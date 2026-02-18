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

package google.registry.model.eppcommon;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static google.registry.model.eppcommon.EppXmlTransformer.isFeeExtension;
import static google.registry.model.eppcommon.EppXmlTransformer.unmarshal;
import static google.registry.testing.TestDataHelper.loadBytes;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableSet;
import google.registry.model.domain.bulktoken.BulkTokenResponseExtension;
import google.registry.model.domain.launch.LaunchCheckResponseExtension;
import google.registry.model.domain.rgp.RgpInfoExtension;
import google.registry.model.domain.secdns.SecDnsInfoExtension;
import google.registry.model.eppinput.EppInput;
import google.registry.model.eppoutput.EppOutput;
import google.registry.model.eppoutput.EppResponse;
import google.registry.util.RegistryEnvironment;
import jakarta.xml.bind.annotation.XmlElementRef;
import jakarta.xml.bind.annotation.XmlElementRefs;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Tests for {@link EppXmlTransformer}. */
class EppXmlTransformerTest {

  // Non-fee extensions allowed in {@code Response.extensions}.
  private static final ImmutableSet<Class<?>> NON_FEE_EXTENSIONS =
      ImmutableSet.of(
          BulkTokenResponseExtension.class,
          LaunchCheckResponseExtension.class,
          RgpInfoExtension.class,
          SecDnsInfoExtension.class);

  @Test
  void isFeeExtension_eppResponse() throws Exception {
    var xmlRefs =
        EppResponse.class.getDeclaredField("extensions").getAnnotation(XmlElementRefs.class);
    Arrays.stream(xmlRefs.value())
        .map(XmlElementRef::type)
        .filter(type -> !NON_FEE_EXTENSIONS.contains(type))
        .forEach(
            type -> assertWithMessage(type.getSimpleName()).that(isFeeExtension(type)).isTrue());
  }

  @Test
  void testUnmarshalingEppInput() throws Exception {
    EppInput input = unmarshal(EppInput.class, loadBytes(getClass(), "contact_info.xml").read());
    assertThat(input.getCommandType()).isEqualTo("info");
  }

  @Test
  void testUnmarshalingWrongClassThrows() {
    assertThrows(
        ClassCastException.class,
        () -> unmarshal(EppOutput.class, loadBytes(getClass(), "contact_info.xml").read()));
  }

  @Test
  void testSchemas_inNonProduction_includesFee1Point0() {
    var currentEnv = RegistryEnvironment.get();
    try {
      RegistryEnvironment.SANDBOX.setup();
      assertThat(EppXmlTransformer.getSchemas()).contains("fee-std-v1.xsd");
    } finally {
      currentEnv.setup();
    }
  }

  @Test
  void testSchemas_inProduction_skipsFee1Point0() {
    var currentEnv = RegistryEnvironment.get();
    try {
      RegistryEnvironment.PRODUCTION.setup();
      assertThat(EppXmlTransformer.getSchemas()).doesNotContain("fee-std-v1.xsd");
    } finally {
      currentEnv.setup();
    }
  }
}
