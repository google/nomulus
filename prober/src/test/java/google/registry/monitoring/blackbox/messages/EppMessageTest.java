package google.registry.monitoring.blackbox.messages;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.JUnitBackports.assertThrows;
import static java.nio.charset.StandardCharsets.UTF_8;

import google.registry.monitoring.blackbox.exceptions.FailureException;
import google.registry.monitoring.blackbox.servers.EppServer;
import google.registry.monitoring.blackbox.exceptions.EppClientException;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

@RunWith(JUnit4.class)
public class EppMessageTest {

  String xmlString = null;
  Document xmlDoc = null;
  Document greeting = null;
  DocumentBuilderFactory factory = null;
  DocumentBuilder builder = null;

  @Before
  public void setUp() throws Exception {
    xmlString =
        "<epp>" +
            "<textAndAttr myAttr1='1'>text1</textAndAttr>" +
            "<textNoAttr>text2</textNoAttr>" +
            "<attrNoText myAttr2='2'/>" +
            "<textAndAttrSplitRepeated>text3</textAndAttrSplitRepeated>" +
            "<textAndAttrSplitRepeated myAttr3='3'/>" +
            "</epp>";
    ByteArrayInputStream byteStream = new ByteArrayInputStream(xmlString.getBytes(UTF_8));
    factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    builder = factory.newDocumentBuilder();
    xmlDoc = builder.parse(byteStream);

    DocumentBuilder greetingBuilder = factory.newDocumentBuilder();
    greeting = greetingBuilder.parse(new ByteArrayInputStream(
        EppServer.getDefaultGreeting().getBytes(UTF_8)));
  }

  @Test
  public void xmlDocToStringSuccess() throws Exception {
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
    String expected = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
        "<doc><title>test</title><meta version=\"1.0\"/></doc>";
    String actual = EppMessage.xmlDocToString(xml);
    assertThat(actual).isEqualTo(expected);
  }

  @Test
  public void xmlDoctoByteArraySuccess() throws Exception {
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
    String expected = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
        "<doc><title>test</title><meta version=\"1.0\"/></doc>";
    byte[] actual = EppMessage.xmlDocToByteArray(xml);
    assertThat(actual).isEqualTo(expected.getBytes(UTF_8));
  }

  @Test
  public void eppValidateSuccess() throws Exception {
    EppMessage.eppValidate(greeting);
  }

  @Test
  public void eppValidateFail() {
    assertThrows(SAXException.class, () -> EppMessage.eppValidate(xmlDoc));
  }

  /**
   * These test a "success" without validating a document. This is to make it easier to test the
   * XPath part of the verify in isolation. See EppClientConnectionTest for tests that verify an
   * actual (greeting) respoonse.
   */
  @Test
  public void verifyResponseSuccess() throws Exception {
    ArrayList<String> list = new ArrayList<>();
    list.add("epp");
    list.add("//textAndAttr[@myAttr1='1'] | //textAndAttr[child::text()='text1']");
    list.add("//textAndAttr[child::text()='text1']");
    list.add("//textAndAttr/@myAttr1");
    list.add("//textAndAttrSplitRepeated[@myAttr3='3']");

    EppMessage.verifyEppResponse(xmlDoc, list, false);
  }

  @Test
  public void verifyEppResponseSuccess() throws Exception {
    ArrayList<String> list = new ArrayList<>();
    list.add("*");
    list.add("/eppns:epp");
    list.add("/eppns:epp/eppns:greeting");
    list.add("//eppns:greeting");
    list.add("//eppns:svID");

    EppMessage.verifyEppResponse(greeting, list, false);
  }

  @Test
  public void verifyResponseMissingTextFail() {
    ArrayList<String> list = new ArrayList<>();
    list.add("epp");
    list.add("//textAndAttr[child::text()='text2']");

    assertThrows(FailureException.class, () -> EppMessage.verifyEppResponse(xmlDoc, list, false));
  }

  @Test
  public void verifyResponseMissingAttrFail() {
    ArrayList<String> list = new ArrayList<>();
    list.add("epp");
    list.add("//textAndAttr/@myAttr2");

    assertThrows(FailureException.class, () -> EppMessage.verifyEppResponse(xmlDoc, list, false));
  }

  @Test
  public void verifyResponseSplitTextAttrFail() {
    ArrayList<String> list = new ArrayList<>();
    list.add("epp");
    list.add("//textAndAttrSplitRepeated[@myAttr3='3'][child::text()='text3']");

    assertThrows(FailureException.class, () -> EppMessage.verifyEppResponse(xmlDoc, list, false));
  }

  @Test
  public void getEppDocFromTemplateTest() throws Exception {
    Map<String, String> replaceMap = new HashMap<>();
    replaceMap.put("//eppns:expectedClTRID", "ABC-1234-CBA");
    replaceMap.put("//domainns:name", "foo");
    replaceMap.put("//domainns:pw", "bar");
    Document epp = EppMessage.getEppDocFromTemplate("create.xml", replaceMap);
    List<String> noList = Collections.emptyList();
    EppMessage.verifyEppResponse(epp, noList, true);
  }

  @Test
  public void getEppDocFromTemplateTestFail() {
    Map<String, String> replaceMap = new HashMap<>();
    replaceMap.put("//eppns:create", "ABC-1234-CBA");
    replaceMap.put("//domainns:name", "foo");
    replaceMap.put("//domainns:pw", "bar");
    assertThrows(
        EppClientException.class, () -> EppMessage.getEppDocFromTemplate("create.xml", replaceMap));
  }

  @Test
  public void checkValidDomainName() {
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
