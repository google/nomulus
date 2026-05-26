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

package google.registry.model.domain.secdns;

import static google.registry.util.CollectionUtils.nullToEmptyImmutableCopy;

import com.google.common.collect.ImmutableSet;
import google.registry.model.Buildable;
import google.registry.model.ImmutableObject;
import google.registry.model.eppinput.EppInput.CommandExtension;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlTransient;
import jakarta.xml.bind.annotation.XmlType;
import java.util.Optional;
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
  @XmlElement Add add;

  /** Would allow changing maxSigLife except that we don't support it. */
  @XmlElement(name = "chg")
  Change change;

  public Boolean getUrgent() {
    return urgent;
  }

  public Optional<Remove> getRemove() {
    return Optional.ofNullable(remove);
  }

  public Optional<Add> getAdd() {
    return Optional.ofNullable(add);
  }

  public Optional<Change> getChange() {
    return Optional.ofNullable(change);
  }

  /** Builder for {@link SecDnsUpdateExtension}. */
  public static class Builder extends Buildable.Builder<SecDnsUpdateExtension> {
    public Builder setUrgent(Boolean urgent) {
      getInstance().urgent = urgent;
      return this;
    }

    public Builder setRemove(Remove remove) {
      getInstance().remove = remove;
      return this;
    }

    public Builder setAdd(Add add) {
      getInstance().add = add;
      return this;
    }
  }

  @XmlTransient
  abstract static class AddRemoveBase extends ImmutableObject {
    abstract static class Builder<T extends AddRemoveBase, B extends Builder<T, B>>
        extends Buildable.Builder<T> {
      public abstract B setDsData(ImmutableSet<DomainDsData> dsData);
    }
  }

  /** The inner add type on the update extension. */
  @XmlType(propOrder = "dsData")
  public static class Add extends AddRemoveBase {
    /** Delegations to add. */
    @XmlElement(name = "dsData")
    Set<DomainDsData> dsData;

    public ImmutableSet<DomainDsData> getDsData() {
      return nullToEmptyImmutableCopy(dsData);
    }

    /** Builder for {@link Add}. */
    public static class Builder extends AddRemoveBase.Builder<Add, Builder> {
      @Override
      public Builder setDsData(ImmutableSet<DomainDsData> dsData) {
        getInstance().dsData = dsData;
        return this;
      }
    }
  }

  /** The inner remove type on the update extension. */
  @XmlType(propOrder = {"all", "dsData"})
  public static class Remove extends AddRemoveBase {
    /** Whether to remove all delegations. */
    @XmlElement Boolean all;

    /** Delegations to remove. */
    @XmlElement(name = "dsData")
    Set<DomainDsData> dsData;

    public Boolean getAll() {
      return all;
    }

    public ImmutableSet<DomainDsData> getDsData() {
      return nullToEmptyImmutableCopy(dsData);
    }

    /** Builder for {@link Remove}. */
    public static class Builder extends AddRemoveBase.Builder<Remove, Builder> {
      public Builder setAll(Boolean all) {
        getInstance().all = all;
        return this;
      }

      @Override
      public Builder setDsData(ImmutableSet<DomainDsData> dsData) {
        getInstance().dsData = dsData;
        return this;
      }
    }
  }

  /** The inner change type on the update extension, though we don't actually support changes. */
  @XmlType(propOrder = "maxSigLife")
  public static class Change extends ImmutableObject {
    /**
     * Time in seconds until the signature should expire.
     *
     * <p>We do not support expirations, but we need this field to be able to return appropriate
     * errors.
     */
    @XmlElement(name = "maxSigLife")
    Long maxSigLife;
  }
}
