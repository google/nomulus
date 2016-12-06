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

package google.registry.model.domain;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Predicates.not;
import static com.google.common.base.Strings.emptyToNull;
import static com.google.common.collect.Iterables.all;
import static com.google.common.collect.Iterables.any;
import static com.google.common.collect.Sets.difference;
import static com.google.common.collect.Sets.union;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.util.CollectionUtils.forceEmptyToNull;
import static google.registry.util.CollectionUtils.nullToEmpty;
import static google.registry.util.CollectionUtils.nullToEmptyImmutableCopy;
import static google.registry.util.CollectionUtils.nullToEmptyImmutableSortedCopy;
import static google.registry.util.CollectionUtils.union;
import static google.registry.util.DomainNameUtils.getTldFromDomainName;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.IgnoreSave;
import com.googlecode.objectify.annotation.Index;
import com.googlecode.objectify.condition.IfNull;
import google.registry.model.EppResource;
import google.registry.model.annotations.ReportedOn;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DesignatedContact.Type;
import google.registry.model.domain.launch.LaunchNotice;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.model.host.HostResource;
import java.util.Set;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlElementWrapper;
import javax.xml.bind.annotation.XmlTransient;

/** Shared base class for {@link DomainResource} and {@link DomainApplication}. */
@XmlTransient
@ReportedOn
@Entity
public abstract class DomainBase extends EppResource {

  /**
   * Fully qualified domain name (puny-coded), which serves as the foreign key for this domain.
   *
   * <p>This is only unique in the sense that for any given lifetime specified as the time range
   * from (creationTime, deletionTime) there can only be one domain in the datastore with this name.
   * However, there can be many domains with the same name and non-overlapping lifetimes.
   *
   * @invariant fullyQualifiedDomainName == fullyQualifiedDomainName.toLowerCase()
   */
  @Index
  @XmlElement(name = "name")
  String fullyQualifiedDomainName;

  /** The top level domain this is under, dernormalized from {@link #fullyQualifiedDomainName}. */
  @Index
  @XmlTransient
  String tld;

  /** References to hosts that are the nameservers for the domain. */
  @Index
  @XmlTransient
  Set<Key<HostResource>> nsHosts;

  /**
   * The union of the contacts visible via {@link #getContacts} and {@link #getRegistrant}.
   *
   * <p>These are stored in one field so that we can query across all contacts at once.
   */
  @XmlTransient
  Set<DesignatedContact> allContacts;

  /** Authorization info (aka transfer secret) of the domain. */
  DomainAuthInfo authInfo;

  /**
   * Data used to construct DS records for this domain.
   *
   * <p>This is {@literal @}XmlTransient because it needs to be returned under the "extension" tag
   * of an info response rather than inside the "infData" tag.
   */
  @XmlTransient
  Set<DelegationSignerData> dsData;

  /**
   * The claims notice supplied when this application or domain was created, if there was one. It's
   * {@literal @}XmlTransient because it's not returned in an info response.
   */
  @IgnoreSave(IfNull.class)
  @XmlTransient
  LaunchNotice launchNotice;

  /**
   * Name of first IDN table associated with TLD that matched the characters in this domain label.
   *
   * @see google.registry.tldconfig.idn.IdnLabelValidator#findValidIdnTableForTld
   */
  @IgnoreSave(IfNull.class)
  @XmlTransient
  String idnTableName;

  /**
   * Synchronously load all referenced contacts and hosts into the Objectify session cache.
   *
   * <p>This saves an extra datastore roundtrip on marshalling, since contacts, hosts, and the
   * registrant will all be in the session cache when their respective methods are called.
   */
  private void preMarshal() {
    // Calling values() blocks until loading is done.
    ofy().load().values(union(getNameservers(), getReferencedContacts())).values();
  }

  /** JAXB java beans property to marshal nameserver hostnames. */
  @XmlElementWrapper(name = "ns")
  @XmlElement(name = "hostObj")
  private ImmutableSet<String> getMarshalledNameservers() {
    preMarshal();
    // If there are no nameservers we must return null, or an empty "ns" element will be marshalled.
    return forceEmptyToNull(loadNameserverFullyQualifiedHostNames());
  }

  /** JAXB java beans property to marshal non-registrant contact ids. */
  @XmlElement(name = "contact")
  private ImmutableSet<ForeignKeyedDesignatedContact> getMarshalledContacts() {
    preMarshal();
    return FluentIterable.from(getContacts())
        .transform(
            new Function<DesignatedContact, ForeignKeyedDesignatedContact>() {
              @Override
              public ForeignKeyedDesignatedContact apply(DesignatedContact designated) {
                return ForeignKeyedDesignatedContact.create(
                    designated.getType(),
                    ofy().load().key(designated.getContactKey()).now().getContactId());
              }})
        .toSet();
  }

  /** JAXB java beans property to marshal nameserver hostnames. */
  @XmlElement(name = "registrant")
  private String getMarshalledRegistrant() {
    preMarshal();
    return ofy().load().key(getRegistrant()).now().getContactId();
  }

  public String getFullyQualifiedDomainName() {
    return fullyQualifiedDomainName;
  }

  public ImmutableSortedSet<DelegationSignerData> getDsData() {
    return nullToEmptyImmutableSortedCopy(dsData);
  }

  public LaunchNotice getLaunchNotice() {
    return launchNotice;
  }

  public String getIdnTableName() {
    return idnTableName;
  }

