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

import com.googlecode.objectify.annotation.Embed;
import google.registry.model.ImmutableObject;
import google.registry.model.UnsafeSerializable;
import javax.persistence.Embeddable;
import javax.persistence.Embedded;
import javax.persistence.MappedSuperclass;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlTransient;
import javax.xml.bind.annotation.XmlType;
import javax.xml.bind.annotation.XmlValue;
import javax.xml.bind.annotation.adapters.CollapsedStringAdapter;
import javax.xml.bind.annotation.adapters.NormalizedStringAdapter;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

/**
 * The "authInfoType" complex type.
 *
 * <p>RFCs 5731 and 5732 define this almost identically up to the namespace.
 */
@XmlTransient
@Embeddable
@MappedSuperclass
public abstract class AuthInfo extends ImmutableObject implements UnsafeSerializable {

  @Embedded protected PasswordAuth pw;

  public PasswordAuth getPw() {
    return pw;
  }

  /** The "pwAuthInfoType" complex type. */
  @Embed
  @XmlType(namespace = "urn:ietf:params:xml:ns:eppcom-1.0")
  @Embeddable
  public static class PasswordAuth extends ImmutableObject implements UnsafeSerializable {
    @XmlValue
    @XmlJavaTypeAdapter(NormalizedStringAdapter.class)
    String value;

    @XmlAttribute(name = "roid")
    @XmlJavaTypeAdapter(CollapsedStringAdapter.class)
    String repoId;

    public String getValue() {
      return value;
    }

    public String getRepoId() {
      return repoId;
    }

    public static PasswordAuth create(String value, String repoId) {
      PasswordAuth instance = new PasswordAuth();
      instance.value = value;
      instance.repoId = repoId;
      return instance;
    }

    public static PasswordAuth create(String value) {
      return create(value, null);
    }
  }
}
