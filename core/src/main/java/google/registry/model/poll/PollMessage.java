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

package google.registry.model.poll;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.util.CollectionUtils.forceEmptyToNull;
import static google.registry.util.CollectionUtils.isNullOrEmpty;
import static google.registry.util.CollectionUtils.nullToEmpty;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.google.common.collect.ImmutableList;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.EntitySubclass;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Ignore;
import com.googlecode.objectify.annotation.Index;
import com.googlecode.objectify.annotation.OnLoad;
import com.googlecode.objectify.annotation.Parent;
import google.registry.model.Buildable;
import google.registry.model.EppResource;
import google.registry.model.ImmutableObject;
import google.registry.model.annotations.ExternalMessagingName;
import google.registry.model.annotations.ReportedOn;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainRenewData;
import google.registry.model.eppoutput.EppResponse.ResponseData;
import google.registry.model.host.HostResource;
import google.registry.model.poll.PendingActionNotificationResponse.ContactPendingActionNotificationResponse;
import google.registry.model.poll.PendingActionNotificationResponse.DomainPendingActionNotificationResponse;
import google.registry.model.poll.PendingActionNotificationResponse.HostPendingActionNotificationResponse;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.transfer.TransferData.TransferServerApproveEntity;
import google.registry.model.transfer.TransferResponse;
import google.registry.model.transfer.TransferResponse.ContactTransferResponse;
import google.registry.model.transfer.TransferResponse.DomainTransferResponse;
import google.registry.persistence.VKey;
import google.registry.persistence.WithLongVKey;
import google.registry.schema.replay.DatastoreAndSqlEntity;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.persistence.AttributeOverride;
import javax.persistence.AttributeOverrides;
import javax.persistence.Column;
import javax.persistence.DiscriminatorColumn;
import javax.persistence.DiscriminatorValue;
import javax.persistence.Embedded;
import javax.persistence.Inheritance;
import javax.persistence.InheritanceType;
import javax.persistence.PostLoad;
import javax.persistence.Transient;
import org.joda.time.DateTime;

/**
 * A poll message that is pending for a registrar.
 *
 * <p>Poll messages are not delivered until their {@link #eventTime} has passed. Poll messages can
 * be speculatively enqueued for future delivery, and then modified or deleted before that date has
 * passed. Unlike most other entities in Datastore, which are marked as deleted but otherwise
 * retained for historical purposes, poll messages are truly deleted once they have been delivered
 * and ACKed.
 *
 * <p>Poll messages are parented off of the {@link HistoryEntry} that resulted in their creation.
 * This means that poll messages are contained in the Datastore entity group of the parent {@link
 * EppResource} (which can be a domain, contact, or host). It is thus possible to perform a strongly
 * consistent query to find all poll messages associated with a given EPP resource.
 *
 * <p>Poll messages are identified externally by registrars using the format defined in {@link
 * PollMessageExternalKeyConverter}.
 *
 * @see <a href="https://tools.ietf.org/html/rfc5730#section-2.9.2.3">RFC5730 - EPP - &lt;poll&gt;
 *     Command</a>
 */
@Entity
@ReportedOn
@ExternalMessagingName("message")
@javax.persistence.Entity
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "type")
@javax.persistence.Table(
    indexes = {
      @javax.persistence.Index(columnList = "registrar_id"),
      @javax.persistence.Index(columnList = "eventTime")
    })
