// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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
// limitations under the License

package google.registry.monitoring.blackbox.message;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertThrows;

import google.registry.monitoring.blackbox.exception.EppClientException;
import google.registry.monitoring.blackbox.exception.FailureException;
import google.registry.monitoring.blackbox.util.EppUtils;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

/** Unit tests for {@link EppMessage}. */
class EppMessageTest {

  private Document xmlDoc = null;
  private Document greeting = null;
  private DocumentBuilder builder = null;

  @BeforeEach
  void beforeEach() throws Exception {
    String xmlString =
        "<epp>"
            + "<textAndAttr myAttr1='1'>text1</textAndAttr>"
            + "<textNoAttr>text2</textNoAttr>"
            + "<attrNoText myAttr2='2'/>"
            + "<textAndAttrSplitRepeated>text3</textAndAttrSplitRepeated>"
            + "<textAndAttrSplitRepeated myAttr3='3'/>"
            + "</epp>";
    ByteArrayInputStream byteStream = new ByteArrayInputStream(xmlString.getBytes(UTF_8));
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    builder = factory.newDocumentBuilder();
    xmlDoc = builder.parse(byteStream);

    greeting = EppUtils.getGreeting();
  }

  @Test
  void xmlDocToStringSuccess() throws Exception {
    Document xml = builder.newDocument();
    Element doc = xml.createElement("doc");
    Element title = xml.createElement("title");
    title.setTextContent("test");
    Element meta = xml.createElement("meta");
    meta.setAttribute("version", "1.0");
    doc.appendChild(title);
    doc.appendChild(meta);
    xml.appendChild(doc);

    // note that setting the version just ensures this will either be the same in the result,
    // or the result won't support the version and this will throw an exception.
    xml.setXmlVersion("1.0");
    // setting stand alone to true removes this from the processing instructions
    xml.setXmlStandalone(true);
    String expected =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<doc><title>test</title><meta version=\"1.0\"/></doc>";
    String actual = EppMessage.xmlDocToString(xml);
    assertThat(actual).isEqualTo(expected);
  }

  @Test
  void xmlDoctoByteArraySuccess() throws Exception {
    Document xml = builder.newDocument();
    Element doc = xml.createElement("doc");
    Element title = xml.createElement("title");
    title.setTextContent("test");
    Element meta = xml.createElement("meta");
    meta.setAttribute("version", "1.0");
    doc.appendChild(title);
    doc.appendChild(meta);
    xml.appendChild(doc);

    // note that setting the version just ensures this will either be the same in the result,
    // or the result won't support the version and this will throw an exception.
    xml.setXmlVersion("1.0");
    // setting stand alone to true removes this from the processing instructions
    xml.setXmlStandalone(true);
    String expected =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<doc><title>test</title><meta version=\"1.0\"/></doc>";
    byte[] actual = EppMessage.xmlDocToByteArray(xml);
    assertThat(actual).isEqualTo(expected.getBytes(UTF_8));
  }

  @Test
  void eppValidateSuccess() throws Exception {
    EppMessage.eppValidate(greeting);
  }

  @Test
  void eppValidateFail() {
    assertThrows(SAXException.class, () -> EppMessage.eppValidate(xmlDoc));
  }

  /**
   * These test a "success" without validating a document. This is to make it easier to test the
   * XPath part of the verify in isolation. See EppClientConnectionTest for tests that verify an
   * actual (greeting) respoonse.
   */
  @Test
  void verifyResponseSuccess() throws Exception {
    ArrayList<String> list = new ArrayList<>();
    list.add("epp");
    list.add("//textAndAttr[@myAttr1='1'] | //textAndAttr[child::text()='text1']");
    list.add("//textAndAttr[child::text()='text1']");
    list.add("//textAndAttr/@myAttr1");
    list.add("//textAndAttrSplitRepeated[@myAttr3='3']");

    EppMessage.verifyEppResponse(xmlDoc, list, false);
  }

  @Test
  void verifyEppResponseSuccess() throws Exception {
    ArrayList<String> list = new ArrayList<>();
    list.add("*");
    list.add("/eppns:epp");
    list.add("/eppns:epp/eppns:greeting");
    list.add("//eppns:greeting");
    list.add("//eppns:svID");

    EppMessage.verifyEppResponse(greeting, list, false);
  }

  @Test
  void verifyResponseMissingTextFail() {
    ArrayList<String> list = new ArrayList<>();
    list.add("epp");
    list.add("//textAndAttr[child::text()='text2']");

    assertThrows(FailureException.class, () -> EppMessage.verifyEppResponse(xmlDoc, list, false));
  }

  @Test
  void verifyResponseMissingAttrFail() {
    ArrayList<String> list = new ArrayList<>();
    list.add("epp");
    list.add("//textAndAttr/@myAttr2");

    assertThrows(FailureException.class, () -> EppMessage.verifyEppResponse(xmlDoc, list, false));
  }

  @Test
  void verifyResponseSplitTextAttrFail() {
    ArrayList<String> list = new ArrayList<>();
    list.add("epp");
    list.add("//textAndAttrSplitRepeated[@myAttr3='3'][child::text()='text3']");

    assertThrows(FailureException.class, () -> EppMessage.verifyEppResponse(xmlDoc, list, false));
  }

  @Test
  void getEppDocFromTemplateTest() throws Exception {
    Map<String, String> replaceMap = new HashMap<>();
    replaceMap.put("//eppns:expectedClTRID", "ABC-1234-CBA");
    replaceMap.put("//domainns:name", "foo");
    replaceMap.put("//domainns:pw", "bar");
    Document epp = EppMessage.getEppDocFromTemplate("create.xml", replaceMap);
    List<String> noList = Collections.emptyList();
    EppMessage.verifyEppResponse(epp, noList, true);
  }

  @Test
  void getEppDocFromTemplateTestFail() {
    Map<String, String> replaceMap = new HashMap<>();
    replaceMap.put("//eppns:create", "ABC-1234-CBA");
    replaceMap.put("//domainns:name", "foo");
    replaceMap.put("//domainns:pw", "bar");
    assertThrows(
        EppClientException.class, () -> EppMessage.getEppDocFromTemplate("create.xml", replaceMap));
  }

  @Test
  void checkValidDomainName() {
    String domainName = "good.tld";
    assertThat(domainName.matches(EppMessage.VALID_SLD_LABEL_REGEX)).isTrue();
    domainName = "good.tld.";
    assertThat(domainName.matches(EppMessage.VALID_SLD_LABEL_REGEX)).isTrue();
    domainName = "g-o-o-d.tld.";
    assertThat(domainName.matches(EppMessage.VALID_SLD_LABEL_REGEX)).isTrue();
    domainName = "good.cc.tld";
    assertThat(domainName.matches(EppMessage.VALID_SLD_LABEL_REGEX)).isTrue();
    domainName = "good.cc.tld.";
    assertThat(domainName.matches(EppMessage.VALID_SLD_LABEL_REGEX)).isTrue();
    domainName = "too-short";
    assertThat(domainName.matches(EppMessage.VALID_SLD_LABEL_REGEX)).isFalse();
    domainName = "too-short.";
    assertThat(domainName.matches(EppMessage.VALID_SLD_LABEL_REGEX)).isFalse();
    // TODO(rgr): sync up how many dots is actually too many
    domainName = "too.many.dots.tld.";
    assertThat(domainName.matches(EppMessage.VALID_SLD_LABEL_REGEX)).isFalse();
    domainName = "too.many.dots.tld";
    assertThat(domainName.matches(EppMessage.VALID_SLD_LABEL_REGEX)).isFalse();
  }
}
