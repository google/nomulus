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

package google.registry.xjc;

import static google.registry.xml.ValidationMode.LENIENT;
import static google.registry.xml.ValidationMode.STRICT;

import com.google.common.collect.ImmutableMap;
import google.registry.xml.ValidationMode;
import google.registry.xml.XmlException;
import google.registry.xml.XmlTransformer;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.Charset;

/** Static methods for marshalling to and from the generated classes.  */
public class XjcXmlTransformer {

  private static final XmlTransformer INSTANCE =
      new XmlTransformer(
          XjcXmlTransformer.class.getPackage(),
          // Hardcoded XML schemas, ordered with respect to dependency.
          new ImmutableMap.Builder<String, String>()
              .put("eppcom", "eppcom.xsd")
              .put("epp", "epp.xsd")
              .put("contact", "contact.xsd")
              .put("host", "host.xsd")
              .put("domain", "domain.xsd")
              .put("rgp", "rgp.xsd")
              .put("secdns", "secdns.xsd")
              .put("mark", "mark.xsd")
              .put("dsig", "dsig.xsd")
              .put("smd", "smd.xsd")
              .put("fee06", "fee06.xsd")
              .put("fee11", "fee11.xsd")
              .put("fee12", "fee12.xsd")
              .put("feestdv1", "fee-std-v1.xsd")
              .put("launch", "launch.xsd")
              .put("rde", "rde.xsd")
              .put("rdeheader", "rde-header.xsd")
              .put("rdereport", "rde-report.xsd")
              .put("rdecontact", "rde-contact.xsd")
              .put("rdehost", "rde-host.xsd")
              .put("rdeidn", "rde-idn.xsd")
              .put("rdedomain", "rde-domain.xsd")
              .put("rdeeppparams", "rde-eppparams.xsd")
              .put("rdenndn", "rde-nndn.xsd")
              .put("rdenotification", "rde-notification.xsd")
              .put("rdepolicy", "rde-policy.xsd")
              .put("rderegistrar", "rde-registrar.xsd")
              .put("iirdea", "iirdea.xsd")
              .build());

  public static XmlTransformer get() {
    return INSTANCE;
  }

  public static <T> T unmarshal(Class<T> clazz, InputStream stream) throws XmlException {
    return INSTANCE.unmarshal(clazz, stream);
  }

  public static void marshalLenient(Object root, Writer writer) throws XmlException {
    INSTANCE.marshal(root, writer, LENIENT);
  }

  public static void marshalLenient(Object root, OutputStream out, Charset charset)
      throws XmlException {
    INSTANCE.marshal(root, out, charset, LENIENT);
  }

  public static void marshalStrict(Object root, OutputStream out, Charset charset)
      throws XmlException {
    INSTANCE.marshal(root, out, charset, STRICT);
  }

  public static void marshal(
      Object root, OutputStream out, Charset charset, ValidationMode validationMode)
      throws XmlException {
    INSTANCE.marshal(root, out, charset, validationMode);
  }
}
