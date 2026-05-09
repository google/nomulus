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

package google.registry.model.domain;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Sets.difference;
import static google.registry.util.CollectionUtils.isNullOrEmpty;
import static google.registry.util.CollectionUtils.nullSafeImmutableCopy;
import static google.registry.util.CollectionUtils.nullToEmptyImmutableCopy;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import google.registry.flows.EppException.ParameterValuePolicyErrorException;
import google.registry.flows.domain.DomainFlowUtils.RegistrantProhibitedException;
import google.registry.flows.exceptions.ContactsProhibitedException;
import google.registry.model.Buildable;
import google.registry.model.ForeignKeyUtils;
import google.registry.model.ImmutableObject;
import google.registry.model.eppcommon.AuthInfo;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppinput.ResourceCommand.AbstractSingleResourceCommand;
import google.registry.model.eppinput.ResourceCommand.ResourceCheck;
import google.registry.model.eppinput.ResourceCommand.ResourceCreateOrChange;
import google.registry.model.eppinput.ResourceCommand.ResourceUpdate;
import google.registry.model.eppinput.ResourceCommand.SingleResourceCommand;
import google.registry.model.host.Host;
import google.registry.persistence.VKey;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import jakarta.xml.bind.annotation.XmlEnumValue;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlTransient;
import jakarta.xml.bind.annotation.XmlType;
import jakarta.xml.bind.annotation.XmlValue;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;

/** A collection of {@link Domain} commands. */
public class DomainCommand {

  /** The default validity period (if not specified) is 1 year for all operations. */
  static final Period DEFAULT_PERIOD = Period.create(1, Period.Unit.YEARS);

  /**
   * A common interface for {@link Create} and {@link Update} to support linking resources.
   *
   * @param <T> the actual type (either {@link Create} or {@link Update})
   */
  public interface CreateOrUpdate<T extends CreateOrUpdate<T>> extends SingleResourceCommand {
    /** Creates a copy of this command with hard links to hosts and contacts. */
    T cloneAndLinkReferences(Instant now)
        throws InvalidReferencesException, ParameterValuePolicyErrorException;
  }

  /** The fields on "chgType" from <a href="https://tools.ietf.org/html/rfc5731">RFC5731</a>. */
  @XmlTransient
  public abstract static class DomainCreateOrChange<B extends Domain.Builder>
      extends ImmutableObject implements ResourceCreateOrChange<B> {

    /** The contactId of the registrant who registered this domain. */
    @XmlElement(name = "registrant")
    @Nullable
    String registrantContactId;

    /** Authorization info (aka transfer secret) of the domain. */
    DomainAuthInfo authInfo;

    public Optional<String> getRegistrantContactId() {
      return Optional.ofNullable(registrantContactId);
    }

    public DomainAuthInfo getAuthInfo() {
      return authInfo;
    }
  }

