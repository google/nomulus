// Copyright 2016 The Nomulus Authors. All Rights Reserved.
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

package google.registry.rde.imports;

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import google.registry.xjc.rdecontact.XjcRdeContact;
import google.registry.xjc.rdecontact.XjcRdeContactElement;
import google.registry.xjc.rdedomain.XjcRdeDomain;
import google.registry.xjc.rdedomain.XjcRdeDomainElement;
import google.registry.xjc.rdeeppparams.XjcRdeEppParams;
import google.registry.xjc.rdeeppparams.XjcRdeEppParamsElement;
import google.registry.xjc.rdeheader.XjcRdeHeader;
import google.registry.xjc.rdeheader.XjcRdeHeaderCount;
import google.registry.xjc.rdeheader.XjcRdeHeaderElement;
import google.registry.xjc.rdehost.XjcRdeHost;
import google.registry.xjc.rdehost.XjcRdeHostElement;
import google.registry.xjc.rdeidn.XjcRdeIdn;
import google.registry.xjc.rdeidn.XjcRdeIdnElement;
import google.registry.xjc.rdenndn.XjcRdeNndn;
import google.registry.xjc.rdenndn.XjcRdeNndnElement;
import google.registry.xjc.rderegistrar.XjcRdeRegistrar;
import google.registry.xjc.rderegistrar.XjcRdeRegistrarElement;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import javax.annotation.concurrent.NotThreadSafe;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * RDE escrow deposit file parser
 *
 * <p>{@link RdeParser} parses escrow deposit files as a sequence of elements. The parser will first
 * parse and cache the RDE header before any other elements, so all calls to {@link #getHeader} will
 * return the header even if the parser has advanced beyond it.
 *
 * <p>{@link RdeParser} currently supports parsing the following rde elements as jaxb objects:
 * <ul>
 * <li>Contact</li>
 * <li>Host</li>
 * <li>Domain</li>
 * <li>Registrar</li>
 * <li>Tld</li>
 * <li>IDN table reference</li>
 * <li>EPP Params</li>
 * <li>NNDN</li>
 * </ul>
 *
 * <p>Any calls to {@link #nextDomain}, {@link #nextHost}, etc. will advance the parser to the next
 * element in the file, if any additional elements of that type exist. Since the order of these
 * elements is not known at the time the file is read, client code should only try to parse one type
 * of element at a time. Parsing of additional element types should be performed by creating a new
 * parser.
 */
@NotThreadSafe
public class RdeParser implements Closeable {

  private static final String RDE_DOMAIN_URI = "urn:ietf:params:xml:ns:rdeDomain-1.0";
  private static final String RDE_HOST_URI = "urn:ietf:params:xml:ns:rdeHost-1.0";
  private static final String RDE_CONTACT_URI = "urn:ietf:params:xml:ns:rdeContact-1.0";
  private static final String RDE_REGISTRAR_URI = "urn:ietf:params:xml:ns:rdeRegistrar-1.0";
  private static final String RDE_IDN_URI = "urn:ietf:params:xml:ns:rdeIDN-1.0";
  private static final String RDE_NNDN_URI = "urn:ietf:params:xml:ns:rdeNNDN-1.0";
  private static final String RDE_EPP_PARAMS_URI = "urn:ietf:params:xml:ns:rdeEppParams-1.0";
  private static final String RDE_HEADER_URI = "urn:ietf:params:xml:ns:rdeHeader-1.0";

  /** List of packages to initialize JAXBContext. **/
  private static final String JAXB_CONTEXT_PACKAGES = Joiner.on(":")
      .join(Arrays.asList(
          "google.registry.xjc.contact",
          "google.registry.xjc.domain",
          "google.registry.xjc.host",
          "google.registry.xjc.mark",
          "google.registry.xjc.rde",
          "google.registry.xjc.rdecontact",
          "google.registry.xjc.rdedomain",
          "google.registry.xjc.rdeeppparams",
          "google.registry.xjc.rdeheader",
          "google.registry.xjc.rdeidn",
          "google.registry.xjc.rdenndn",
          "google.registry.xjc.rderegistrar",
          "google.registry.xjc.smd"));

  /**
   * Convenient immutable java representation of an RDE header
   */
  public static class RdeHeader {

    private final ImmutableMap<String, Long> counts;
    private final String tld;

    public String getTld() {
      return tld;
    }

    public Long getDomainCount() {
      return Optional.fromNullable(counts.get(RDE_DOMAIN_URI)).or(0L);
    }

    public Long getHostCount() {
      return Optional.fromNullable(counts.get(RDE_HOST_URI)).or(0L);
    }

    public Long getContactCount() {
      return Optional.fromNullable(counts.get(RDE_CONTACT_URI)).or(0L);
    }

    public Long getRegistrarCount() {
      return Optional.fromNullable(counts.get(RDE_REGISTRAR_URI)).or(0L);
    }

    public Long getIdnCount() {
      return Optional.fromNullable(counts.get(RDE_IDN_URI)).or(0L);
    }

    public Long getNndnCount() {
      return Optional.fromNullable(counts.get(RDE_NNDN_URI)).or(0L);
    }

    public Long getEppParamsCount() {
      return Optional.fromNullable(counts.get(RDE_EPP_PARAMS_URI)).or(0L);
    }

    private RdeHeader(XjcRdeHeader header) {
      this.tld = header.getTld();
      ImmutableMap.Builder<String, Long> builder = new ImmutableMap.Builder<>();
      for (XjcRdeHeaderCount count : header.getCounts()) {
        builder = builder.put(count.getUri(), count.getValue());
      }
      counts = builder.build();
    }
  }

  private final InputStream xmlInput;
  private final XMLStreamReader reader;
  private final Unmarshaller unmarshaller;

  private RdeHeader header;

  /**
   * Creates a new instance of {@link RdeParser}
   *
   * @param xmlInput Contents of the escrow deposit file
   * @throws JAXBException
   */
  public RdeParser(InputStream xmlInput) throws XMLStreamException, JAXBException {
    this.xmlInput = xmlInput;
    this.unmarshaller = JAXBContext.newInstance(JAXB_CONTEXT_PACKAGES).createUnmarshaller();
    this.reader = XMLInputFactory.newInstance().createXMLStreamReader(xmlInput);
    this.header = new RdeHeader(readHeader());
  }

  /**
   * Attempts to read the RDE header as a jaxb object.
   *
   * @throws IllegalStateException if no RDE header is found in the file
   */
  private XjcRdeHeader readHeader() {
    if (!nextElement(RDE_HEADER_URI, "header")) {
      throw new IllegalStateException("No RDE Header found");
    }
    XjcRdeHeaderElement element = (XjcRdeHeaderElement) unmarshalElement(RDE_HEADER_URI, "header");
    return element.getValue();
  }

  /**
   * Unmarshals the current element into a Jaxb element.
   *
   * @param uri Element URI
   * @param name Element Name
   * @return Jaxb Element
   * @throws IllegalStateException if the parser is not at the specified element
   */
  private Object unmarshalElement(String uri, String name) {
    checkArgumentNotNull(name, "name cannot be null");
    checkArgumentNotNull(uri, "uri cannot be null");
    try {
      if (isAtElement(uri, name)) {
        Object element = unmarshaller.unmarshal(reader);
        return element;
      } else {
        throw new IllegalStateException(String.format("Not at element %s:%s", uri, name));
      }
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Checks if the parser is at an instance of the specified element.
   *
   * @param uri Element URI
   * @param name Element Name
   * @return true if the parser is at an instance of the element, false otherwise
   */
  private boolean isAtElement(String uri, String name) {
    return reader.getEventType() == XMLStreamReader.START_ELEMENT
        && uri.equals(reader.getNamespaceURI()) && name.equals(reader.getName().getLocalPart());
  }

  /**
   * Attempts to advance to the next instance of the specified element.
   *
   * <p>The parser may skip over other types of elements while advancing to the next instance of the
   * specified element.
   *
   * @param uri Element URI
   * @param name Element Name
   * @return true if the parser advanced to the element, false otherwise.
   */
  private boolean nextElement(String uri, String name) {
    try {
      while (reader.hasNext()) {
        reader.next();
        if (isAtElement(uri, name)) {
          return true;
        }
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    return false;
  }

  public RdeHeader getHeader() {
    return header;
  }

  /**
   * Attempts to skip over a number of specified elements.
   *
   * <p>If the parser is not currently at one of the specified elements, it will advance to the next
   * instance before skipping any.
   *
   * <p>In the process of skipping over a specific type of element, other elements may be skipped as
   * well. Elements of types other than that specified by the uri and name will not be counted.
   *
   * @param numberOfElements Number of elements to skip
   * @param uri Element URI
   * @param name Element Name
   * @return Number of elements that were skipped
   */
  private int skipElements(int numberOfElements, String uri, String name) {
    checkArgument(
        numberOfElements >= 0, "number of elements must be greater than or equal to zero");
    // don't do any skipping if numberOfElements is 0
    if (numberOfElements == 0) {
      return 0;
    }
    // unless the parser is at one of the specified elements,
    // the first call to nextElement() will advance to the first
    // element to be skipped
    int skipped = 0;
    if (isAtElement(uri, name)) {
      skipped = 1;
    }
    while (nextElement(uri, name) && skipped < numberOfElements) {
      skipped++;
    }
    return skipped;
  }

  /**
   * Advances parser to the next contact element.
   *
   * <p>The parser may skip over other types of elements while advancing to the next contact
   * element.
   *
   * @return true if the parser advanced to a contact element, false otherwise
   */
  public boolean nextContact() {
    return nextElement(RDE_CONTACT_URI, "contact");
  }

  /**
   * Checks if the parser is at a contact element.
   *
   * @return true if the parser is at a contact element, false otherwise
   */
  public boolean isAtContact() {
    return isAtElement(RDE_CONTACT_URI, "contact");
  }

  /**
   * Attempts to skip over a number of contacts.
   *
   * <p>If the parser is not currently at a contact element, it will advance to the next instance
   * before skipping any.
   *
   * <p>In the process of skipping over a contact element, other elements may be skipped as well.
   * Elements of types other than contact elements will not be counted.
   *
   * @return Number of contact elements that were skipped
   */
  public int skipContacts(int numberOfContacts) {
    return skipElements(numberOfContacts, RDE_CONTACT_URI, "contact");
  }

  /**
   * Gets the current contact.
   *
   * <p>The parser must be at a contact element before this method is called, or an
   * {@link IllegalStateException} will be thrown. Check the return value of {@link #isAtContact} or
   * {@link #nextContact} to determine if the parser is at a contact element.
   *
   * @return Jaxb Contact
   * @throws IllegalStateException if the parser is not at a contact element
   */
  public XjcRdeContact getContact() {
    XjcRdeContactElement element =
        (XjcRdeContactElement) unmarshalElement(RDE_CONTACT_URI, "contact");
    return element.getValue();
  }

  /**
   * Advances parser to the next domain element.
   *
   * <p>The parser may skip over other types of elements while advancing to the next domain element.
   *
   * @return true if the parser advanced to a domain element, false otherwise
   */
  public boolean nextDomain() {
    return nextElement(RDE_DOMAIN_URI, "domain");
  }

  /**
   * Checks if the parser is at a domain element.
   *
   * @return true if the parser is at a domain element, false otherwise
   */
  public boolean isAtDomain() {
    return isAtElement(RDE_DOMAIN_URI, "domain");
  }

  /**
   * Attempts to skip over a number of domains.
   *
   * <p>If the parser is not currently at a domain element, it will advance to the next instance
   * before skipping any.
   *
   * <p>In the process of skipping over a domain element, other elements may be skipped as well.
   * Elements of types other than domain elements will not be counted.
   *
   * @return Number of domain elements that were skipped
   */
  public int skipDomains(int numberOfDomains) {
    return skipElements(numberOfDomains, RDE_DOMAIN_URI, "domain");
  }

  /**
   * Gets the current domain.
   *
   * <p>The parser must be at a domain element before this method is called, or an
   * {@link IllegalStateException} will be thrown. Check the return value of {@link #isAtDomain} or
   * {@link #nextDomain} to determine if the parser is at a domain element.
   *
   * @return Jaxb Domain
   * @throws IllegalStateException if the parser is not at a domain element
   */
  public XjcRdeDomain getDomain() {
    XjcRdeDomainElement element = (XjcRdeDomainElement) unmarshalElement(RDE_DOMAIN_URI, "domain");
    return element.getValue();
  }

  /**
   * Advances parser to the next host element.
   *
   * <p>The parser may skip over other types of elements while advancing to the next host element.
   *
   * @return true if the parser advanced to a host element, false otherwise
   */
  public boolean nextHost() {
    return nextElement(RDE_HOST_URI, "host");
  }

  /**
   * Checks if the parser is at a host element.
   *
   * @return true if the parser is at a host element, false otherwise
   */
  public boolean isAtHost() {
    return isAtElement(RDE_HOST_URI, "host");
  }

  /**
   * Attempts to skip over a number of hosts.
   *
   * <p>If the parser is not currently at a host element, it will advance to the next instance
   * before skipping any.
   *
   * <p>In the process of skipping over a host element, other elements may be skipped as well.
   * Elements of types other than host elements will not be counted.
   *
   * @return Number of host elements that were skipped
   */
  public int skipHosts(int numberOfHosts) {
    return skipElements(numberOfHosts, RDE_HOST_URI, "host");
  }

  /**
   * Gets the current host.
   *
   * <p>The parser must be at a host element before this method is called, or an
   * {@link IllegalStateException} will be thrown. Check the return value of {@link #isAtHost} or
   * {@link #nextHost} to determine if the parser is at a host element.
   *
   * @return Jaxb Host
   * @throws IllegalStateException if the parser is not at a host element
   */
  public XjcRdeHost getHost() {
    XjcRdeHostElement element = (XjcRdeHostElement) unmarshalElement(RDE_HOST_URI, "host");
    return element.getValue();
  }

  /**
   * Advances parser to the next registrar element.
   *
   * <p>The parser may skip over other types of elements while advancing to the next registrar
   * element.
   *
   * @return true if the parser advanced to a registrar element, false otherwise
   */
  public boolean nextRegistrar() {
    return nextElement(RDE_REGISTRAR_URI, "registrar");
  }

  /**
   * Checks if the parser is at a registrar element.
   *
   * @return true if the parser is at a registrar element, false otherwise
   */
  public boolean isAtRegistrar() {
    return isAtElement(RDE_REGISTRAR_URI, "registrar");
  }

  /**
   * Gets the current registrar.
   *
   * <p>The parser must be at a registrar element before this method is called, or an
   * {@link IllegalStateException} will be thrown. Check the return value of {@link #isAtRegistrar}
   * or {@link #nextRegistrar} to determine if the parser is at a registrar element.
   *
   * @return Jaxb Registrar
   * @throws IllegalStateException if the parser is not at a registrar element
   */
  public XjcRdeRegistrar getRegistrar() {
    XjcRdeRegistrarElement element =
        (XjcRdeRegistrarElement) unmarshalElement(RDE_REGISTRAR_URI, "registrar");
    return element.getValue();
  }

  /**
   * Advances parser to the next IDN element.
   *
   * <p>The parser may skip over other types of elements while advancing to the next IDN element.
   *
   * @return true if the parser advanced to a IDN element, false otherwise
   */
  public boolean nextIdn() {
    return nextElement(RDE_IDN_URI, "idnTableRef");
  }

  /**
   * Checks if the parser is at a IDN element.
   *
   * @return true if the parser is at a IDN element, false otherwise
   */
  public boolean isAtIdn() {
    return isAtElement(RDE_IDN_URI, "idnTableRef");
  }

  /**
   * Gets the current IDN.
   *
   * <p>The parser must be at a IDN element before this method is called, or an
   * {@link IllegalStateException} will be thrown. Check the return value of {@link #isAtIdn} or
   * {@link #nextIdn} to determine if the parser is at a IDN element.
   *
   * @return Jaxb IDN
   * @throws IllegalStateException if the parser is not at a IDN element
   */
  public XjcRdeIdn getIdn() {
    XjcRdeIdnElement element = (XjcRdeIdnElement) unmarshalElement(RDE_IDN_URI, "idnTableRef");
    return element.getValue();
  }

  /**
   * Advances parser to the next NNDN element.
   *
   * <p>The parser may skip over other types of elements while advancing to the next NNDN element.
   *
   * @return true if the parser advanced to a NNDN element, false otherwise
   */
  public boolean nextNndn() {
    return nextElement(RDE_NNDN_URI, "NNDN");
  }

  /**
   * Checks if the parser is at a NNDN element.
   *
   * @return true if the parser is at a NNDN element, false otherwise
   */
  public boolean isAtNndn() {
    return isAtElement(RDE_NNDN_URI, "NNDN");
  }

  /**
   * Gets the current NNDN.
   *
   * <p>The parser must be at a NNDN element before this method is called, or an
   * {@link IllegalStateException} will be thrown. Check the return value of {@link #isAtNndn} or
   * {@link #nextNndn} to determine if the parser is at a NNDN element.
   *
   * @return Jaxb NNDN
   * @throws IllegalStateException if the parser is not at a NNDN element
   */
  public XjcRdeNndn getNndn() {
    XjcRdeNndnElement element = (XjcRdeNndnElement) unmarshalElement(RDE_NNDN_URI, "NNDN");
    return element.getValue();
  }

  /**
   * Advances parser to the next eppParams element.
   *
   * <p>The parser may skip over other types of elements while advancing to the next eppParams
   * element.
   *
   * @return true if the parser advanced to a eppParams element, false otherwise
   */
  public boolean nextEppParams() {
    return nextElement(RDE_EPP_PARAMS_URI, "eppParams");
  }

  /**
   * Checks if the parser is at a eppParams element.
   *
   * @return true if the parser is at a eppParams element, false otherwise
   */
  public boolean isAtEppParams() {
    return isAtElement(RDE_EPP_PARAMS_URI, "eppParams");
  }

  /**
   * Gets the current eppParams.
   *
   * <p>The parser must be at a eppParams element before this method is called, or an
   * {@link IllegalStateException} will be thrown. Check the return value of {@link #isAtEppParams}
   * or {@link #nextEppParams} to determine if the parser is at a eppParams element.
   *
   * @return Jaxb EppParams
   * @throws IllegalStateException if the parser is not at a eppParams element
   */
  public XjcRdeEppParams getEppParams() {
    XjcRdeEppParamsElement element =
        (XjcRdeEppParamsElement) unmarshalElement(RDE_EPP_PARAMS_URI, "eppParams");
    return element.getValue();
  }

  /**
   * Closes the underlying InputStream
   *
   * @throws IOException if the underlying stream throws {@link IOException} on close.
   */
  @Override
  public void close() throws IOException {
    xmlInput.close();
  }
}
