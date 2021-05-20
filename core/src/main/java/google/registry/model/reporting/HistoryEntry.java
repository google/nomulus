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

package google.registry.model.reporting;

import static com.google.common.base.Preconditions.checkArgument;
import static com.googlecode.objectify.Key.getKind;
import static google.registry.util.CollectionUtils.nullToEmptyImmutableCopy;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.IgnoreSave;
import com.googlecode.objectify.annotation.Index;
import com.googlecode.objectify.annotation.Parent;
import com.googlecode.objectify.condition.IfNull;
import google.registry.model.Buildable;
import google.registry.model.EppResource;
import google.registry.model.ImmutableObject;
import google.registry.model.annotations.ReportedOn;
import google.registry.model.contact.ContactBase;
import google.registry.model.contact.ContactHistory;
import google.registry.model.contact.ContactHistory.ContactHistoryId;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainContent;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.DomainHistory.DomainHistoryId;
import google.registry.model.domain.Period;
import google.registry.model.eppcommon.Trid;
import google.registry.model.host.HostBase;
import google.registry.model.host.HostHistory;
import google.registry.model.host.HostHistory.HostHistoryId;
import google.registry.model.host.HostResource;
import google.registry.persistence.VKey;
import google.registry.schema.replay.DatastoreEntity;
import google.registry.schema.replay.SqlEntity;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import javax.persistence.Access;
import javax.persistence.AccessType;
import javax.persistence.AttributeOverride;
import javax.persistence.AttributeOverrides;
import javax.persistence.Column;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.MappedSuperclass;
import javax.persistence.Transient;
import org.joda.time.DateTime;

/**
 * A record of an EPP command that mutated a resource.
 *
 * <p>Due to historical reasons this class is persisted only to Datastore. It has three subclasses
 * that include the parent resource itself which are persisted to Cloud SQL. During migration this
 * class cannot be made abstract in order for the class to be persisted and loaded to and from
 * Datastore. However it should never be used directly in the Java code itself. When it is loaded
 * from Datastore it should be converted to a subclass for handling and when a new history entry is
 * built it should always be a subclass, which is automatically converted to HistoryEntry when
 * persisting to Datastore.
 *
 * <p>Some care has been taken to make it close to impossible to use this class directly, but the
 * user should still exercise caution. After the migration is complete this class will be made
 * abstract.
 */
@ReportedOn
@Entity
@MappedSuperclass
@Access(AccessType.FIELD)
public class HistoryEntry extends ImmutableObject implements Buildable, DatastoreEntity {

  /** Represents the type of history entry. */
  public enum Type {
    CONTACT_CREATE,
    CONTACT_DELETE,
    CONTACT_DELETE_FAILURE,
    CONTACT_PENDING_DELETE,
    CONTACT_TRANSFER_APPROVE,
    CONTACT_TRANSFER_CANCEL,
    CONTACT_TRANSFER_REJECT,
    CONTACT_TRANSFER_REQUEST,
    CONTACT_UPDATE,
    /**
     * Used for history entries that were allocated as a result of a domain application.
     *
     * <p>Domain applications (and thus allocating from an application) no longer exist, but we have
     * existing domains in the system that were created via allocation and thus have history entries
     * of this type under them, so this is retained for legacy purposes.
     */
    @Deprecated
    DOMAIN_ALLOCATE,
    /**
     * Used for domain registration autorenews explicitly logged by {@link
     * google.registry.batch.ExpandRecurringBillingEventsAction}.
     */
    DOMAIN_AUTORENEW,
    DOMAIN_CREATE,
    DOMAIN_DELETE,
    DOMAIN_RENEW,
    DOMAIN_RESTORE,
    DOMAIN_TRANSFER_APPROVE,
    DOMAIN_TRANSFER_CANCEL,
    DOMAIN_TRANSFER_REJECT,
    DOMAIN_TRANSFER_REQUEST,
    DOMAIN_UPDATE,
    HOST_CREATE,
    HOST_DELETE,
    HOST_DELETE_FAILURE,
    HOST_PENDING_DELETE,
    HOST_UPDATE,
    /** Resource was created by an escrow file import. */
    RDE_IMPORT,
    /**
     * A synthetic history entry created by a tool or back-end migration script outside of the scope
     * of usual EPP flows. These are sometimes needed to serve as parents for billing events or poll
     * messages that otherwise wouldn't have a suitable parent.
     */
    SYNTHETIC
  }

