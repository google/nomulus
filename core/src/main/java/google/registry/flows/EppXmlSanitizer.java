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

import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.CharMatcher;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventFactory;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLEventWriter;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Characters;
import javax.xml.stream.events.StartDocument;
import javax.xml.stream.events.XMLEvent;

/**
 * Sanitizes sensitive data in incoming/outgoing EPP XML messages.
 *
 * <p>Current implementation masks user credentials (text following &lt;pw&gt; and &lt;newPW&gt;
 * tags) as follows:
 *
 * <ul>
 *   <li>A control character (in ranges [0 - 1F] and [7F - 9F]) is replaced with 'C'.
 *   <li>Everything else is replaced with '*'.
 * </ul>
 *
 * <p>Invalid XML text is not sanitized, and returned as is.
 */
public class EppXmlSanitizer {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /**
   * Set of EPP XML tags whose data should be sanitized. Tags are converted to lower case for
   * case-insensitive match.
   *
   * <p>Although XML tag names are case sensitive, a tag in wrong case such as {@code newPw} is
   * likely a user error and may still wrap a real password.
   */
  private static final ImmutableSet<String> EPP_TAGS_IN_LOWER_CASE =
      Stream.of("pw", "newPW").map(String::toLowerCase).collect(ImmutableSet.toImmutableSet());

  // Masks by unicode char categories:
  // Ctrl chars: [0 - 1F] and [7F - 9F]
  private static final String CTRL_CHAR_MASK = "C";
  private static final String DEFAULT_MASK = "*";

  private static final XMLInputFactory XML_INPUT_FACTORY = createXmlInputFactory();
  private static final XMLOutputFactory XML_OUTPUT_FACTORY = XMLOutputFactory.newFactory();
  private static final XMLEventFactory XML_EVENT_FACTORY = XMLEventFactory.newFactory();

  /**
   * Returns sanitized EPP XML message. For malformed XML messages, base64-encoded raw bytes will be
   * returned.
   *
   * <p>The output always begins with version and encoding declarations no matter if the input
   * includes them. If encoding is not declared by input, UTF-8 will be used according to XML
   * standard.
   *
   * <p>Also, an empty element will be formatted as {@code <tag></tag>} instead of {@code <tag/>}.
   */
  public static String sanitizeEppXml(byte[] inputXmlBytes) {
    try {
      // Keep exactly one newline at end of sanitized string.
      return CharMatcher.whitespace().trimTrailingFrom(sanitizeAndEncode(inputXmlBytes)) + "\n";
    } catch (XMLStreamException | UnsupportedEncodingException e) {
      logger.atWarning().withCause(e).log("Failed to sanitize EPP XML message.");
      return Base64.getMimeEncoder().encodeToString(inputXmlBytes);
    }
  }

  private static String sanitizeAndEncode(byte[] inputXmlBytes)
      throws XMLStreamException, UnsupportedEncodingException {
    XMLEventReader xmlEventReader =
        XML_INPUT_FACTORY.createXMLEventReader(new ByteArrayInputStream(inputXmlBytes));

    if (!xmlEventReader.hasNext()) {
      return "";
    }

    XMLEvent firstEvent = xmlEventReader.nextEvent();
    checkState(firstEvent.isStartDocument(), "Missing StartDocument");
    // Get input encoding for use in XMLEventWriter creation, so that sanitized XML preserves the
    // encoding declaration. According to XML spec, UTF-8 is to be used unless input declares
    // otherwise. Epp officially allows UTF-8 and UTF-16.
    String inputEncoding =
        Optional.ofNullable(((StartDocument) firstEvent).getCharacterEncodingScheme())
            .orElse(StandardCharsets.UTF_8.name());

    ByteArrayOutputStream outputXmlBytes = new ByteArrayOutputStream();
    XMLEventWriter xmlEventWriter =
        XML_OUTPUT_FACTORY.createXMLEventWriter(outputXmlBytes, inputEncoding);
    xmlEventWriter.add(firstEvent);

    while (xmlEventReader.hasNext()) {
      XMLEvent xmlEvent = xmlEventReader.nextEvent();
      xmlEventWriter.add(xmlEvent);
      if (isStartEventForSensitiveData(xmlEvent)) {
        QName startEventName = xmlEvent.asStartElement().getName();
        while (xmlEventReader.hasNext()) {
          XMLEvent event = xmlEventReader.nextEvent();
          if (event.isCharacters()) {
            Characters characters = event.asCharacters();
            event = XML_EVENT_FACTORY.createCharacters(maskSensitiveData(characters.getData()));
          }
          xmlEventWriter.add(event);
          if (isMatchingEndEvent(event, startEventName)) {
            // The inner while-loop is guaranteed to break here for any valid XML.
            // If matching event is missing, xmlEventReader will throw XMLStreamException.
            break;
          }
        }
      }
    }
    xmlEventWriter.flush();
    return outputXmlBytes.toString(inputEncoding);
  }

  private static String maskSensitiveData(String original) {
    return original
        .codePoints()
        .mapToObj(codePoint -> Character.isISOControl(codePoint) ? CTRL_CHAR_MASK : DEFAULT_MASK)
        .collect(Collectors.joining());
  }

  private static boolean isStartEventForSensitiveData(XMLEvent xmlEvent) {
    return xmlEvent.isStartElement()
        && EPP_TAGS_IN_LOWER_CASE.contains(
            xmlEvent.asStartElement().getName().getLocalPart().toLowerCase(Locale.ROOT));
  }

  private static boolean isMatchingEndEvent(XMLEvent xmlEvent, QName startEventName) {
    return xmlEvent.isEndElement() && xmlEvent.asEndElement().getName().equals(startEventName);
  }

  private static XMLInputFactory createXmlInputFactory() {
    XMLInputFactory xmlInputFactory = XMLInputFactory.newFactory();
    // Coalesce adjacent data, so that all chars in a string will be grouped as one item.
    xmlInputFactory.setProperty(XMLInputFactory.IS_COALESCING, true);
    // Preserve Name Space information.
    xmlInputFactory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
    return xmlInputFactory;
  }
}
