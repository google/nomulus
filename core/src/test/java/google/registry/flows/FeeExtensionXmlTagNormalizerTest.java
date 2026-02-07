// Copyright 2026 The Nomulus Authors. All Rights Reserved.
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
// limitations under the License.package google.registry.flows;

package google.registry.flows;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.flows.FeeExtensionXmlTagNormalizer.feeExtensionInUseRegex;
import static google.registry.flows.FeeExtensionXmlTagNormalizer.normalize;
import static google.registry.model.eppcommon.EppXmlTransformer.validateOutput;
import static google.registry.testing.TestDataHelper.loadFile;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class FeeExtensionXmlTagNormalizerTest {

  @Test
  void feeExtensionInUseRegex_correct() {
    assertThat(feeExtensionInUseRegex())
        .isEqualTo("\\b(fee):|\\b(fee11):|\\b(fee12):|\\b(fee_1_00):");
  }

  @Test
  void normalize_noFeeExtensions() throws Exception {
    String xml = loadFile(getClass(), "domain_create.xml");
    String normalized = normalize(xml);
    assertThat(normalized).isEqualTo(xml);
  }

  @ParameterizedTest(name = "normalize_withFeeExtension-{0}")
  @MethodSource("provideTestCombinations")
  @SuppressWarnings("unused") // Parameter 'name' is part of test case name
  void normalize_withFeeExtension(String name, String inputXmlFilename, String expectedXmlFilename)
      throws Exception {
    String original = loadFile(getClass(), inputXmlFilename);
    String normalized = normalize(original);
    String expected = loadFile(getClass(), expectedXmlFilename);
    // Verify that expected xml is syntatically correct.
    validateOutput(expected);

    assertThat(normalized).isEqualTo(expected);
  }

  @SuppressWarnings("unused")
  static Stream<Arguments> provideTestCombinations() {
    return Stream.of(
        Arguments.of(
            "v06",
            "domain_check_fee_response_raw_v06.xml",
            "domain_check_fee_response_normalized_v06.xml"),
        Arguments.of(
            "v11",
            "domain_check_fee_response_raw_v11.xml",
            "domain_check_fee_response_normalized_v11.xml"),
        Arguments.of(
            "v12",
            "domain_check_fee_response_raw_v12.xml",
            "domain_check_fee_response_normalized_v12.xml"),
        Arguments.of(
            "stdv1",
            "domain_check_fee_response_raw_stdv1.xml",
            "domain_check_fee_response_normalized_stdv1.xml"));
  }
}
