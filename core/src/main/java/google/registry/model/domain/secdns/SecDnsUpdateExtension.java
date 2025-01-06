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

import static google.registry.util.CollectionUtils.nullToEmptyImmutableCopy;

import com.google.common.collect.ImmutableSet;
import google.registry.model.ImmutableObject;
import google.registry.model.eppinput.EppInput.CommandExtension;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlTransient;
import jakarta.xml.bind.annotation.XmlType;
import java.util.Set;

/** The EPP secDNS extension that may be present on domain update commands. */
@XmlRootElement(name = "update")
@XmlType(propOrder = {"remove", "add", "change"})
public class SecDnsUpdateExtension extends ImmutableObject implements CommandExtension {

  /**
   * Specifies whether this update is urgent.
   *
   * <p>We don't support urgent updates but we need this to be present to provide appropriate error
   * messages if a client requests it.
   */
  @XmlAttribute
  Boolean urgent;

  /** Allows removing some or all delegations. */
  @XmlElement(name = "rem")
  Remove remove;

  /** Allows adding new delegations. */
  Add add;

  /** Would allow changing maxSigLife except that we don't support it. */
  @XmlElement(name = "chg")
  Change change;

  public Boolean getUrgent() {
    return urgent;
  }

  public Remove getRemove() {
    return remove;
  }

  public Add getAdd() {
    return add;
  }

  public Change getChange() {
    return change;
  }

  @XmlTransient
  abstract static class AddRemoveBase extends ImmutableObject {
    /** Delegations to add or remove. */
    Set<DomainDsData> dsData;

    public ImmutableSet<DomainDsData> getDsData() {
      return nullToEmptyImmutableCopy(dsData);
    }
  }

  /** The inner add type on the update extension. */
  public static class Add extends AddRemoveBase {}

  /** The inner remove type on the update extension. */
  @XmlType(propOrder = {"all", "dsData"})
  public static class Remove extends AddRemoveBase {
    /** Whether to remove all delegations. */
    Boolean all;

    public Boolean getAll() {
      return all;
    }
  }

  /** The inner change type on the update extension, though we don't actually support changes. */
  public static class Change extends ImmutableObject {
    /**
     * Time in seconds until the signature should expire.
     *
     * <p>We do not support expirations, but we need this field to be able to return appropriate
     * errors.
     */
    Long maxSigLife;
  }
}