  /**
   * A create command for a {@link Domain}, mapping "createType" from <a
   * href="https://tools.ietf.org/html/rfc5731">RFC5731</a>.
   */
  @XmlRootElement
  @XmlAccessorType(XmlAccessType.FIELD)
  @XmlType(
      propOrder = {
        "domainName",
        "period",
        "nameserverHostNames",
        "registrantContactId",
        "foreignKeyedDesignatedContacts",
        "authInfo"
      })
  public static class Create extends DomainCreateOrChange<Domain.Builder>
      implements CreateOrUpdate<Create> {

    /** Fully qualified domain name, which serves as a unique identifier for this domain. */
    @XmlElement(name = "name")
    String domainName;

    /** Fully qualified host names of the hosts that are the nameservers for the domain. */
    @XmlElementWrapper(name = "ns")
    @XmlElement(name = "hostObj")
    Set<String> nameserverHostNames;

    /** Resolved keys to hosts that are the nameservers for the domain. */
    @XmlTransient Set<VKey<Host>> nameservers;

    /** Foreign keyed associated contacts for the domain (other than registrant). */
    @XmlElement(name = "contact")
    Set<ForeignKeyedDesignatedContact> foreignKeyedDesignatedContacts;

    /** The period that this domain's state was set to last for (e.g. 1-10 years). */
    Period period;

    public Period getPeriod() {
      return firstNonNull(period, DEFAULT_PERIOD);
    }

    @Override
    public String getTargetId() {
      return domainName;
    }

    public String getDomainName() {
      return domainName;
    }

    public ImmutableSet<String> getNameserverHostNames() {
      return nullToEmptyImmutableCopy(nameserverHostNames);
    }

    public ImmutableSet<VKey<Host>> getNameservers() {
      return nullToEmptyImmutableCopy(nameservers);
    }

    /** Creates a copy of this {@link Create} with hard links to hosts and contacts. */
    @Override
    public Create cloneAndLinkReferences(Instant now)
        throws InvalidReferencesException, ParameterValuePolicyErrorException {
      Create clone = clone(this);
      clone.nameservers = linkHosts(nullSafeImmutableCopy(clone.nameserverHostNames), now);
      if (registrantContactId != null) {
        throw new RegistrantProhibitedException();
      }
      if (!isNullOrEmpty(foreignKeyedDesignatedContacts)) {
        throw new ContactsProhibitedException();
      }
      return clone;
    }

    /** Builder for {@link Create}. */
    public static class Builder extends Buildable.Builder<Create> {
      public Builder setDomainName(String domainName) {
        getInstance().domainName = domainName;
        return this;
      }

      public Builder setPeriod(Period period) {
        getInstance().period = period;
        return this;
      }

      public Builder setNameserverHostNames(ImmutableSet<String> nameserverHostNames) {
        getInstance().nameserverHostNames =
            isNullOrEmpty(nameserverHostNames) ? null : nameserverHostNames;
        return this;
      }

      public Builder setForeignKeyedDesignatedContacts(
          ImmutableSet<ForeignKeyedDesignatedContact> foreignKeyedDesignatedContacts) {
        getInstance().foreignKeyedDesignatedContacts =
            isNullOrEmpty(foreignKeyedDesignatedContacts) ? null : foreignKeyedDesignatedContacts;
        return this;
      }

      public Builder setRegistrant(String registrant) {
        getInstance().registrantContactId = registrant;
        return this;
      }

      public Builder setAuthInfo(DomainAuthInfo authInfo) {
        getInstance().authInfo = authInfo;
        return this;
      }
    }
  }

  /** A delete command for a {@link Domain}. */
  @XmlRootElement
  @XmlAccessorType(XmlAccessType.FIELD)
  public static class Delete extends AbstractSingleResourceCommand {
    @XmlElement(name = "name")
    String name;

    @Override
    public String getTargetId() {
      return name;
    }

    @Override
    public void setTargetId(String targetId) {
      this.name = targetId;
    }
  }

  /** An info request for a {@link Domain}. */
  @XmlRootElement
  @XmlAccessorType(XmlAccessType.FIELD)
  public static class Info extends ImmutableObject implements SingleResourceCommand {

    /** The name of the domain to look up, and an attribute specifying the host lookup type. */
    @XmlElement(name = "name")
    NameWithHosts domainName;

    DomainAuthInfo authInfo;

    /** Enum of the possible values for the "hosts" attribute in info flows. */
    public enum HostsRequest {
      @XmlEnumValue("all")
      ALL,

      @XmlEnumValue("del")
      DELEGATED,

      @XmlEnumValue("sub")
      SUBORDINATE,

      @XmlEnumValue("none")
      NONE;

      public boolean requestDelegated() {
        return this == ALL || this == DELEGATED;
      }

      public boolean requestSubordinate() {
        return this == ALL || this == SUBORDINATE;
      }
    }

    /** Info commands use a variant syntax where the name tag has a "hosts" attribute. */
    public static class NameWithHosts extends ImmutableObject {
      @XmlAttribute
      HostsRequest hosts;

      @XmlValue
      String name;
    }

    /** Get the enum that specifies the requested hosts (applies only to info flows). */
    public HostsRequest getHostsRequest() {
      // Null "hosts" is implicitly ALL.
      return MoreObjects.firstNonNull(domainName.hosts, HostsRequest.ALL);
    }

    @Override
    public String getTargetId() {
      return domainName.name;
    }

    @Override
    public AuthInfo getAuthInfo() {
      return authInfo;
    }
  }

  /** A check request for {@link Domain}. */
  @XmlRootElement
  public static class Check extends ResourceCheck {}

  /** A renew command for a {@link Domain}. */
  @XmlRootElement
  @XmlAccessorType(XmlAccessType.FIELD)
  @XmlType(propOrder = {"name", "currentExpirationDate", "period"})
  public static class Renew extends AbstractSingleResourceCommand {
    @XmlElement(name = "name")
    String name;

    @Override
    public String getTargetId() {
      return name;
    }

    @Override
    public void setTargetId(String targetId) {
      this.name = targetId;
    }

    @XmlElement(name = "curExpDate")
    LocalDate currentExpirationDate;

    /** The period that this domain's state was set to last for. */
    @XmlElement Period period;

    public LocalDate getCurrentExpirationDate() {
      return currentExpirationDate;
    }