public abstract class PollMessage extends ImmutableObject
    implements Buildable, DatastoreAndSqlEntity, TransferServerApproveEntity {

  /** Entity id. */
  @Id
  @javax.persistence.Id
  @Column(name = "poll_message_id")
  Long id;

  @Parent @DoNotHydrate @Transient Key<HistoryEntry> parent;

  /** The registrar that this poll message will be delivered to. */
  @Index
  @Column(name = "registrar_id", nullable = false)
  String clientId;

  /** The time when the poll message should be delivered. May be in the future. */
  @Index
  @Column(nullable = false)
  DateTime eventTime;

  /** Human readable message that will be returned with this poll message. */
  @Column(name = "message")
  String msg;

  @Ignore String domainRepoId;

  @Ignore String contactRepoId;

  @Ignore String hostRepoId;

  @Ignore Long domainHistoryRevisionId;

  @Ignore Long contactHistoryRevisionId;

  @Ignore Long hostHistoryRevisionId;

  public Key<HistoryEntry> getParentKey() {
    return parent;
  }

  public Long getId() {
    return id;
  }

  public String getClientId() {
    return clientId;
  }

  public DateTime getEventTime() {
    return eventTime;
  }

  public String getMsg() {
    return msg;
  }

  public abstract ImmutableList<ResponseData> getResponseData();

  @PostLoad
  void postLoad() {
    if (domainRepoId != null) {
      parent =
          Key.create(
              Key.create(DomainBase.class, domainRepoId),
              HistoryEntry.class,
              domainHistoryRevisionId);
    } else if (contactRepoId != null) {
      parent =
          Key.create(
              Key.create(ContactResource.class, contactRepoId),
              HistoryEntry.class,
              contactHistoryRevisionId);
    } else if (hostHistoryRevisionId != null) {
      parent =
          Key.create(
              Key.create(HostResource.class, hostRepoId),
              HistoryEntry.class,
              hostHistoryRevisionId);
    }
  }

  @OnLoad
  void onLoad() {
    setSqlForeignKeys(this);
  }

  @Override
  public abstract VKey<? extends PollMessage> createVKey();

  /** Static VKey factory method for use by VKeyTranslatorFactory. */
  public static VKey<PollMessage> createVKey(Key<PollMessage> key) {
    return VKey.create(PollMessage.class, key.getId(), key);
  }

  /** Override Buildable.asBuilder() to give this method stronger typing. */
  @Override
  public abstract Builder<?, ?> asBuilder();

  /** Builder for {@link PollMessage} because it is immutable. */
  public abstract static class Builder<T extends PollMessage, B extends Builder<?, ?>>
      extends GenericBuilder<T, B> {

    protected Builder() {}

    protected Builder(T instance) {
      super(instance);
    }

    /**
     * Manually set the ID for testing or in special circumstances.
     *
     * <p>In general the ID is auto-created, and there should be no need to set it manually.
     *
     * <p>This is only here for testing and for one special situation in which we're making a new
     * poll message to replace an existing one, so it has to have the same ID.
     */
    public B setId(Long id) {
      getInstance().id = id;
      return thisCastToDerived();
    }

    public B setClientId(String clientId) {
      getInstance().clientId = clientId;
      return thisCastToDerived();
    }

    public B setEventTime(DateTime eventTime) {
      getInstance().eventTime = eventTime;
      return thisCastToDerived();
    }

    public B setMsg(String msg) {
      getInstance().msg = msg;
      return thisCastToDerived();
    }

    public B setParent(HistoryEntry parent) {
      getInstance().parent = Key.create(parent);
      return thisCastToDerived();
    }

    public B setParentKey(Key<HistoryEntry> parentKey) {
      getInstance().parent = parentKey;
      return thisCastToDerived();
    }

    @Override
    public T build() {
      T instance = getInstance();
      checkArgumentNotNull(instance.clientId, "clientId must be specified");
      checkArgumentNotNull(instance.eventTime, "eventTime must be specified");
      checkArgumentNotNull(instance.parent, "parent must be specified");
      checkArgumentNotNull(instance.parent.getParent(), "parent.getParent() must be specified");
      setSqlForeignKeys(instance);
      return super.build();
    }
  }

  private static void setSqlForeignKeys(PollMessage pollMessage) {
    String grandparentKind = pollMessage.parent.getParent().getKind();
    String repoId = pollMessage.parent.getParent().getName();
    long historyRevisionId = pollMessage.parent.getId();
    if (Key.getKind(DomainBase.class).equals(grandparentKind)) {
      pollMessage.domainRepoId = repoId;
      pollMessage.domainHistoryRevisionId = historyRevisionId;
    } else if (Key.getKind(ContactResource.class).equals(grandparentKind)) {
      pollMessage.contactRepoId = repoId;
      pollMessage.contactHistoryRevisionId = historyRevisionId;
    } else if (Key.getKind(HostResource.class).equals(grandparentKind)) {
      pollMessage.hostRepoId = repoId;
      pollMessage.hostHistoryRevisionId = historyRevisionId;
    } else {
      throw new IllegalArgumentException("Unknown grandparent kind: " + grandparentKind);
    }
  }

  /**
   * A one-time poll message.
   *
   * <p>One-time poll messages are deleted from Datastore once they have been delivered and ACKed.
   */
  @EntitySubclass(index = false)
  @javax.persistence.Entity
  @DiscriminatorValue("ONE_TIME")
  @WithLongVKey(compositeKey = true)
  public static class OneTime extends PollMessage {

    // Response data. Objectify cannot persist a base class type, so we must have a separate field
    // to hold every possible derived type of ResponseData that we might store.
    @Transient
    List<ContactPendingActionNotificationResponse> contactPendingActionNotificationResponses;

    @Transient List<ContactTransferResponse> contactTransferResponses;

    @Transient @ImmutableObject.DoNotCompare
    List<DomainPendingActionNotificationResponse> domainPendingActionNotificationResponses;

    @Transient @ImmutableObject.DoNotCompare List<DomainTransferResponse> domainTransferResponses;

    @Transient List<HostPendingActionNotificationResponse> hostPendingActionNotificationResponses;

    @Ignore
    @Embedded
    @AttributeOverrides({
      @AttributeOverride(
          name = "nameOrId.value",
          column = @Column(name = "pending_action_response_name_or_id")),
      @AttributeOverride(
          name = "nameOrId.actionResult",
          column = @Column(name = "pending_action_response_action_result")),
      @AttributeOverride(
          name = "trid.serverTransactionId",
          column = @Column(name = "pending_action_response_server_txn_id")),
      @AttributeOverride(
          name = "trid.clientTransactionId",
          column = @Column(name = "pending_action_response_client_txn_id")),
      @AttributeOverride(
          name = "processedDate",
          column = @Column(name = "pending_action_response_processed_date"))
    })
    PendingActionNotificationResponse pendingActionNotificationResponse;

    @Ignore
    @Embedded
    @AttributeOverrides({
      @AttributeOverride(
          name = "transferStatus",
          column = @Column(name = "transfer_response_transfer_status")),
      @AttributeOverride(
          name = "gainingClientId",
          column = @Column(name = "transfer_response_gaining_registrar_id")),
      @AttributeOverride(
          name = "transferRequestTime",
          column = @Column(name = "transfer_response_transfer_request_time")),
      @AttributeOverride(
          name = "losingClientId",
          column = @Column(name = "transfer_response_losing_registrar_id")),
      @AttributeOverride(
          name = "pendingTransferExpirationTime",
          column = @Column(name = "transfer_response_pending_transfer_expiration_time"))
    })
    TransferResponse transferResponse;

    @Ignore
    @Column(name = "transfer_response_domain_name")
    String fullyQualifiedDomainName;

    @Ignore
    @Column(name = "transfer_response_domain_expiration_time")
    DateTime extendedRegistrationExpirationTime;

    @Ignore
    @Column(name = "transfer_response_contact_id")
    String contactId;

    @Override
    public VKey<OneTime> createVKey() {
      return VKey.create(OneTime.class, getId(), Key.create(this));
    }

    /** Converts an unspecialized VKey&lt;PollMessage&gt; to a VKey of the derived class. */
    public static @Nullable VKey<OneTime> convertVKey(@Nullable VKey<? extends PollMessage> key) {
      if (key == null) {
        return null;
      }
      Key<OneTime> ofyKey =
          Key.create(key.getOfyKey().getParent(), OneTime.class, key.getOfyKey().getId());
      return VKey.create(OneTime.class, key.getSqlKey(), ofyKey);
    }

    @Override
    public Builder asBuilder() {
      return new Builder(clone(this));
    }

    @Override
    public ImmutableList<ResponseData> getResponseData() {
      return new ImmutableList.Builder<ResponseData>()
          .addAll(nullToEmpty(contactPendingActionNotificationResponses))
          .addAll(nullToEmpty(contactTransferResponses))
          .addAll(nullToEmpty(domainPendingActionNotificationResponses))
          .addAll(nullToEmpty(domainTransferResponses))
          .addAll(nullToEmpty(hostPendingActionNotificationResponses))
          .build();
    }

    @Override
    @OnLoad
    void onLoad() {
      super.onLoad();
      // Take the Objectify-specific fields and map them to the SQL-specific fields, if applicable
      if (!isNullOrEmpty(contactPendingActionNotificationResponses)) {
        pendingActionNotificationResponse = contactPendingActionNotificationResponses.get(0);
      }
      if (!isNullOrEmpty(contactTransferResponses)) {
        contactId = contactTransferResponses.get(0).getContactId();
        transferResponse = contactTransferResponses.get(0);
      }
      if (!isNullOrEmpty(domainPendingActionNotificationResponses)) {
        pendingActionNotificationResponse = domainPendingActionNotificationResponses.get(0);
      }
      if (!isNullOrEmpty(domainTransferResponses)) {
        fullyQualifiedDomainName = domainTransferResponses.get(0).getFullyQualifiedDomainName();
        transferResponse = domainTransferResponses.get(0);
        extendedRegistrationExpirationTime =
            domainTransferResponses.get(0).getExtendedRegistrationExpirationTime();
      }
    }

    @Override
    @PostLoad
    void postLoad() {
      super.postLoad();
      // Take the SQL-specific fields and map them to the Objectify-specific fields, if applicable
      if (pendingActionNotificationResponse != null) {
        if (contactId != null) {
          contactPendingActionNotificationResponses =
              ImmutableList.of(
                  ContactPendingActionNotificationResponse.create(
                      pendingActionNotificationResponse.nameOrId.value,
                      pendingActionNotificationResponse.getActionResult(),
                      pendingActionNotificationResponse.getTrid(),
                      pendingActionNotificationResponse.processedDate));
        } else if (fullyQualifiedDomainName != null) {
          domainPendingActionNotificationResponses =
              ImmutableList.of(
                  DomainPendingActionNotificationResponse.create(
                      pendingActionNotificationResponse.nameOrId.value,
                      pendingActionNotificationResponse.getActionResult(),
                      pendingActionNotificationResponse.getTrid(),
                      pendingActionNotificationResponse.processedDate));
        }
      }
      if (transferResponse != null) {
        // The transferResponse is currently an unspecialized TransferResponse instance, create the
        // appropriate subclass so that the value is consistently specialized
        if (contactId != null) {
          transferResponse =
              new ContactTransferResponse.Builder()
                  .setContactId(contactId)
                  .setGainingClientId(transferResponse.getGainingClientId())
                  .setLosingClientId(transferResponse.getLosingClientId())
                  .setTransferStatus(transferResponse.getTransferStatus())
                  .setTransferRequestTime(transferResponse.getTransferRequestTime())
                  .setPendingTransferExpirationTime(
                      transferResponse.getPendingTransferExpirationTime())
                  .build();
          contactTransferResponses = ImmutableList.of((ContactTransferResponse) transferResponse);
        } else if (fullyQualifiedDomainName != null) {
          transferResponse =
              new DomainTransferResponse.Builder()
                  .setFullyQualifiedDomainName(fullyQualifiedDomainName)
                  .setGainingClientId(transferResponse.getGainingClientId())
                  .setLosingClientId(transferResponse.getLosingClientId())
                  .setTransferStatus(transferResponse.getTransferStatus())
                  .setTransferRequestTime(transferResponse.getTransferRequestTime())
                  .setPendingTransferExpirationTime(
                      transferResponse.getPendingTransferExpirationTime())
                  .setExtendedRegistrationExpirationTime(extendedRegistrationExpirationTime)
                  .build();
          domainTransferResponses = ImmutableList.of((DomainTransferResponse) transferResponse);
        }
      }
    }

    /** A builder for {@link OneTime} since it is immutable. */
    public static class Builder extends PollMessage.Builder<OneTime, Builder> {

      public Builder() {
      }

      private Builder(OneTime instance) {
        super(instance);
      }

      public Builder setResponseData(ImmutableList<? extends ResponseData> responseData) {
        getInstance().contactPendingActionNotificationResponses =
            forceEmptyToNull(
                responseData
                    .stream()
                    .filter(ContactPendingActionNotificationResponse.class::isInstance)
                    .map(ContactPendingActionNotificationResponse.class::cast)
                    .collect(toImmutableList()));

        getInstance().contactTransferResponses =
            forceEmptyToNull(
                responseData
                    .stream()
                    .filter(ContactTransferResponse.class::isInstance)
                    .map(ContactTransferResponse.class::cast)
                    .collect(toImmutableList()));

        getInstance().domainPendingActionNotificationResponses =
            forceEmptyToNull(
                responseData
                    .stream()
                    .filter(DomainPendingActionNotificationResponse.class::isInstance)
                    .map(DomainPendingActionNotificationResponse.class::cast)
                    .collect(toImmutableList()));
        getInstance().domainTransferResponses =
            forceEmptyToNull(
                responseData
                    .stream()
                    .filter(DomainTransferResponse.class::isInstance)
                    .map(DomainTransferResponse.class::cast)
                    .collect(toImmutableList()));

        getInstance().hostPendingActionNotificationResponses =
            forceEmptyToNull(
                responseData.stream()
                    .filter(HostPendingActionNotificationResponse.class::isInstance)
                    .map(HostPendingActionNotificationResponse.class::cast)
                    .collect(toImmutableList()));

        // Set the generic pending-action field as appropriate
        if (getInstance().contactPendingActionNotificationResponses != null) {
          getInstance().pendingActionNotificationResponse =
              getInstance().contactPendingActionNotificationResponses.get(0);
        } else if (getInstance().domainPendingActionNotificationResponses != null) {
          getInstance().pendingActionNotificationResponse =
              getInstance().domainPendingActionNotificationResponses.get(0);
        } else if (getInstance().hostPendingActionNotificationResponses != null) {
          getInstance().pendingActionNotificationResponse =
              getInstance().hostPendingActionNotificationResponses.get(0);
        }
        // Set the generic transfer response field as appropriate
        if (getInstance().contactTransferResponses != null) {
          getInstance().contactId = getInstance().contactTransferResponses.get(0).getContactId();
          getInstance().transferResponse = getInstance().contactTransferResponses.get(0);
        } else if (getInstance().domainTransferResponses != null) {
          getInstance().fullyQualifiedDomainName =
              getInstance().domainTransferResponses.get(0).getFullyQualifiedDomainName();
          getInstance().transferResponse = getInstance().domainTransferResponses.get(0);
          getInstance().extendedRegistrationExpirationTime =
              getInstance().domainTransferResponses.get(0).getExtendedRegistrationExpirationTime();
        }
        return this;
      }
    }
  }

  /**
   * An auto-renew poll message which recurs annually.
   *
   * <p>Auto-renew poll messages are not deleted until the registration of their parent domain has
   * been canceled, because there will always be a speculative renewal for next year until that
   * happens.
   */
  @EntitySubclass(index = false)
  @javax.persistence.Entity
  @DiscriminatorValue("AUTORENEW")
  @WithLongVKey(compositeKey = true)
  public static class Autorenew extends PollMessage {

    /** The target id of the autorenew event. */
    @Column(name = "autorenew_domain_name")
    String targetId;

    /** The autorenew recurs annually between {@link #eventTime} and this time. */
    @Index
    DateTime autorenewEndTime;

    public String getTargetId() {
      return targetId;
    }

    public DateTime getAutorenewEndTime() {
      return autorenewEndTime;
    }

    @Override
    public VKey<Autorenew> createVKey() {
      return VKey.create(Autorenew.class, getId(), Key.create(this));
    }

    /** Converts an unspecialized VKey&lt;PollMessage&gt; to a VKey of the derived class. */
    public static @Nullable VKey<Autorenew> convertVKey(VKey<? extends PollMessage> key) {
      if (key == null) {
        return null;
      }
      Key<Autorenew> ofyKey =
          Key.create(key.getOfyKey().getParent(), Autorenew.class, key.getOfyKey().getId());
      return VKey.create(Autorenew.class, key.getSqlKey(), ofyKey);
    }

    @Override
    public ImmutableList<ResponseData> getResponseData() {
      // Note that the event time is when the auto-renew occured, so the expiration time in the
      // response should be 1 year past that, since it denotes the new expiration time.
      return ImmutableList.of(
          DomainRenewData.create(getTargetId(), getEventTime().plusYears(1)));
    }

    @Override
    public Builder asBuilder() {
      return new Builder(clone(this));
    }

    /** A builder for {@link Autorenew} since it is immutable. */
    public static class Builder extends PollMessage.Builder<Autorenew, Builder> {

      public Builder() {}

      private Builder(Autorenew instance) {
        super(instance);
      }

      public Builder setTargetId(String targetId) {
        getInstance().targetId = targetId;
        return this;
      }

      public Builder setAutorenewEndTime(DateTime autorenewEndTime) {
        getInstance().autorenewEndTime = autorenewEndTime;
        return this;
      }

      @Override
      public Autorenew build() {
        Autorenew instance = getInstance();
        instance.autorenewEndTime =
            Optional.ofNullable(instance.autorenewEndTime).orElse(END_OF_TIME);
        return super.build();
      }
    }
  }
}
