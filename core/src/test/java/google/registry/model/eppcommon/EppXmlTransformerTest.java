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
import static google.registry.model.eppcommon.EppXmlTransformer.marshal;
import static google.registry.model.eppcommon.EppXmlTransformer.unmarshal;
import static google.registry.testing.TestDataHelper.loadBytes;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import google.registry.model.domain.Period;
import google.registry.model.domain.fee.FeeCheckResponseExtension;
import google.registry.model.domain.fee.FeeCheckResponseExtensionItem;
import google.registry.model.domain.fee.FeeQueryCommandExtensionItem;
import google.registry.model.domain.fee06.FeeCheckResponseExtensionItemV06;
import google.registry.model.domain.fee06.FeeCheckResponseExtensionV06;
import google.registry.model.domain.fee11.FeeCheckResponseExtensionItemV11;
import google.registry.model.domain.fee11.FeeCheckResponseExtensionV11;
import google.registry.model.domain.fee12.FeeCheckResponseExtensionItemV12;
import google.registry.model.domain.fee12.FeeCheckResponseExtensionV12;
import google.registry.model.domain.feestdv1.FeeCheckResponseExtensionItemStdV1;
import google.registry.model.domain.feestdv1.FeeCheckResponseExtensionStdV1;
import google.registry.model.eppinput.EppInput;
import google.registry.model.eppoutput.EppOutput;
import google.registry.model.eppoutput.EppResponse;
import google.registry.model.eppoutput.Result;
import google.registry.xml.ValidationMode;
import java.util.stream.Stream;
import org.joda.money.CurrencyUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Tests for {@link EppXmlTransformer}. */
class EppXmlTransformerTest {

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

  @ParameterizedTest
  @MethodSource("provideFeeTestParams")
  void testFeeXmlNamespaceTagLiteral(
      String tag, FeeCheckResponseExtension feeCheckResponseExtension) throws Exception {
    var eppOutput =
        EppOutput.create(
            new EppResponse.Builder()
                .setTrid(Trid.create("clTrid", "svrTrid"))
                .setResultFromCode(Result.Code.SUCCESS)
                .setOnlyExtension(feeCheckResponseExtension)
                .build());
    var xmlOutput = new String(marshal(eppOutput, ValidationMode.STRICT), UTF_8);
    String expected = String.format("<%s:chkData>", tag);
    assertThat(xmlOutput).contains(expected);
  }

  @SuppressWarnings("unused")
  private static Stream<Arguments> provideFeeTestParams() {
    return Stream.of(
        Arguments.of(
            "fee06",
            FeeCheckResponseExtensionV06.create(
                ImmutableList.of(
                    createCannedExtensionItem(
                        FeeCheckResponseExtensionItemV06.class,
                        new FeeCheckResponseExtensionItemV06.Builder()))),
            Arguments.of(
                "fee11",
                FeeCheckResponseExtensionV11.create(
                    ImmutableList.of(
                        createCannedExtensionItem(
                            FeeCheckResponseExtensionItemV11.class,
                            new FeeCheckResponseExtensionItemV11.Builder()))))),
        Arguments.of(
            "fee12",
            FeeCheckResponseExtensionV12.create(
                CurrencyUnit.USD,
                ImmutableList.of(
                    createCannedExtensionItem(
                        FeeCheckResponseExtensionItemV12.class,
                        new FeeCheckResponseExtensionItemV12.Builder())))),
        Arguments.of(
            "fee",
            FeeCheckResponseExtensionStdV1.create(
                CurrencyUnit.USD,
                ImmutableList.of(
                    createCannedExtensionItem(
                        FeeCheckResponseExtensionItemStdV1.class,
                        new FeeCheckResponseExtensionItemStdV1.Builder())))));
  }

  private static <T extends FeeCheckResponseExtensionItem> T createCannedExtensionItem(
      Class<T> itemType, FeeCheckResponseExtensionItem.Builder<T> builder) {
    return itemType.cast(
        builder
            .setCommand(FeeQueryCommandExtensionItem.CommandName.CREATE, null, null)
            .setDomainNameIfSupported("example.tld")
            .setCurrencyIfSupported(CurrencyUnit.USD)
            .setPeriod(Period.create(1, Period.Unit.YEARS))
            .build());
  }
}