    public Period getPeriod() {
      return firstNonNull(period, DEFAULT_PERIOD);
    }

    /** Builder for {@link Renew}. */
    public static class Builder extends Buildable.Builder<Renew> {
      public Builder setTargetId(String targetId) {
        getInstance().setTargetId(targetId);
        return this;
      }

      public Builder setCurrentExpirationDate(LocalDate currentExpirationDate) {
        getInstance().currentExpirationDate = currentExpirationDate;
        return this;
      }

      public Builder setPeriod(Period period) {
        getInstance().period = period;
        return this;
      }
    }
  }

  /** A transfer operation for a {@link Domain}. */
  @XmlRootElement
  @XmlAccessorType(XmlAccessType.FIELD)
  @XmlType(propOrder = {"name", "period", "authInfo"})
  public static class Transfer extends AbstractSingleResourceCommand {
    @XmlElement(name = "name")
    String name;

    @Override
    public String getTargetId() {
      return name;
    }

    @Override
    public void setTargetId(String targetId) {
      this.name = targetId;
    }

    /** The period to extend this domain's registration upon completion of the transfer. */
    @XmlElement Period period;

    /** Authorization info used to validate if client has permissions to perform this operation. */
    DomainAuthInfo authInfo;

    public Period getPeriod() {
      return firstNonNull(period, DEFAULT_PERIOD);
    }

    @Override
    public AuthInfo getAuthInfo() {
      return authInfo;
    }
  }

  /** An update to a {@link Domain}. */
  @XmlRootElement
  @XmlAccessorType(XmlAccessType.FIELD)
  @XmlType(propOrder = {"name", "innerAdd", "innerRemove", "innerChange"})
  public static class Update
      extends ResourceUpdate<Update.DomainAddRemove, Domain.Builder, Update.Change>
      implements CreateOrUpdate<Update> {

    @XmlElement(name = "name")
    String name;

    @Override
    public String getTargetId() {
      return name;
    }

    @Override
    public void setTargetId(String targetId) {
      this.name = targetId;
    }

    @XmlElement(name = "chg")
    protected Change innerChange;

    @XmlElement(name = "add")
    protected DomainAddRemove innerAdd;

    @XmlElement(name = "rem")
    protected DomainAddRemove innerRemove;

    @Override
    protected Change getNullableInnerChange() {
      return innerChange;
    }

    @Override
    protected DomainAddRemove getNullableInnerAdd() {
      return innerAdd;
    }

    @Override
    protected DomainAddRemove getNullableInnerRemove() {
      return innerRemove;
    }

    public boolean noChangesPresent() {
      DomainAddRemove emptyAddRemove = new DomainAddRemove();
      return emptyAddRemove.equals(getInnerAdd())
          && emptyAddRemove.equals(getInnerRemove())
          && new Change().equals(getInnerChange());
    }

    /** Builder for {@link Update}. */
    public static class Builder extends Buildable.Builder<Update> {
      public Builder setTargetId(String targetId) {
        getInstance().setTargetId(targetId);
        return this;
      }

      public Builder setInnerAdd(DomainAddRemove innerAdd) {
        getInstance().innerAdd = innerAdd;
        return this;
      }

      public Builder setInnerRemove(DomainAddRemove innerRemove) {
        getInstance().innerRemove = innerRemove;
        return this;
      }

      public Builder setInnerChange(Change innerChange) {
        getInstance().innerChange = innerChange;
        return this;
      }
    }

    /** The inner change type on a domain update command. */
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(propOrder = {"nameserverHostNames", "foreignKeyedDesignatedContacts", "statusValues"})
    public static class DomainAddRemove extends ResourceUpdate.AddRemove {
      /** Fully qualified host names of the hosts that are the nameservers for the domain. */
      @XmlElementWrapper(name = "ns")
      @XmlElement(name = "hostObj")
      Set<String> nameserverHostNames;

      /** Resolved keys to hosts that are the nameservers for the domain. */
      @XmlTransient Set<VKey<Host>> nameservers;

      /** Foreign keyed associated contacts for the domain (other than registrant). */
      @XmlElement(name = "contact")
      Set<ForeignKeyedDesignatedContact> foreignKeyedDesignatedContacts;

      @XmlElement(name = "status")
      Set<StatusValue> statusValues;

      public boolean isEmpty() {
        return isNullOrEmpty(nameserverHostNames)
            && isNullOrEmpty(foreignKeyedDesignatedContacts)
            && isNullOrEmpty(statusValues);
      }

      @Override
      public void setStatusValues(ImmutableSet<StatusValue> statusValues) {
        this.statusValues = statusValues;
      }

      @Override
      public ImmutableSet<StatusValue> getStatusValues() {
        return nullToEmptyImmutableCopy(statusValues);
      }

      public ImmutableSet<String> getNameserverHostNames() {
        return nullSafeImmutableCopy(nameserverHostNames);
      }

      public ImmutableSet<VKey<Host>> getNameservers() {
        return nullToEmptyImmutableCopy(nameservers);
      }

      /** Builder for {@link DomainAddRemove}. */
      public static class Builder extends Buildable.Builder<DomainAddRemove> {
        public Builder setNameserverHostNames(ImmutableSet<String> nameserverHostNames) {
          getInstance().nameserverHostNames =
              isNullOrEmpty(nameserverHostNames) ? null : nameserverHostNames;
          return this;
        }

        public Builder setStatusValues(ImmutableSet<StatusValue> statusValues) {
          getInstance().statusValues = isNullOrEmpty(statusValues) ? null : statusValues;
          return this;
        }
      }

      /** Creates a copy of this {@link DomainAddRemove} with hard links to hosts and contacts. */
      private DomainAddRemove cloneAndLinkReferences(Instant now)
          throws InvalidReferencesException, ContactsProhibitedException {
        DomainAddRemove clone = clone(this);
        clone.nameservers = linkHosts(nullSafeImmutableCopy(clone.nameserverHostNames), now);
        if (!isNullOrEmpty(foreignKeyedDesignatedContacts)) {
          throw new ContactsProhibitedException();
        }
        return clone;
      }
    }

    /** The inner change type on a domain update command. */
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(propOrder = {"registrantContactId", "authInfo"})
    public static class Change extends DomainCreateOrChange<Domain.Builder> {
      /** Builder for {@link Change}. */
      public static class Builder extends Buildable.Builder<Change> {
        public Builder setAuthInfo(DomainAuthInfo authInfo) {
          getInstance().authInfo = authInfo;
          return this;
        }
      }

      Change cloneAndLinkReferences() throws RegistrantProhibitedException {
        Change clone = clone(this);
        if (clone.registrantContactId != null) {
          throw new RegistrantProhibitedException();
        }
        return clone;
      }
    }

    /**
     * Creates a copy of this {@link Update} with hard links to hosts and contacts.
     *
     * <p>As a side effect, this will turn null innerAdd/innerRemove/innerChange into empty versions
     * of those classes, which is harmless because the getters do that anyways.
     */
    @Override
    public Update cloneAndLinkReferences(Instant now)
        throws InvalidReferencesException, ParameterValuePolicyErrorException {
      Update clone = clone(this);
      clone.innerAdd = clone.getInnerAdd().cloneAndLinkReferences(now);
      clone.innerRemove = clone.getInnerRemove().cloneAndLinkReferences(now);
      clone.innerChange = clone.getInnerChange().cloneAndLinkReferences();
      return clone;
    }
  }