  /**
   * The autogenerated id of this event. Note that, this field is marked as {@link Transient} in the
   * SQL schema, this is because the child class of {@link HistoryEntry}, e.g. {@link
   * DomainHistory}, uses a composite primary key which the id is part of, and Hibernate requires
   * that all the {@link javax.persistence.Id} fields must be put in the exact same class.
   */
  @Id @Transient @VisibleForTesting public Long id;

  /** The resource this event mutated. */
  @Parent @Transient protected Key<? extends EppResource> parent;

  /** The type of history entry. */
  @Column(nullable = false, name = "historyType")
  @Enumerated(EnumType.STRING)
  Type type;

  /**
   * The length of time that a create, allocate, renewal, or transfer request was issued for. Will
   * be null for all other types.
   */
  @IgnoreSave(IfNull.class)
  @Transient // domain-specific
  Period period;

  /**
   * The actual EPP xml of the command, stored as bytes to be agnostic of encoding.
   *
   * <p>Changes performed by backend actions would not have EPP requests to store.
   */
  @Column(name = "historyXmlBytes")
  byte[] xmlBytes;

  /** The time the command occurred, represented by the ofy transaction time. */
  @Index
  @Column(nullable = false, name = "historyModificationTime")
  DateTime modificationTime;

  /** The id of the registrar that sent the command. */
  @Index
  @Column(name = "historyRegistrarId")
  String clientId;

  /**
   * For transfers, the id of the other registrar.
   *
   * <p>For requests and cancels, the other registrar is the losing party (because the registrar
   * sending the EPP transfer command is the gaining party). For approves and rejects, the other
   * registrar is the gaining party.
   */
  @Transient // domain-specific
  String otherClientId;

  /** Transaction id that made this change, or null if the entry was not created by a flow. */
  @Nullable
  @AttributeOverrides({
    @AttributeOverride(
        name = "clientTransactionId",
        column = @Column(name = "historyClientTransactionId")),
    @AttributeOverride(
        name = "serverTransactionId",
        column = @Column(name = "historyServerTransactionId"))
  })
  Trid trid;

  /** Whether this change was created by a superuser. */
  @Column(nullable = false, name = "historyBySuperuser")
  boolean bySuperuser;

  /** Reason for the change. */
  @Column(name = "historyReason")
  String reason;

  /** Whether this change was requested by a registrar. */
  @Column(name = "historyRequestedByRegistrar")
  Boolean requestedByRegistrar;

  /**
   * Logging field for transaction reporting.
   *
   * <p>This will be empty for any HistoryEntry generated before this field was added. This will
   * also be empty if the HistoryEntry refers to an EPP mutation that does not affect domain
   * transaction counts (such as contact or host mutations).
   */
  @Transient // domain-specific
  @ImmutableObject.EmptySetToNull
  protected Set<DomainTransactionRecord> domainTransactionRecords;

  // Make it impossible to instantiate a HistoryEntry explicitly. One should only instantiate a
  // subtype of HistoryEntry.
  protected HistoryEntry() {
    super();
  }

  public long getId() {
    // For some reason, Hibernate throws NPE during some initialization phase if we don't deal with
    // the null case. Setting the id to 0L when it is null should be fine because 0L for primitive
    // type is considered as null for wrapper class in the Hibernate context.
    return id == null ? 0L : id;
  }

  /** This method exists solely to satisfy Hibernate. Use the {@link Builder} instead. */
  @SuppressWarnings("UnusedMethod")
  private void setId(long id) {
    this.id = id;
  }

  public Key<? extends EppResource> getParent() {
    return parent;
  }

  public Type getType() {
    return type;
  }

  public Period getPeriod() {
    return period;
  }

  public byte[] getXmlBytes() {
    return xmlBytes;
  }

  public DateTime getModificationTime() {
    return modificationTime;
  }

  public String getClientId() {
    return clientId;
  }

  public String getOtherClientId() {
    return otherClientId;
  }

  /** Returns the TRID, which may be null if the entry was not created by a normal flow. */
  @Nullable
  public Trid getTrid() {
    return trid;
  }

  public boolean getBySuperuser() {
    return bySuperuser;
  }

  public String getReason() {
    return reason;
  }

  public Boolean getRequestedByRegistrar() {
    return requestedByRegistrar;
  }

  public Set<DomainTransactionRecord> getDomainTransactionRecords() {
    return nullToEmptyImmutableCopy(domainTransactionRecords);
  }

