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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Streams.stream;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.CharMatcher;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.flogger.FluentLogger;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.util.Base64;
import java.util.Iterator;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventFactory;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLEventWriter;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.EndElement;
import javax.xml.stream.events.Namespace;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;

/**
 * Normalizes Fee extension namespace tags in EPP XML response messages.
 *
 * <p>Nomulus currently supports multiple versions of the Fee extension. With the current XML
 * tooling, the namespace of every version is included in each EPP response, and as a result must
 * use a unique XML tag. E.g., fee for extension v0.6, and fee12 for extension v0.12.
 *
 * <p>Some registrars are not XML namespace-aware and rely on the XML tags being specific literals.
 * This makes it difficult to perform seamless rollout of new versions: if Nomulus reassigns a tag
 * literal to a different version, it effectively forces all these registrars to upgrade.
 *
 * <p>This class can be used to normalize the namespace tag in EPP responses. Since every response
 * message may use at most one version of the Fee extension, we can remove declared but unused
 * versions from the message, thus freeing up the canonical tag ('fee') for the active version.
 */
public class FeeExtensionXmlTagNormalizer {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  // So far we only have Fee extensions to process
  private static final String CANONICAL_FEE_TAG = "fee";
  private static final ImmutableSet FEE_EXTENSIONS =
      ImmutableSet.of(
          "urn:ietf:params:xml:ns:fee-0.6",
          "urn:ietf:params:xml:ns:fee-0.11",
          "urn:ietf:params:xml:ns:fee-0.12",
          "urn:ietf:params:xml:ns:epp:fee-1.0");

  private static final XMLInputFactory XML_INPUT_FACTORY = createXmlInputFactory();
  private static final XMLOutputFactory XML_OUTPUT_FACTORY = XMLOutputFactory.newFactory();
  private static final XMLEventFactory XML_EVENT_FACTORY = XMLEventFactory.newFactory();

  /**
   * Returns an EPP XML message with normalized Fee extension tags.
   *
   * <p>The output always begins with version and encoding declarations no matter if the input
   * includes them. If encoding is not declared by input, UTF-8 will be used according to XML
   * standard.
   */
  public static String normalizeFeeExtensionTag(byte[] inputXmlBytes) {
    try {
      // Keep exactly one newline at end of sanitized string.
      return CharMatcher.whitespace().trimTrailingFrom(normalize(inputXmlBytes)) + "\n";
    } catch (XMLStreamException | UnsupportedEncodingException e) {
      logger.atWarning().withCause(e).log("Failed to sanitize EPP XML message.");
      return Base64.getMimeEncoder().encodeToString(inputXmlBytes);
    }
  }

  private static String normalize(byte[] inputXmlBytes)
      throws XMLStreamException, UnsupportedEncodingException {
    ParseResults parseResults = findFeeExtensionInUse(inputXmlBytes);

    if (parseResults.feeExtensionInUse.isEmpty()) {
      // Fee extension not present. Return as is.
      return new String(inputXmlBytes, UTF_8);
    }

    ByteArrayOutputStream outputXmlBytes = new ByteArrayOutputStream();
    XMLEventWriter xmlEventWriter =
        XML_OUTPUT_FACTORY.createXMLEventWriter(outputXmlBytes, UTF_8.name());

    for (XMLEvent event : parseResults.xmlEvents()) {
      xmlEventWriter.add(normalizeXmlEvent(event, parseResults.feeExtensionInUse));
      // Most standard Java StAX implementations omits the content between the XML header and the
      // root element. Add a "\n" between them to improve readability.
      if (event.isStartDocument()) {
        xmlEventWriter.add(XML_EVENT_FACTORY.createCharacters("\n"));
      }
    }

    xmlEventWriter.flush();
    return outputXmlBytes.toString(UTF_8);
  }

  /**
   * Holds intermediate results during XML processing.
   *
   * @param feeExtensionInUse The fee extension namespace URI in the EPP response, if found
   * @param xmlEvents The parsed XML objects found in a pass, saved for reuse
   */
  private record ParseResults(
      Optional<String> feeExtensionInUse, ImmutableList<XMLEvent> xmlEvents) {}

  /**
   * Makes one pass of the input XML and returns parsed data the Fee extension in use.
   *
   * <p>Each XML message should use at most one Fee extension. This method returns it if found. The
   * {@link XMLEvent} objects returned by the parser are also saved for reuse.
   *
   * @throws IllegalArgumentException if more than one Fee extension version is found
   */
  private static ParseResults findFeeExtensionInUse(byte[] inputXmlBytes)
      throws XMLStreamException {
    XMLEventReader xmlEventReader =
        XML_INPUT_FACTORY.createXMLEventReader(new ByteArrayInputStream(inputXmlBytes));

    ImmutableList.Builder<XMLEvent> eventBuffer = new ImmutableList.Builder<>();
    Optional<String> feeExtensionInUse = Optional.empty();

    // Make one pass through the message to identify the Fee extension in use.
    while (xmlEventReader.hasNext()) {
      XMLEvent xmlEvent = xmlEventReader.nextEvent();
      Optional<String> eventFeeExtensionUri = getXmlEventFeeExtensionUri(xmlEvent);

      if (feeExtensionInUse.isEmpty()) {
        feeExtensionInUse = eventFeeExtensionUri;
      } else if (eventFeeExtensionUri.isPresent()
          && !feeExtensionInUse.equals(eventFeeExtensionUri)) {
        throw new IllegalArgumentException(
            String.format(
                "Expecting one Fee extension, found two: %s -- %s",
                feeExtensionInUse, eventFeeExtensionUri.get()));
      }
      eventBuffer.add(xmlEvent);
    }
    return new ParseResults(feeExtensionInUse, eventBuffer.build());
  }

