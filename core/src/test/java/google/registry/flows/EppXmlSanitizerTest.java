// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.flows.EppXmlSanitizer.sanitizeEppXml;
import static google.registry.testing.TestDataHelper.loadBytes;
import static google.registry.xml.XmlTestUtils.assertXmlEqualsIgnoreHeader;
import static java.nio.charset.StandardCharsets.UTF_16LE;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableMap;
import google.registry.testing.EppLoader;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link EppXmlSanitizer}. */
class EppXmlSanitizerTest {

  private static final String UTF8_HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";

  @Test
  void testSanitize_noSensitiveData_noop() throws Exception {
    byte[] inputXmlBytes = loadBytes(getClass(), "host_create.xml").read();
    String expectedXml = UTF8_HEADER + new String(inputXmlBytes, UTF_8);
    assertXmlEqualsIgnoreHeader(expectedXml, sanitizeEppXml(inputXmlBytes));
  }

  @Test
  void testSanitize_loginPasswords_sanitized() throws Exception {
    String inputXml =
        new EppLoader(
                this,
                "login_update_password.xml",
                ImmutableMap.of("PW", "oldpass", "NEWPW", "newPw"))
            .getEppXml();
    String expectedXml =
        UTF8_HEADER
            + new EppLoader(
                    this,
                    "login_update_password.xml",
                    ImmutableMap.of("PW", "*******", "NEWPW", "*****"))
                .getEppXml();
    assertXmlEqualsIgnoreHeader(expectedXml, sanitizeEppXml(inputXml.getBytes(UTF_8)));
  }

  @Test
  void testSanitize_loginPasswordTagWrongCase_sanitized() throws Exception {
    String inputXml =
        new EppLoader(
                this, "login_wrong_case.xml", ImmutableMap.of("PW", "oldpass", "NEWPW", "newPw"))
            .getEppXml();
    String expectedXml =
        UTF8_HEADER
            + new EppLoader(
                    this,
                    "login_wrong_case.xml",
                    ImmutableMap.of("PW", "*******", "NEWPW", "*****"))
                .getEppXml();
    assertXmlEqualsIgnoreHeader(expectedXml, sanitizeEppXml(inputXml.getBytes(UTF_8)));
  }

  @Test
  void testSanitize_contactAuthInfo_sanitized() throws Exception {
    byte[] inputXmlBytes = loadBytes(getClass(), "contact_info.xml").read();
    String expectedXml =
        UTF8_HEADER
            + new EppLoader(this, "contact_info_sanitized.xml", ImmutableMap.of()).getEppXml();
    assertXmlEqualsIgnoreHeader(expectedXml, sanitizeEppXml(inputXmlBytes));
  }

  @Test
  void testSanitize_contactCreateResponseAuthInfo_sanitized() throws Exception {
    byte[] inputXmlBytes = loadBytes(getClass(), "contact_info_from_create_response.xml").read();
    String expectedXml =
        UTF8_HEADER
            + new EppLoader(
                    this, "contact_info_from_create_response_sanitized.xml", ImmutableMap.of())
                .getEppXml();
    assertXmlEqualsIgnoreHeader(expectedXml, sanitizeEppXml(inputXmlBytes));
  }

  @Test
  void testSanitize_emptyElement_transformedToLongForm() throws Exception {
    byte[] inputXmlBytes = "<pw/>".getBytes(UTF_8);
    assertXmlEqualsIgnoreHeader("<pw></pw>", sanitizeEppXml(inputXmlBytes));
  }

  @Test
  void testSanitize_invalidXML_throws() {
    byte[] inputXmlBytes = "<pw>".getBytes(UTF_8);
    assertThat(sanitizeEppXml(inputXmlBytes))
        .isEqualTo(Base64.getMimeEncoder().encodeToString(inputXmlBytes));
  }

  @Test
  void testSanitize_unicode_hasCorrectCharCount() throws Exception {
    byte[] inputXmlBytes = "<pw>\u007F\u4E43x</pw>".getBytes(UTF_8);
    assertXmlEqualsIgnoreHeader("<pw>C**</pw>", sanitizeEppXml(inputXmlBytes));
  }

  @Test
  void testSanitize_emptyString_encodedToBase64() {
    byte[] inputXmlBytes = "".getBytes(UTF_8);
    assertThat(sanitizeEppXml(inputXmlBytes)).isEqualTo("");
  }

  @Test
  void testSanitize_utf16_encodingPreserved() {
    // Test data should specify an endian-specific UTF-16 scheme for easy assertion. If 'UTF-16' is
    // used, the XMLEventReader in sanitizer may resolve it to an endian-specific one.
    String inputXml = "<?xml version=\"1.0\" encoding=\"UTF-16LE\" standalone=\"no\"?>"
	+ "<p>\u03bc</p>\n";
    String sanitizedXml = sanitizeEppXml(inputXml.getBytes(UTF_16LE));

    assertThat(sanitizedXml).isEqualTo(inputXml);
  }
}