  public ImmutableSet<Key<HostResource>> getNameservers() {
    return nullToEmptyImmutableCopy(nsHosts);
  }

  /** Loads and returns the fully qualified host names of all linked nameservers. */
  public ImmutableSortedSet<String> loadNameserverFullyQualifiedHostNames() {
    return FluentIterable.from(ofy().load().keys(getNameservers()).values())
        .transform(
            new Function<HostResource, String>() {
              @Override
              public String apply(HostResource host) {
                return host.getFullyQualifiedHostName();
              }
            })
        .toSortedSet(Ordering.natural());
  }

  /** A key to the registrant who registered this domain. */
  public Key<ContactResource> getRegistrant() {
    return FluentIterable
        .from(nullToEmpty(allContacts))
        .filter(IS_REGISTRANT)
        .first()
        .get()
        .getContactKey();
  }

  /** Associated contacts for the domain (other than registrant). */
  public ImmutableSet<DesignatedContact> getContacts() {
    return FluentIterable
        .from(nullToEmpty(allContacts))
        .filter(not(IS_REGISTRANT))
        .toSet();
  }

  public DomainAuthInfo getAuthInfo() {
    return authInfo;
  }

  /** Returns all referenced contacts from this domain or application. */
  public ImmutableSet<Key<ContactResource>> getReferencedContacts() {
    ImmutableSet.Builder<Key<ContactResource>> contactsBuilder =
        new ImmutableSet.Builder<>();
    for (DesignatedContact designated : nullToEmptyImmutableCopy(allContacts)) {
      contactsBuilder.add(designated.getContactKey());
    }
    return contactsBuilder.build();
  }

  public String getTld() {
    return tld;
  }

  /** Predicate to determine if a given {@link DesignatedContact} is the registrant. */
  private static final Predicate<DesignatedContact> IS_REGISTRANT =
      new Predicate<DesignatedContact>() {
        @Override
        public boolean apply(DesignatedContact contact) {
          return DesignatedContact.Type.REGISTRANT.equals(contact.type);
        }};

  /** An override of {@link EppResource#asBuilder} with tighter typing. */
  @Override
  public abstract Builder<?, ?> asBuilder();

  /** A builder for constructing {@link DomainBase}, since it is immutable. */
  public abstract static class Builder<T extends DomainBase, B extends Builder<?, ?>>
      extends EppResource.Builder<T, B> {

    protected Builder() {}

    protected Builder(T instance) {
      super(instance);
    }

    @Override
    public T build() {
      T instance = getInstance();
      checkArgumentNotNull(
          emptyToNull(instance.fullyQualifiedDomainName), "Missing fullyQualifiedDomainName");
      checkArgument(any(instance.allContacts, IS_REGISTRANT), "Missing registrant");
      instance.tld = getTldFromDomainName(instance.fullyQualifiedDomainName);
      return super.build();
    }

    public B setFullyQualifiedDomainName(String fullyQualifiedDomainName) {
      getInstance().fullyQualifiedDomainName = fullyQualifiedDomainName;
      return thisCastToDerived();
    }

    public B setDsData(ImmutableSet<DelegationSignerData> dsData) {
      getInstance().dsData = dsData;
      return thisCastToDerived();
    }

    public B setRegistrant(Key<ContactResource> registrant) {
      // Replace the registrant contact inside allContacts.
      getInstance().allContacts = union(
          getInstance().getContacts(),
          DesignatedContact.create(Type.REGISTRANT, checkArgumentNotNull(registrant)));
      return thisCastToDerived();
    }

    public B setAuthInfo(DomainAuthInfo authInfo) {
      getInstance().authInfo = authInfo;
      return thisCastToDerived();
    }

    public B setNameservers(ImmutableSet<Key<HostResource>> nameservers) {
      getInstance().nsHosts = nameservers;
      return thisCastToDerived();
    }

    public B addNameservers(ImmutableSet<Key<HostResource>> nameservers) {
      return setNameservers(
          ImmutableSet.copyOf(union(getInstance().getNameservers(), nameservers)));
    }

    public B removeNameservers(ImmutableSet<Key<HostResource>> nameservers) {
      return setNameservers(
          ImmutableSet.copyOf(difference(getInstance().getNameservers(), nameservers)));
    }

    public B setContacts(ImmutableSet<DesignatedContact> contacts) {
      checkArgument(all(contacts, not(IS_REGISTRANT)), "Registrant cannot be a contact");
      // Replace the non-registrant contacts inside allContacts.
      getInstance().allContacts = FluentIterable
          .from(nullToEmpty(getInstance().allContacts))
          .filter(IS_REGISTRANT)
          .append(contacts)
          .toSet();
      return thisCastToDerived();
    }

    public B addContacts(ImmutableSet<DesignatedContact> contacts) {
      return setContacts(ImmutableSet.copyOf(union(getInstance().getContacts(), contacts)));
    }

    public B removeContacts(ImmutableSet<DesignatedContact> contacts) {
      return setContacts(ImmutableSet.copyOf(difference(getInstance().getContacts(), contacts)));
    }

    public B setLaunchNotice(LaunchNotice launchNotice) {
      getInstance().launchNotice = launchNotice;
      return thisCastToDerived();
    }

    public B setIdnTableName(String idnTableName) {
      getInstance().idnTableName = idnTableName;
      return thisCastToDerived();
    }
  }
}