  /** This method exists solely to satisfy Hibernate. Use the {@link Builder} instead. */
  @SuppressWarnings("UnusedMethod")
  private void setPeriod(Period period) {
    this.period = period;
  }

  /** This method exists solely to satisfy Hibernate. Use the {@link Builder} instead. */
  @SuppressWarnings("UnusedMethod")
  private void setOtherRegistrarId(String otherRegistrarId) {
    this.otherClientId = otherRegistrarId;
  }

  /** This method exists solely to satisfy Hibernate. Use the {@link Builder} instead. */
  @SuppressWarnings("UnusedMethod")
  private void setDomainTransactionRecords(Set<DomainTransactionRecord> domainTransactionRecords) {
    this.domainTransactionRecords =
        domainTransactionRecords == null ? null : ImmutableSet.copyOf(domainTransactionRecords);
  }

  /**
   * Throws an error when trying to get a builder from a bare {@link HistoryEntry}.
   *
   * <p>This method only exists to satisfy the requirement that the {@link HistoryEntry} is NOT
   * abstract, it should never be called directly and all three of the subclass of {@link
   * HistoryEntry} implements it.
   */
  @Override
  public Builder<? extends HistoryEntry, ?> asBuilder() {
    throw new UnsupportedOperationException(
        "You should never attempt to build a HistoryEntry from a raw HistoryEntry. A raw "
            + "HistoryEntry should only exist internally when persisting to datastore. If you need "
            + "to build from a raw HistoryEntry, use "
            + "{Contact,Host,Domain}History.Builder.copyFrom(HistoryEntry) instead.");
  }

  /**
   * Clones and returns a {@code HistoryEntry} objec
   *
   * <p>This is useful when converting a subclass to the base class to persist to Datastore.
   */
  public HistoryEntry asHistoryEntry() {
    HistoryEntry historyEntry = new HistoryEntry();
    copy(this, historyEntry);
    return historyEntry;
  }

  protected static void copy(HistoryEntry src, HistoryEntry dst) {
    dst.id = src.id;
    dst.parent = src.parent;
    dst.type = src.type;
    dst.period = src.period;
    dst.xmlBytes = src.xmlBytes;
    dst.modificationTime = src.modificationTime;
    dst.clientId = src.clientId;
    dst.otherClientId = src.otherClientId;
    dst.trid = src.trid;
    dst.bySuperuser = src.bySuperuser;
    dst.reason = src.reason;
    dst.requestedByRegistrar = src.requestedByRegistrar;
    dst.domainTransactionRecords =
        src.domainTransactionRecords == null
            ? null
            : ImmutableSet.copyOf(src.domainTransactionRecords);
  }

  @SuppressWarnings("unchecked")
  public HistoryEntry toChildHistoryEntity() {
    String parentKind = getParent().getKind();
    final HistoryEntry resultEntity;
    // can't use a switch statement since we're calling getKind()
    if (parentKind.equals(getKind(DomainBase.class))) {
      resultEntity =
          new DomainHistory.Builder().copyFrom(this).setDomainRepoId(parent.getName()).build();
    } else if (parentKind.equals(getKind(HostResource.class))) {
      resultEntity =
          new HostHistory.Builder().copyFrom(this).setHostRepoId(parent.getName()).build();
    } else if (parentKind.equals(getKind(ContactResource.class))) {
      resultEntity =
          new ContactHistory.Builder().copyFrom(this).setContactRepoId(parent.getName()).build();
    } else {
      throw new IllegalStateException(
          String.format("Unknown kind of HistoryEntry parent %s", parentKind));
    }
    return resultEntity;
  }

  // In SQL, save the child type
  @Override
  public Optional<SqlEntity> toSqlEntity() {
    return Optional.of((SqlEntity) toChildHistoryEntity());
  }

  /** Creates a {@link VKey} instance from a {@link Key} instance. */
  public static VKey<? extends HistoryEntry> createVKey(Key<HistoryEntry> key) {
    String repoId = key.getParent().getName();
    long id = key.getId();
    Key<EppResource> parent = key.getParent();
    String parentKind = parent.getKind();
    if (parentKind.equals(getKind(DomainBase.class))) {
      return VKey.create(
          DomainHistory.class,
          new DomainHistoryId(repoId, id),
          Key.create(parent, DomainHistory.class, id));
    } else if (parentKind.equals(getKind(HostResource.class))) {
      return VKey.create(
          HostHistory.class,
          new HostHistoryId(repoId, id),
          Key.create(parent, HostHistory.class, id));
    } else if (parentKind.equals(getKind(ContactResource.class))) {
      return VKey.create(
          ContactHistory.class,
          new ContactHistoryId(repoId, id),
          Key.create(parent, ContactHistory.class, id));
    } else {
      throw new IllegalStateException(
          String.format("Unknown kind of HistoryEntry parent %s", parentKind));
    }
  }

