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
// limitations under the License.

package google.registry.monitoring.blackbox.util;

import static java.nio.charset.StandardCharsets.UTF_8;

import google.registry.monitoring.blackbox.exceptions.EppClientException;
import google.registry.monitoring.blackbox.messages.EppMessage;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.xml.sax.SAXException;

public class EppUtils {

  private static final int HEADER_LENGTH = 4;
  private static DocumentBuilderFactory factory;
  private static DocumentBuilder builder;

  static {
    factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);

    try {
      builder = factory.newDocumentBuilder();
    } catch (ParserConfigurationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /** Return a simple default greeting as a String. */
  public static String getDefaultGreeting() {
    String greeting =
        "<?xml version='1.0' encoding='UTF-8' standalone='no'?>"
            + "<epp xmlns='urn:ietf:params:xml:ns:epp-1.0'>"
            + "<greeting>"
            + "<svID>Test EPP server</svID>"
            + "<svDate>2000-06-08T22:00:00.0Z</svDate>"
            + "<svcMenu>"
            + "<version>1.0</version>"
            + "<lang>en</lang>"
            + "<lang>fr</lang>"
            + "<objURI>urn:ietf:params:xml:ns:obj1</objURI>"
            + "<objURI>urn:ietf:params:xml:ns:obj2</objURI>"
            + "<objURI>urn:ietf:params:xml:ns:obj3</objURI>"
            + "<svcExtension>"
            + "<extURI>http://custom/obj1ext-1.0</extURI>"
            + "</svcExtension>"
            + "</svcMenu>"
            + "<dcp>"
            + "<access><all/></access>"
            + "<statement>"
            + "<purpose><admin/><prov/></purpose>"
            + "<recipient><ours/><public/></recipient>"
            + "<retention><stated/></retention>"
            + "</statement>"
            + "</dcp>"
            + "</greeting>"
            + "</epp>";
    return greeting;
  }

  /**
   * Return a simple EPP success as a string.
   *
   * @param resCode the result code for the success
   * @param resMsg the message to include in the success
   * @param clTRID the client transaction ID
   * @param svTRID the server transaction ID
   * @return the EPP success message as a string
   */
  public static String getBasicResponse(int resCode, String resMsg, String clTRID, String svTRID) {
    String response =
        "<?xml version='1.0' encoding='UTF-8'?><epp"
            + " xmlns='urn:ietf:params:xml:ns:epp-1.0'>\n"
            + "\t<response>\n"
            + "\t\t<result code='%d'>\n"
            + "\t\t\t<msg>%s</msg>\n"
            + "\t\t</result>\n"
            + "\t\t<trID>\n"
            + "\t\t\t<clTRID>%s</clTRID>\n"
            + "\t\t\t<svTRID>%s</svTRID>\n"
            + "\t\t</trID>\n"
            + "\t</response>\n"
            + "</epp>";
    return String.format(response, resCode, resMsg, clTRID, svTRID);
  }

  /**
   * Return a domain CheckSuccess success as a string. These always have a result code of 1000
   * unless something unusual occurred. The success or failure is evaulated against expectation of
   * availability rather than result code in this case.
   *
   * @param availCode the availability code to use
   * @param domain the domain the check success is for
   * @param clTRID the client transaction ID
   * @param svTRID the server transaction ID
   * @return the EPP success message as a string
   * @throws IllegalArgumentException if availability code is anything other than 0 or 1
   */
  public static String getCheckDomainResponse(
      boolean availCode, String domain, String clTRID, String svTRID) {
    String response =
        "<?xml version='1.0' encoding='UTF-8'?><epp xmlns='urn:ietf:params:xml:ns:epp-1.0'>\n"
            + "\t<response>\n"
            + "\t\t<result code='1000'>\n"
            + "\t\t\t<msg>Generic Message</msg>\n"
            + "\t\t</result>\n"
            + "\t\t<resData>\n"
            + "\t\t\t<domain:chkData xmlns:domain='urn:ietf:params:xml:ns:domain-1.0'>\n"
            + "\t\t\t\t<domain:cd>\n"
            + "\t\t\t\t\t<domain:name avail='%s'>%s</domain:name>\n"
            + "\t\t\t\t</domain:cd>\n"
            + "\t\t\t</domain:chkData>\n"
            + "\t\t</resData>\n"
            + "\t\t<trID>\n"
            + "\t\t\t<clTRID>%s</clTRID>\n"
            + "\t\t\t<svTRID>%s</svTRID>\n"
            + "\t\t</trID>\n"
            + "\t</response>\n"
            + "</epp>";

    return String.format(response, availCode, domain, clTRID, svTRID);
  }

  public static ByteBuf stringToByteBuf(String message)
      throws IOException, SAXException, EppClientException {
    byte[] bytestream =
        EppMessage.xmlDocToByteArray(
            builder.parse(new ByteArrayInputStream(message.getBytes(UTF_8))));

    int capacity = HEADER_LENGTH + bytestream.length;

    ByteBuf buf = Unpooled.buffer(capacity);

    buf.writeInt(capacity);
    buf.writeBytes(bytestream);

    return buf;
  }
}
