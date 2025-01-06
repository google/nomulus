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

package google.registry.model.domain.secdns;

import static google.registry.util.CollectionUtils.nullSafeImmutableCopy;

import com.google.common.collect.ImmutableSet;
import google.registry.model.ImmutableObject;
import google.registry.model.eppinput.EppInput.CommandExtension;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlType;
import java.util.Set;

/** The EPP secDNS extension that may be present on domain create commands. */
@XmlRootElement(name = "create")
@XmlType(propOrder = {"maxSigLife", "dsData"})
public class SecDnsCreateExtension extends ImmutableObject implements CommandExtension {
  /**
   * Time in seconds until the signature should expire.
   *
   * <p>We do not support expirations, but we need this field to be able to return appropriate
   * errors.
   */
  Long maxSigLife;

  /** Signatures for this domain. */
  Set<DomainDsData> dsData;

  public Long getMaxSigLife() {
    return maxSigLife;
  }

  public ImmutableSet<DomainDsData> getDsData() {
    return nullSafeImmutableCopy(dsData);
  }
}
