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

package google.registry.xml;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Strings.nullToEmpty;
import static google.registry.xml.ValidationMode.STRICT;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Closer;
import com.google.common.io.Resources;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import javax.xml.XMLConstants;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.UnmarshalException;
import javax.xml.bind.Unmarshaller;
import javax.xml.bind.helpers.DefaultValidationEventHandler;
import javax.xml.stream.FactoryConfigurationError;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Result;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/** Static methods for marshaling, unmarshaling, and validating XML. */
public class XmlTransformer {

  /** Default for {@link StreamSource#setSystemId(String)} so error reporting works. */
  private static final String SYSTEM_ID = "<default system id>";

  /** A transformer factory for the {@link #prettyPrint} method. */
  private static final TransformerFactory transformerFactory = TransformerFactory.newInstance();

  /** A {@link JAXBContext} (thread-safe) to use for marshaling and unmarshaling. */
  private final JAXBContext jaxbContext;

  /** A factory for setting flags to disable XXE attacks. */
  private static final XMLInputFactory XML_INPUT_FACTORY = createInputFactory();

  /** A {@link Schema} to validate XML. */
  private final Schema schema;

  /**
   * Create a new XmlTransformer that validates using the given schemas, but uses the given classes
   * (rather than generated ones) for marshaling and unmarshaling.
   *
   * @param schemaFilenames schema files, used only for validating, and relative to this package.
   * @param recognizedClasses the classes that can be used to marshal to and from
   */
  public XmlTransformer(List<String> schemaFilenames, Class<?>... recognizedClasses) {
    try {
      this.jaxbContext = JAXBContext.newInstance(recognizedClasses);
      this.schema = loadXmlSchemas(schemaFilenames);
    } catch (JAXBException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Create a new XmlTransformer that validates using the given schemas and marshals to and from
   * classes generated off of those schemas.
   *
   * @param schemaNamesToFilenames map of schema names to filenames, immutable because ordering is
   *        significant and ImmutableMap preserves insertion order. The filenames are relative to
   *        this package.
   */
  public XmlTransformer(Package pakkage, ImmutableMap<String, String> schemaNamesToFilenames) {
    try {
      this.jaxbContext = initJaxbContext(pakkage, schemaNamesToFilenames.keySet());
      this.schema = loadXmlSchemas(ImmutableList.copyOf(schemaNamesToFilenames.values()));
    } catch (JAXBException e) {
      throw new RuntimeException(e);
    }
  }

  private static XMLInputFactory createInputFactory() throws FactoryConfigurationError {
    // Prevent XXE attacks.
    XMLInputFactory xmlInputFactory = XMLInputFactory.newFactory();
    xmlInputFactory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    xmlInputFactory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    return xmlInputFactory;
  }

  /**
   * Validates XML text against {@link #schema} without marshalling.
   *
   * <p>You must specify the XML class you expect to receive as the root element.  Validation is
   * performed in accordance with the hard-coded XML schemas.
   *
   * @throws XmlException if XML input was invalid or root element doesn't match {@code expect}.
   */
  public void validate(String xml) throws XmlException {
    try {
      schema.newValidator().validate(new StreamSource(new StringReader(xml)));
    } catch (SAXException | IOException e) {
      throw new XmlException(e);
    }
  }

  /**
   * Turns XML text into an object, validating against hard-coded xml {@link #schema}s.
   *
   * @param clazz the XML class you expect to receive as the root element
   * @throws XmlException if failed to read from {@code bytes}, XML input is invalid, or root
   *         element doesn't match {@code expect}.
   * @see com.google.common.io.Files#asByteSource
   * @see com.google.common.io.Resources#asByteSource
   * @see <a href="https://errorprone.info/bugpattern/TypeParameterUnusedInFormals">TypeParameterUnusedInFormals</a>
   */
  public <T> T unmarshal(Class<T> clazz, InputStream stream) throws XmlException {
    try (InputStream autoClosingStream = stream) {
      return clazz.cast(getUnmarshaller().unmarshal(
          XML_INPUT_FACTORY.createXMLStreamReader(new StreamSource(autoClosingStream, SYSTEM_ID))));
    } catch (UnmarshalException e) {
      // Plain old parsing exceptions have a SAXParseException with no further cause.
      if (e.getLinkedException() instanceof SAXParseException
          && e.getLinkedException().getCause() == null) {
        SAXParseException sae = (SAXParseException) e.getLinkedException();
        throw new XmlException(String.format(
            "Syntax error at line %d, column %d: %s",
            sae.getLineNumber(),
            sae.getColumnNumber(),
            nullToEmpty(sae.getMessage()).replaceAll("&quot;", "")));
      }
      // These get thrown for attempted XXE attacks.
      if (e.getLinkedException() instanceof XMLStreamException) {
        XMLStreamException xse = (XMLStreamException) e.getLinkedException();
        throw new XmlException(String.format(
            "Syntax error at line %d, column %d: %s",
            xse.getLocation().getLineNumber(),
            xse.getLocation().getColumnNumber(),
            nullToEmpty(xse.getMessage())
                .replaceAll("^.*\nMessage: ", "")  // Strip an ugly prefix from XMLStreamException.
                .replaceAll("&quot;", "")));
      }
      throw new XmlException(e);
    } catch (JAXBException | XMLStreamException | IOException e) {
      throw new XmlException(e);
    }
  }

  /**
   * Streams {@code root} without XML declaration, optionally validating against the schema.
   *
   * <p>The root object must be annotated with {@link javax.xml.bind.annotation.XmlRootElement}. If
   * the validation parameter is set to {@link ValidationMode#STRICT} this method will verify that
   * your object strictly conforms to {@link #schema}. Because the output is streamed, {@link
   * XmlException} will most likely be thrown <i>after</i> output has been written.
   *
   * @param root the object to write
   * @param writer to write the output to
   * @param validation whether to validate while marshaling
   * @throws XmlException to rethrow {@link JAXBException}.
   */
  public void marshal(Object root, Writer writer, ValidationMode validation) throws XmlException {
    try {
      // Omit XML declaration because character-oriented output prevents us from knowing.
      getMarshaller(
          STRICT.equals(validation) ? schema : null,
          ImmutableMap.of(Marshaller.JAXB_FRAGMENT, true)).marshal(
              checkNotNull(root, "root"), checkNotNull(writer, "writer"));
    } catch (JAXBException e) {
      throw new XmlException(e);
    }
  }

  /**
   * Validates and streams {@code root} as formatted XML bytes with XML declaration.
   *
   * <p>The root object must be annotated with {@link javax.xml.bind.annotation.XmlRootElement}. If
   * the validation parameter is set to {@link ValidationMode#STRICT} this method will verify that
   * your object strictly conforms to {@link #schema}. Because the output is streamed,
   * {@link XmlException} will most likely be thrown <i>after</i> output has been written.
   *
   * @param root the object to write
   * @param out byte-oriented output for writing XML. This method won't close it.
   * @param charset should almost always be set to {@code "utf-8"}.
   * @param validation whether to validate while marshaling
   * @throws XmlException to rethrow {@link JAXBException}.
   * @see #unmarshal
   */
  public void marshal(Object root, OutputStream out, Charset charset, ValidationMode validation)
      throws XmlException {
    try {
      getMarshaller(
          STRICT.equals(validation) ? schema : null,
          ImmutableMap.of(Marshaller.JAXB_ENCODING, charset.toString())).marshal(
              checkNotNull(root, "root"), checkNotNull(out, "out"));
    } catch (JAXBException e) {
      throw new XmlException(e);
    }
  }

  /**
   * Validates and streams {@code root} as characters, always using strict validation.
   *
   * <p>The root object must be annotated with {@link javax.xml.bind.annotation.XmlRootElement}.
   * This method will verify that your object strictly conforms to {@link #schema}. Because the
   * output is streamed, {@link XmlException} will most likely be thrown <i>after</i> output has
   * been written.
   *
   * @param root the object to write
   * @param result to write the output to
   * @throws XmlException to rethrow {@link JAXBException}.
   */
  public void marshalStrict(Object root, Result result) throws XmlException {
    try {
      getMarshaller(schema, ImmutableMap.of())
          .marshal(checkNotNull(root, "root"), checkNotNull(result, "result"));
    } catch (JAXBException e) {
      throw new XmlException(e);
    }
  }

  /** Returns new instance of {@link XmlFragmentMarshaller}. */
  public XmlFragmentMarshaller createFragmentMarshaller() {
    return new XmlFragmentMarshaller(jaxbContext, schema);
  }

  /** Creates a single {@link Schema} from multiple {@code .xsd} files. */
  public static Schema loadXmlSchemas(List<String> schemaFilenames) {
    try (Closer closer = Closer.create()) {
      StreamSource[] sources = new StreamSource[schemaFilenames.size()];
      for (int i = 0; i < schemaFilenames.size(); ++i) {
        sources[i] = new StreamSource(closer.register(
            Resources.asByteSource(Resources.getResource(
                XmlTransformer.class, "xsd/" + schemaFilenames.get(i))).openStream()));
      }
      return SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI).newSchema(sources);
    } catch (IOException | SAXException e) {
      throw new RuntimeException(e);
    }
  }

  /** Creates a {@link JAXBContext} from multiple schema names. */
  private static JAXBContext initJaxbContext(
      Package pakkage, Collection<String> schemaNames) throws JAXBException {
    String prefix = pakkage.getName() + ".";
    return JAXBContext.newInstance(prefix + Joiner.on(':' + prefix).join(schemaNames));
  }

  /** Get a {@link Unmarshaller} instance with the default configuration. */
  private Unmarshaller getUnmarshaller() throws JAXBException {
    Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
    unmarshaller.setSchema(schema);
    // This handler was the default in JAXB 1.0. It fails on any exception thrown while
    // unmarshalling. In JAXB 2.0 some errors are considered recoverable and are ignored, which is
    // not what we want, so we have to set this explicitly.
    unmarshaller.setEventHandler(new DefaultValidationEventHandler());
    return unmarshaller;
  }

  /** Get a {@link Marshaller} instance with the given configuration. */
  private Marshaller getMarshaller(@Nullable Schema schemaParam, Map<String, ?> properties)
      throws JAXBException {
    Marshaller marshaller = jaxbContext.createMarshaller();
    for (Map.Entry<String, ?> entry : properties.entrySet()) {
      marshaller.setProperty(entry.getKey(), entry.getValue());
    }
    marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
    marshaller.setSchema(schemaParam);
    return marshaller;
  }

  /** Pretty print XML. */
  public static String prettyPrint(String xmlString) {
    StringWriter prettyXml = new StringWriter();
    try {
      Transformer transformer = transformerFactory.newTransformer();
      transformer.setOutputProperty(OutputKeys.INDENT, "yes");
      transformer.setOutputProperty(OutputKeys.STANDALONE, "yes");
      transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
      transformer.transform(
          new StreamSource(new StringReader(xmlString)),
          new StreamResult(prettyXml));

      // Remove whitespace-only/blank lines (which the XMLTransformer in Java 9 and up sometimes
      // adds depending on input format). Surprisingly, this is the least bad solution. See:
      // https://stackoverflow.com/questions/58478632/how-to-avoid-extra-blank-lines-in-xml-generation-with-java
      // Note that a simple regex replace is waaaaay more performant than using an XSLT.
      return prettyXml.toString().replaceAll("\\n\\s*\\n", "\n");
    } catch (TransformerException e) {
      return xmlString;  // We couldn't prettify it, but that's ok; fail gracefully.
    }
  }

  /** Pretty print XML bytes. */
  public static String prettyPrint(byte[] xmlBytes) {
    return prettyPrint(new String(xmlBytes, UTF_8));
  }
}
