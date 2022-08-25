// Copyright 2022 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.domain.packagetoken;

import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import google.registry.model.ImmutableObject;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.eppoutput.EppResponse.ResponseExtension;
import google.registry.persistence.VKey;
import google.registry.xml.TrimWhitespaceAdapter;
import java.util.Optional;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlType;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

/**
 * An XML data object that represents a package token extension that may be present on the response
 * to EPP domain info commands.
 */
@XmlRootElement(name = "packageData")
@XmlType(propOrder = {"token"})
public class PackageTokenResponseExtension extends ImmutableObject implements ResponseExtension {

  /** Token string of the PACKAGE token the name belongs to. */
  @XmlElement(name = "token")
  @XmlJavaTypeAdapter(TrimWhitespaceAdapter.class)
  String token;

  public static PackageTokenResponseExtension create(Optional<VKey<AllocationToken>> tokenKey) {
    PackageTokenResponseExtension instance = new PackageTokenResponseExtension();
    instance.token = "";
    if (tokenKey.isPresent()) {
      instance.token = tm().transact(() -> tm().loadByKey(tokenKey.get())).getToken();
    }
    return instance;
  }
}
