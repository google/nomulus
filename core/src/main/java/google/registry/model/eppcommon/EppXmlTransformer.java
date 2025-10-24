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

package google.registry.model.eppcommon;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import google.registry.model.ImmutableObject;
import google.registry.model.eppinput.EppInput;
import google.registry.model.eppoutput.EppOutput;
import google.registry.xml.ValidationMode;
import google.registry.xml.XmlException;
import google.registry.xml.XmlTransformer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/** {@link XmlTransformer} for marshalling to and from the Epp model classes.  */
public class EppXmlTransformer  {

  // Hardcoded XML schemas, ordered with respect to dependency.
  private static final ImmutableList<String> SCHEMAS =
      ImmutableList.of(
          "eppcom.xsd",
          "epp.xsd",
          "contact.xsd",
          "host.xsd",
          "domain.xsd",
          "rgp.xsd",
          "secdns.xsd",
          "fee06.xsd",
          "fee11.xsd",
          "fee12.xsd",
          "fee-std-v1.xsd",
          "metadata.xsd",
          "mark.xsd",
          "dsig.xsd",
          "smd.xsd",
          "launch.xsd",
          "allocate.xsd",
          "superuser.xsd",
          "allocationToken-1.0.xsd",
          "bulkToken.xsd");

  private static final XmlTransformer INPUT_TRANSFORMER =
      new XmlTransformer(SCHEMAS, EppInput.class);

  private static final XmlTransformer OUTPUT_TRANSFORMER =
      new XmlTransformer(SCHEMAS, EppOutput.class);

  public static void validateOutput(String xml) throws XmlException {
    OUTPUT_TRANSFORMER.validate(xml);
  }

  /**
   * Unmarshal bytes into Epp classes.
   *
   * @param clazz type to return, specified as a param to enforce typesafe generics
   */
  public static <T> T unmarshal(Class<T> clazz, byte[] bytes) throws XmlException {
    return INPUT_TRANSFORMER.unmarshal(clazz, new ByteArrayInputStream(bytes));
  }

  private static byte[] marshal(
      XmlTransformer transformer,
      ImmutableObject root,
      ValidationMode validation) throws XmlException {
    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    transformer.marshal(root, byteArrayOutputStream, UTF_8, validation);
    return byteArrayOutputStream.toByteArray();
  }

  public static byte[] marshal(EppOutput root, ValidationMode validation) throws XmlException {
    return marshal(OUTPUT_TRANSFORMER, root, validation);
  }

  @VisibleForTesting
  public static byte[] marshalInput(EppInput root, ValidationMode validation) throws XmlException {
    return marshal(INPUT_TRANSFORMER, root, validation);
  }

  @VisibleForTesting
  public static void validateInput(String xml) throws XmlException {
    INPUT_TRANSFORMER.validate(xml);
  }
}