  /** A builder for {@link HistoryEntry} since it is immutable */
  public abstract static class Builder<T extends HistoryEntry, B extends Builder<?, ?>>
      extends GenericBuilder<T, B> {
    protected Builder() {}

    protected Builder(T instance) {
      super(instance);
    }

    // Used to fill out the fields in this object from an object which may not be exactly the same
    // as the class T, where both classes still subclass HistoryEntry
    public B copyFrom(HistoryEntry historyEntry) {
      copy(historyEntry, getInstance());
      return thisCastToDerived();
    }

    public B copyFrom(HistoryEntry.Builder<? extends HistoryEntry, ?> builder) {
      return copyFrom(builder.getInstance());
    }

    @Override
    public T build() {
      // TODO(mcilwain): Add null checking for id/parent once DB migration is complete.
      checkArgumentNotNull(getInstance().type, "History entry type must be specified");
      checkArgumentNotNull(getInstance().modificationTime, "Modification time must be specified");
      checkArgumentNotNull(getInstance().clientId, "Registrar ID must be specified");
      checkArgument(
          !getInstance().type.equals(Type.SYNTHETIC) || !getInstance().requestedByRegistrar,
          "Synthetic history entries cannot be requested by a registrar");
      return super.build();
    }

    public B setId(Long id) {
      getInstance().id = id;
      return thisCastToDerived();
    }

    protected B setParent(EppResource parent) {
      getInstance().parent = Key.create(parent);
      return thisCastToDerived();
    }

    // Until we move completely to SQL, override this in subclasses (e.g. HostHistory) to set VKeys
    protected B setParent(Key<? extends EppResource> parent) {
      getInstance().parent = parent;
      return thisCastToDerived();
    }

    public B setType(Type type) {
      getInstance().type = type;
      return thisCastToDerived();
    }

    public B setPeriod(Period period) {
      getInstance().period = period;
      return thisCastToDerived();
    }

    public B setXmlBytes(byte[] xmlBytes) {
      getInstance().xmlBytes = xmlBytes;
      return thisCastToDerived();
    }

    public B setModificationTime(DateTime modificationTime) {
      getInstance().modificationTime = modificationTime;
      return thisCastToDerived();
    }

    public B setClientId(String clientId) {
      getInstance().clientId = clientId;
      return thisCastToDerived();
    }

    public B setOtherClientId(String otherClientId) {
      getInstance().otherClientId = otherClientId;
      return thisCastToDerived();
    }

    public B setTrid(Trid trid) {
      getInstance().trid = trid;
      return thisCastToDerived();
    }

    public B setBySuperuser(boolean bySuperuser) {
      getInstance().bySuperuser = bySuperuser;
      return thisCastToDerived();
    }

    public B setReason(String reason) {
      getInstance().reason = reason;
      return thisCastToDerived();
    }

    public B setRequestedByRegistrar(Boolean requestedByRegistrar) {
      getInstance().requestedByRegistrar = requestedByRegistrar;
      return thisCastToDerived();
    }

    public B setDomainTransactionRecords(
        ImmutableSet<DomainTransactionRecord> domainTransactionRecords) {
      getInstance().domainTransactionRecords = domainTransactionRecords;
      return thisCastToDerived();
    }
  }

  public static <E extends EppResource>
      HistoryEntry.Builder<? extends HistoryEntry, ?> createBuilderForResource(E parent) {
    if (parent instanceof DomainContent) {
      return new DomainHistory.Builder().setDomain((DomainContent) parent);
    } else if (parent instanceof ContactBase) {
      return new ContactHistory.Builder().setContact((ContactBase) parent);
    } else if (parent instanceof HostBase) {
      return new HostHistory.Builder().setHost((HostBase) parent);
    } else {
      throw new IllegalStateException(
          String.format(
              "Class %s does not have an associated HistoryEntry", parent.getClass().getName()));
    }
  }
}
