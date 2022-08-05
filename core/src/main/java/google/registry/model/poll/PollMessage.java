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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.google.common.collect.ImmutableList;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.EntitySubclass;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Ignore;
import com.googlecode.objectify.annotation.Index;
import google.registry.model.Buildable;
import google.registry.model.EppResource;
import google.registry.model.ImmutableObject;
import google.registry.model.UnsafeSerializable;
import google.registry.model.annotations.ExternalMessagingName;
import google.registry.model.annotations.ReportedOn;
import google.registry.model.contact.ContactHistory;
import google.registry.model.contact.ContactHistory.ContactHistoryId;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.DomainHistory.DomainHistoryId;
import google.registry.model.domain.DomainRenewData;
import google.registry.model.eppoutput.EppResponse.ResponseData;
import google.registry.model.host.Host;
import google.registry.model.host.HostHistory;
import google.registry.model.host.HostHistory.HostHistoryId;
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
import google.registry.util.NullIgnoringCollectionBuilder;
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
    implements Buildable, TransferServerApproveEntity, UnsafeSerializable {

  /** Indicates the type of entity the poll message is for. */
  public enum Type {
    DOMAIN(1L, Domain.class),
    CONTACT(2L, ContactResource.class),
    HOST(3L, Host.class);

    private final long id;
    private final Class<?> clazz;

    Type(long id, Class<?> clazz) {
      this.id = id;
      this.clazz = clazz;
    }

    /**
     * Returns a numeric id for the enum, which is used as part of an externally published string
     * key for the message.
     */
    public long getId() {
      return id;
    }

    /** Returns the class of the underlying resource for the poll message type. */
    public Class<?> getResourceClass() {
      return clazz;
    }

    /**
     * Returns the type specified by the identifer, {@code Optional.empty()} if the id is out of
     * range.
     */
    public static Optional<Type> fromId(long id) {
      for (Type val : values()) {
        if (val.id == id) {
          return Optional.of(val);
        }
      }

      return Optional.empty();
    }
  }

  /** Entity id. */
  @Id
  @javax.persistence.Id
  @Column(name = "poll_message_id")
  Long id;

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

  // TODO(b/456803336): Replace these fields with {Domain,Contact,Host}HistoryId objects.

  @Ignore String domainRepoId;

  @Ignore String contactRepoId;

  @Ignore String hostRepoId;

  @Ignore Long domainHistoryRevisionId;

  @Ignore Long contactHistoryRevisionId;

  @Ignore Long hostHistoryRevisionId;

  public Long getId() {
    return id;
  }

  public String getRegistrarId() {
    return clientId;
  }

  public DateTime getEventTime() {
    return eventTime;
  }

  public String getMsg() {
    return msg;
  }

  /**
   * Returns the domain repo id.
   *
   * <p>This may only be used on a Domain poll event.
   */
  public String getDomainRepoId() {
    checkArgument(getType() == Type.DOMAIN);
    return domainRepoId;
  }

  /**
   * Returns the contact repo id.
   *
   * <p>This may only be used on a ContactResource poll event.
   */
  public String getContactRepoId() {
    checkArgument(getType() == Type.CONTACT);
    return contactRepoId;
  }

  /**
   * Returns the host repo id.
   *
   * <p>This may only be used on a Host poll event.
   */
  public String getHostRepoId() {
    checkArgument(getType() == Type.DOMAIN);
    return hostRepoId;
  }

  /**
   * Gets the name of the underlying resource that the PollMessage is for, regardless of the type of
   * the resource.
   */
  public String getResourceName() {
    return domainRepoId != null
        ? domainRepoId
        : (contactRepoId != null ? contactRepoId : hostRepoId);
  }

  /** Gets the underlying history revision id, regardless of the type of the resource. */
  public Long getHistoryRevisionId() {
    return domainHistoryRevisionId != null
        ? domainHistoryRevisionId
        : (contactHistoryRevisionId != null ? contactHistoryRevisionId : hostHistoryRevisionId);
  }

  public Type getType() {
    return domainRepoId != null ? Type.DOMAIN : (contactRepoId != null ? Type.CONTACT : Type.HOST);
  }

  public abstract ImmutableList<ResponseData> getResponseData();

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

    public B setRegistrarId(String registrarId) {
      getInstance().clientId = registrarId;
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

    public B setDomainHistoryId(DomainHistoryId historyId) {
      getInstance().domainRepoId = historyId.getDomainRepoId();
      getInstance().domainHistoryRevisionId = historyId.getId();
      return thisCastToDerived();
    }

    public B setContactHistoryId(ContactHistoryId historyId) {
      getInstance().contactRepoId = historyId.getContactRepoId();
      getInstance().contactHistoryRevisionId = historyId.getId();
      return thisCastToDerived();
    }

    public B setHostHistoryId(HostHistoryId historyId) {
      getInstance().hostRepoId = historyId.getHostRepoId();
      getInstance().hostHistoryRevisionId = historyId.getId();
      return thisCastToDerived();
    }

    public B setHistoryEntry(HistoryEntry history) {
      // Set the appropriate field based on the history entry type.
      if (history instanceof DomainHistory) {
        return setDomainHistoryId(((DomainHistory) history).getDomainHistoryId());
      } else if (history instanceof ContactHistory) {
        return setContactHistoryId(((ContactHistory) history).getContactHistoryId());
      } else if (history instanceof HostHistory) {
        return setHostHistoryId(((HostHistory) history).getHostHistoryId());
      }
      return thisCastToDerived();
    }

    /**
     * Given an array containing pairs of objects, verifies that both members of exactly one of the
     * pairs is non-null.
     */
    private boolean exactlyOnePairNonNull(Object... pairs) {
      int count = 0;
      checkArgument(pairs.length % 2 == 0, "Odd number of arguments provided.");
      for (int i = 0; i < pairs.length; i += 2) {
        // Add the number of non-null elements of each pair, after which the count should either be
        // zero (a non-null pair hasn't been found yet) or two (exactly one non-null pair has been
        // found).
        count += (pairs[i] != null ? 1 : 0) + (pairs[i + 1] != null ? 1 : 0);
        if (count != 0 && count != 2) {
          return false;
        }
      }

      // Verify that we've found a non-null pair.
      return count == 2;
    }

    @Override
    public T build() {
      T instance = getInstance();
      checkArgumentNotNull(instance.clientId, "clientId must be specified");
      checkArgumentNotNull(instance.eventTime, "eventTime must be specified");
      checkState(
          exactlyOnePairNonNull(
              instance.domainRepoId,
              instance.domainHistoryRevisionId,
              instance.contactRepoId,
              instance.contactHistoryRevisionId,
              instance.hostRepoId,
              instance.hostHistoryRevisionId),
          "the repo id and history revision id must be defined for exactly one of domain, "
              + "contact or host: "
              + instance);
      return super.build();
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

    @Ignore
    @Column(name = "transfer_response_host_id")
    String hostId;

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
      return NullIgnoringCollectionBuilder.create(new ImmutableList.Builder<ResponseData>())
          .add(pendingActionNotificationResponse)
          .add(transferResponse)
          .getBuilder()
          .build();
    }

    @PostLoad
    void postLoad() {
      if (pendingActionNotificationResponse != null) {
        // Promote the pending action notification response to its specialized type.
        if (contactId != null) {
          pendingActionNotificationResponse =
              ContactPendingActionNotificationResponse.create(
                  pendingActionNotificationResponse.nameOrId.value,
                  pendingActionNotificationResponse.getActionResult(),
                  pendingActionNotificationResponse.getTrid(),
                  pendingActionNotificationResponse.processedDate);
        } else if (fullyQualifiedDomainName != null) {
          pendingActionNotificationResponse =
              DomainPendingActionNotificationResponse.create(
                  pendingActionNotificationResponse.nameOrId.value,
                  pendingActionNotificationResponse.getActionResult(),
                  pendingActionNotificationResponse.getTrid(),
                  pendingActionNotificationResponse.processedDate);
        } else if (hostId != null) {
          pendingActionNotificationResponse =
              HostPendingActionNotificationResponse.create(
                  pendingActionNotificationResponse.nameOrId.value,
                  pendingActionNotificationResponse.getActionResult(),
                  pendingActionNotificationResponse.getTrid(),
                  pendingActionNotificationResponse.processedDate);
        }
      }
      if (transferResponse != null) {
        // The transferResponse is currently an unspecialized TransferResponse instance, create the
        // appropriate subclass so that the value is consistently specialized
        if (contactId != null) {
          transferResponse =
              new ContactTransferResponse.Builder()
                  .setContactId(contactId)
                  .setGainingRegistrarId(transferResponse.getGainingRegistrarId())
                  .setLosingRegistrarId(transferResponse.getLosingRegistrarId())
                  .setTransferStatus(transferResponse.getTransferStatus())
                  .setTransferRequestTime(transferResponse.getTransferRequestTime())
                  .setPendingTransferExpirationTime(
                      transferResponse.getPendingTransferExpirationTime())
                  .build();
        } else if (fullyQualifiedDomainName != null) {
          transferResponse =
              new DomainTransferResponse.Builder()
                  .setFullyQualifiedDomainName(fullyQualifiedDomainName)
                  .setGainingRegistrarId(transferResponse.getGainingRegistrarId())
                  .setLosingRegistrarId(transferResponse.getLosingRegistrarId())
                  .setTransferStatus(transferResponse.getTransferStatus())
                  .setTransferRequestTime(transferResponse.getTransferRequestTime())
                  .setPendingTransferExpirationTime(
                      transferResponse.getPendingTransferExpirationTime())
                  .setExtendedRegistrationExpirationTime(extendedRegistrationExpirationTime)
                  .build();
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
        OneTime instance = getInstance();
        // Note: In its current form, the code will basically just ignore everything but the first
        // PendingActionNotificationResponse and TransferResponse in responseData, and will override
        // any identifier fields (e.g. contactId, fullyQualifiedDomainName) obtained from the
        // PendingActionNotificationResponse if a TransferResponse is found with different values
        // for those fields.  It is not clear what the constraints should be on this data or
        // whether we should enforce them here, though historically we have not, so the current
        // implementation doesn't.

        // Extract the first PendingActionNotificationResponse element from the list if there is
        // one.
        instance.pendingActionNotificationResponse =
            responseData.stream()
                .filter(PendingActionNotificationResponse.class::isInstance)
                .map(PendingActionNotificationResponse.class::cast)
                .findFirst()
                .orElse(null);

        // Set identifier fields based on the type of the notification response.
        if (instance.pendingActionNotificationResponse
            instanceof ContactPendingActionNotificationResponse) {
          instance.contactId = instance.pendingActionNotificationResponse.nameOrId.value;
        } else if (instance.pendingActionNotificationResponse
            instanceof DomainPendingActionNotificationResponse) {
          instance.fullyQualifiedDomainName =
              instance.pendingActionNotificationResponse.nameOrId.value;
        } else if (instance.pendingActionNotificationResponse
            instanceof HostPendingActionNotificationResponse) {
          instance.hostId = instance.pendingActionNotificationResponse.nameOrId.value;
        }

        // Extract the first TransferResponse from the list.
        instance.transferResponse =
            responseData.stream()
                .filter(TransferResponse.class::isInstance)
                .map(TransferResponse.class::cast)
                .findFirst()
                .orElse(null);

        // Set the identifier according to the TransferResponse type.
        if (instance.transferResponse instanceof ContactTransferResponse) {
          instance.contactId = ((ContactTransferResponse) instance.transferResponse).getContactId();
        } else if (instance.transferResponse instanceof DomainTransferResponse) {
          DomainTransferResponse response = (DomainTransferResponse) instance.transferResponse;
          instance.fullyQualifiedDomainName = response.getFullyQualifiedDomainName();
          instance.extendedRegistrationExpirationTime =
              response.getExtendedRegistrationExpirationTime();
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
