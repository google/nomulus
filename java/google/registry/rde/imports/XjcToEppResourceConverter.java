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

import com.google.common.base.Joiner;
import google.registry.model.EppResource;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;

/**
 * Base class for Jaxb object to {@link EppResource} converters
 */
public abstract class XjcToEppResourceConverter {

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

  /** Creates a {@link Marshaller} for serializing Jaxb objects */
  private static Marshaller createMarshaller() throws JAXBException {
    return JAXBContext.newInstance(JAXB_CONTEXT_PACKAGES).createMarshaller();
  }

  protected static byte[] getObjectXml(JAXBElement<?> jaxbElement) {
    try {
      Marshaller marshaller = createMarshaller();
      ByteArrayOutputStream bout = new ByteArrayOutputStream();
      marshaller.marshal(jaxbElement, bout);
      return bout.toByteArray();
    } catch (JAXBException e) {
      throw new RuntimeException(e);
    }
  }
}