  private static XMLEvent normalizeXmlEvent(XMLEvent xmlEvent, Optional<String> feeExtensionInUse) {
    if (xmlEvent.isStartElement()) {
      return normalizeStartElement(xmlEvent.asStartElement(), feeExtensionInUse);
    } else if (xmlEvent.isEndElement()) {
      return normalizeEndElement(xmlEvent.asEndElement(), feeExtensionInUse);
    } else {
      return xmlEvent;
    }
  }

  private static Optional<String> getXmlEventFeeExtensionUri(XMLEvent xmlEvent) {
    if (xmlEvent.isStartElement()) {
      return getFeeExtensionUri(xmlEvent.asStartElement());
    }
    if (xmlEvent.isEndElement()) {
      String extension = xmlEvent.asEndElement().getName().getNamespaceURI();
      if (FEE_EXTENSIONS.contains(extension)) {
        return Optional.of(extension);
      }
    }
    return Optional.empty();
  }

  private static Optional<String> getFeeExtensionUri(StartElement startElement) {
    Set<String> attrs =
        stream(startElement.asStartElement().getAttributes())
            .map(Attribute::getName)
            .map(FeeExtensionXmlTagNormalizer::getFeeExtensionUri)
            .flatMap(Optional::stream)
            .collect(Collectors.toSet());
    var qName = startElement.asStartElement().getName();
    if (FEE_EXTENSIONS.contains(qName.getNamespaceURI())) {
      attrs.add(qName.getNamespaceURI());
    }
    if (attrs.size() > 1) {
      throw new IllegalArgumentException("Multiple Fee extension in use: " + attrs);
    }
    if (attrs.isEmpty()) {
      return Optional.empty();
    }
    // attrs.size == 1
    return Optional.of(Iterables.getOnlyElement(attrs));
  }

  private static Optional<String> getFeeExtensionUri(QName name) {
    String extensionUri = name.getNamespaceURI();
    if (FEE_EXTENSIONS.contains(extensionUri)) {
      return Optional.of(extensionUri);
    }
    return Optional.empty();
  }

  private static XMLEvent normalizeStartElement(
      StartElement startElement, Optional<String> feeExtensionInUse) {
    QName name = normalizeName(startElement.getName());
    ImmutableList<Namespace> namespaces =
        normalizeNamespaces(startElement.getNamespaces(), feeExtensionInUse);
    ImmutableList<Attribute> attributes = normalizeAttributes(startElement.getAttributes());

    return XML_EVENT_FACTORY.createStartElement(name, attributes.iterator(), namespaces.iterator());
  }

  private static XMLEvent normalizeEndElement(
      EndElement endElement, Optional<String> feeExtensionInUse) {
    QName name = normalizeName(endElement.getName());
    ImmutableList<Namespace> namespaces =
        normalizeNamespaces(endElement.getNamespaces(), feeExtensionInUse);

    return XML_EVENT_FACTORY.createEndElement(name, namespaces.iterator());
  }

  private static QName normalizeName(QName name) {
    if (!FEE_EXTENSIONS.contains(name.getNamespaceURI())
        || name.getPrefix().equals(CANONICAL_FEE_TAG)) {
      return name;
    }
    return new QName(name.getNamespaceURI(), name.getLocalPart(), CANONICAL_FEE_TAG);
  }

  private static Attribute normalizeAttribute(Attribute attribute) {
    QName name = normalizeName(attribute.getName());
    return XML_EVENT_FACTORY.createAttribute(name, attribute.getValue());
  }

  private static Optional<Namespace> normalizeNamespace(
      Namespace namespace, Optional<String> feeExtensionInUse) {
    var extension = namespace.getNamespaceURI();
    if (!FEE_EXTENSIONS.contains(extension)) {
      return Optional.of(namespace);
    }
    if (feeExtensionInUse.isPresent() && extension.equals(feeExtensionInUse.get())) {
      if (namespace.getPrefix().equals(CANONICAL_FEE_TAG)) {
        return Optional.of(namespace);
      }
      return Optional.of(XML_EVENT_FACTORY.createNamespace(CANONICAL_FEE_TAG, extension));
    }
    return Optional.empty();
  }

  private static ImmutableList<Attribute> normalizeAttributes(Iterator<Attribute> attributes) {
    return stream(attributes).map(attr -> normalizeAttribute(attr)).collect(toImmutableList());
  }

  private static ImmutableList<Namespace> normalizeNamespaces(
      Iterator<Namespace> namespaces, Optional<String> feeExtensionInUse) {
    return stream(namespaces)
        .map(namespace -> normalizeNamespace(namespace, feeExtensionInUse))
        .flatMap(Optional::stream)
        .collect(toImmutableList());
  }

  private static XMLInputFactory createXmlInputFactory() {
    XMLInputFactory xmlInputFactory = XMLInputFactory.newFactory();
    // Coalesce adjacent data, so that all chars in a string will be grouped as one item.
    xmlInputFactory.setProperty(XMLInputFactory.IS_COALESCING, true);
    // Preserve Name Space information.
    xmlInputFactory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
    // Prevent XXE attacks.
    xmlInputFactory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    xmlInputFactory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    return xmlInputFactory;
  }
}