  private static ImmutableSet<VKey<Host>> linkHosts(ImmutableSet<String> hostNames, Instant now)
      throws InvalidReferencesException {
    if (hostNames == null) {
      return null;
    }
    return ImmutableSet.copyOf(loadByForeignKeysCached(hostNames, now).values());
  }

  /** Loads host keys to cached EPP resources by their foreign keys. */
  private static ImmutableMap<String, VKey<Host>> loadByForeignKeysCached(
      ImmutableSet<String> foreignKeys, Instant now) throws InvalidReferencesException {
    ImmutableMap<String, VKey<Host>> fks =
        ForeignKeyUtils.loadKeysByCacheIfEnabled(Host.class, foreignKeys, now);
    if (!fks.keySet().equals(foreignKeys)) {
      throw new InvalidReferencesException(
          Host.class, ImmutableSet.copyOf(difference(foreignKeys, fks.keySet())));
    }
    return fks;
  }

  /** Exception to throw when referenced objects don't exist. */
  public static class InvalidReferencesException extends ParameterValuePolicyErrorException {
    private final ImmutableSet<String> foreignKeys;
    private final Class<?> type;

    public InvalidReferencesException(Class<?> type, Set<String> foreignKeys) {
      super(String.format("Invalid %s reference IDs: %s", type.getSimpleName(), foreignKeys));
      this.type = checkNotNull(type);
      this.foreignKeys = nullToEmptyImmutableCopy(foreignKeys);
    }

    public ImmutableSet<String> getForeignKeys() {
      return foreignKeys;
    }

    public Class<?> getType() {
      return type;
    }
  }
}
