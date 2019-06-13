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

package google.registry.rde;

import static com.google.common.base.Verify.verify;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.flogger.FluentLogger;
import com.googlecode.objectify.Key;
import google.registry.model.ImmutableObject;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.host.HostResource;
import google.registry.model.rde.RdeMode;
import google.registry.model.registrar.Registrar;
import google.registry.tldconfig.idn.IdnTable;
import google.registry.xjc.XjcXmlTransformer;
import google.registry.xjc.rde.XjcRdeContentsType;
import google.registry.xjc.rde.XjcRdeDeposit;
import google.registry.xjc.rde.XjcRdeDepositTypeType;
import google.registry.xjc.rde.XjcRdeMenuType;
import google.registry.xjc.rdeidn.XjcRdeIdn;
import google.registry.xjc.rdeidn.XjcRdeIdnElement;
import google.registry.xjc.rdepolicy.XjcRdePolicy;
import google.registry.xjc.rdepolicy.XjcRdePolicyElement;
import google.registry.xml.ValidationMode;
import google.registry.xml.XmlException;
import google.registry.xml.XmlFragmentMarshaller;
import java.io.ByteArrayOutputStream;
import java.io.Serializable;
import java.util.Collection;
import javax.annotation.concurrent.NotThreadSafe;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.MarshalException;
import org.joda.time.DateTime;

/** XML document <i>fragment</i> marshaller for RDE. */
@NotThreadSafe
public final class RdeMarshaller implements Serializable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final long serialVersionUID = 202890386611768455L;

  private final ValidationMode validationMode;
  private transient XmlFragmentMarshaller memoizedMarshaller;

  public RdeMarshaller(ValidationMode validationMode) {
    this.validationMode = validationMode;
  }

  /** Returns top-portion of XML document. */
  public String makeHeader(
      String depositId, DateTime watermark, Collection<String> uris, int revision) {
    // We can't make JAXB marshal half an element. So we're going to use a kludge where we provide
    // it with the minimum data necessary to marshal a deposit, and then cut it up by manually.
    XjcRdeMenuType menu = new XjcRdeMenuType();
    menu.setVersion("1.0");
    menu.getObjURIs().addAll(uris);
    XjcRdePolicy policy = new XjcRdePolicy();
    policy.setScope("this-will-be-trimmed");
    policy.setElement("/make/strict/validation/pass");
    XjcRdeContentsType contents = new XjcRdeContentsType();
    contents.getContents().add(new XjcRdePolicyElement(policy));
    XjcRdeDeposit deposit = new XjcRdeDeposit();
    deposit.setId(depositId);
    deposit.setWatermark(watermark);
    deposit.setType(XjcRdeDepositTypeType.FULL);
    if (revision > 0) {
      deposit.setResend(revision);
    }
    deposit.setRdeMenu(menu);
    deposit.setContents(contents);
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    try {
      XjcXmlTransformer.marshal(deposit, os, UTF_8, validationMode);
    } catch (XmlException e) {
      throw new RuntimeException(e);
    }
    String rdeDocument = os.toString();
    String marker = "<rde:contents>\n";
    int startOfContents = rdeDocument.indexOf(marker);
    verify(startOfContents > 0, "Bad RDE document:\n%s", rdeDocument);
    return rdeDocument.substring(0, startOfContents + marker.length());
  }

  /** Returns bottom-portion of XML document. */
  public String makeFooter() {
    return "\n</rde:contents>\n</rde:deposit>\n";
  }

  /** Turns XJC element into XML fragment, with schema validation unless in lenient mode. */
  public String marshal(JAXBElement<?> element) throws MarshalException {
    return getMarshaller().marshal(element, validationMode);
  }

  /**
   * Turns XJC element into XML fragment, converting {@link MarshalException}s to {@link
   * RuntimeException}s.
   */
  public String marshalOrDie(JAXBElement<?> element) {
    try {
      return marshal(element);
    } catch (MarshalException e) {
      throw new RuntimeException(e);
    }
  }

  /** Turns {@link ContactResource} object into an XML fragment. */
  public DepositFragment marshalContact(ContactResource contact) {
    return marshalResource(RdeResourceType.CONTACT, contact,
        ContactResourceToXjcConverter.convert(contact));
  }

  /** Turns {@link DomainBase} object into an XML fragment. */
  public DepositFragment marshalDomain(DomainBase domain, RdeMode mode) {
    return marshalResource(RdeResourceType.DOMAIN, domain,
        DomainBaseToXjcConverter.convert(domain, mode));
  }

  /** Turns {@link HostResource} object into an XML fragment. */
  public DepositFragment marshalSubordinateHost(
      HostResource host, DomainBase superordinateDomain) {
    return marshalResource(RdeResourceType.HOST, host,
        HostResourceToXjcConverter.convertSubordinate(host, superordinateDomain));
  }

  /** Turns {@link HostResource} object into an XML fragment. */
  public DepositFragment marshalExternalHost(HostResource host) {
    return marshalResource(RdeResourceType.HOST, host,
        HostResourceToXjcConverter.convertExternal(host));
  }

  /** Turns {@link Registrar} object into an XML fragment. */
  public DepositFragment marshalRegistrar(Registrar registrar) {
    return marshalResource(RdeResourceType.REGISTRAR, registrar,
        RegistrarToXjcConverter.convert(registrar));
  }

  /** Turns {@link IdnTable} object into an XML fragment. */
  public String marshalIdn(IdnTable idn) {
    XjcRdeIdn bean = new XjcRdeIdn();
    bean.setId(idn.getName());
    bean.setUrl(idn.getUrl().toString());
    bean.setUrlPolicy(idn.getPolicy().toString());
    return marshalOrDie(new XjcRdeIdnElement(bean));
  }

  private DepositFragment marshalResource(
      RdeResourceType type, ImmutableObject resource, JAXBElement<?> element) {
    String xml = "";
    String error = "";
    try {
      xml = marshal(element);
    } catch (MarshalException e) {
      error = String.format("RDE XML schema validation failed: %s\n%s%s\n",
          Key.create(resource),
          e.getLinkedException(),
          getMarshaller().marshalLenient(element));
      logger.atSevere().withCause(e).log(error);
    }
    return DepositFragment.create(type, xml, error);
  }

  private XmlFragmentMarshaller getMarshaller() {
    return memoizedMarshaller != null
        ?  memoizedMarshaller
        : (memoizedMarshaller = XjcXmlTransformer.get().createFragmentMarshaller());
  }
}
