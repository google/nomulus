// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.base.Throwables.throwIfInstanceOf;
import static com.google.common.base.Verify.verify;
import static google.registry.xml.ValidationMode.LENIENT;
import static google.registry.xml.ValidationMode.STRICT;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.re2j.Pattern;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.MarshalException;
import jakarta.xml.bind.Marshaller;
import java.io.ByteArrayOutputStream;
import javax.annotation.concurrent.NotThreadSafe;
import javax.xml.validation.Schema;

/** JAXB marshaller for building pieces of XML documents in a single thread. */
@NotThreadSafe
public final class XmlFragmentMarshaller {

  private static final Pattern XMLNS_PATTERN = Pattern.compile(" xmlns:\\w+=\"[^\"]+\"");

  private final ByteArrayOutputStream os = new ByteArrayOutputStream();
  private final Marshaller marshaller;
  private final Schema schema;

  XmlFragmentMarshaller(JAXBContext jaxbContext, Schema schema) {
    try {
      marshaller = jaxbContext.createMarshaller();
      marshaller.setProperty(Marshaller.JAXB_ENCODING, UTF_8.toString());
      marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
      marshaller.setProperty(Marshaller.JAXB_FRAGMENT, true);
    } catch (JAXBException e) {
      throw new RuntimeException(e);
    }
    this.schema = schema;
  }

  /**
   * Turns an individual JAXB element into an XML fragment string.
   *
   * @throws MarshalException if schema validation failed
   */
  public String marshal(JAXBElement<?> element) throws MarshalException {
    return marshal(element, STRICT);
  }

  /** Turns an individual JAXB element into an XML fragment string. */
  public String marshalLenient(JAXBElement<?> element) {
    try {
      return marshal(element, LENIENT);
    } catch (MarshalException e) {
      throw new RuntimeException("MarshalException shouldn't be thrown in lenient mode", e);
    }
  }

  /**
   * Turns an individual JAXB element into an XML fragment string using the given validation mode.
   *
   * @throws MarshalException if schema validation failed
   */
  public String marshal(JAXBElement<?> element, ValidationMode validationMode)
      throws MarshalException {
    os.reset();
    marshaller.setSchema((validationMode == STRICT) ? schema : null);
    try {
      marshaller.marshal(element, os);
    } catch (JAXBException e) {
      throwIfInstanceOf(e, MarshalException.class);
      throw new RuntimeException("Mysterious XML exception", e);
    }
    String fragment = new String(os.toByteArray(), UTF_8);
    int endOfFirstLine = fragment.indexOf(">\n");
    verify(endOfFirstLine > 0, "Bad XML fragment:\n%s", fragment);
    String firstLine = fragment.substring(0, endOfFirstLine + 2);
    String rest = fragment.substring(firstLine.length());
    return XMLNS_PATTERN.matcher(firstLine).replaceAll("") + rest;
  }
}
