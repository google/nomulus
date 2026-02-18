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
import google.registry.model.Buildable;
import google.registry.model.EppResource;
import google.registry.model.ImmutableObject;
import google.registry.model.UnsafeSerializable;
import google.registry.model.annotations.ExternalMessagingName;
import google.registry.model.annotations.IdAllocation;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.DomainRenewData;
import google.registry.model.eppoutput.EppResponse.ResponseData;
import google.registry.model.host.Host;
import google.registry.model.host.HostHistory;
import google.registry.model.poll.PendingActionNotificationResponse.ContactPendingActionNotificationResponse;
import google.registry.model.poll.PendingActionNotificationResponse.DomainPendingActionNotificationResponse;
import google.registry.model.poll.PendingActionNotificationResponse.HostPendingActionNotificationResponse;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.reporting.HistoryEntry.HistoryEntryId;
import google.registry.model.transfer.TransferData.TransferServerApproveEntity;
import google.registry.model.transfer.TransferResponse;
import google.registry.model.transfer.TransferResponse.ContactTransferResponse;
import google.registry.model.transfer.TransferResponse.DomainTransferResponse;
import google.registry.persistence.VKey;
import google.registry.persistence.WithVKey;
import google.registry.util.NullIgnoringCollectionBuilder;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.PostLoad;
import jakarta.persistence.Table;
import java.util.Optional;
import org.joda.time.DateTime;

/**
 * A poll message that is pending for a registrar.
 *
 * <p>Poll messages are not delivered until their {@link #eventTime} has passed. Poll messages can
 * be speculatively enqueued for future delivery, and then modified or deleted before that date has
 * passed. Unlike most other entities in the database, which are marked as deleted but otherwise
 * retained for historical purposes, poll messages are truly deleted once they have been delivered
 * and ACKed.
 *
 * <p>Poll messages have a reference to the revision id of the {@link HistoryEntry} that resulted in
 * their creation or re-creation, in case of transfer cancellation/rejection. The revision ids are
 * not used directly by the Java code, but their presence in the database makes it easier to
 * diagnose potential issues related to poll messages.
 *
 * <p>Poll messages are identified externally by registrars using the format defined in {@link
 * PollMessageExternalKeyConverter}.
 *
 * @see <a href="https://tools.ietf.org/html/rfc5730#section-2.9.2.3">RFC5730 - EPP - &lt;poll&gt;
 *     Command</a>
 */
@Entity
@ExternalMessagingName("message")
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "type")
@Table(
    indexes = {
      @Index(columnList = "domainRepoId"),
      @Index(columnList = "registrarId"),
      @Index(columnList = "eventTime"),
      @Index(columnList = "domainRepoId,domainHistoryRevisionId")
    })
public abstract class PollMessage extends ImmutableObject
    implements Buildable, TransferServerApproveEntity, UnsafeSerializable {

  /** Indicates the type of entity the poll message is for. */
  public enum Type {
    DOMAIN(1L, Domain.class),
    // Contacts would be 2L but have since been removed. Host is kept at 3 for consistency.
    HOST(3L, Host.class);

    private final long id;
    private final Class<? extends EppResource> clazz;

    Type(long id, Class<? extends EppResource> clazz) {
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
    public Class<? extends EppResource> getResourceClass() {
      return clazz;
    }
  }

  /** Entity id. */
  @Id
  @IdAllocation
  @Column(name = "poll_message_id")
  Long id;

  /** The registrar that this poll message will be delivered to. */
  @Column(name = "registrar_id", nullable = false)
  String clientId;

  /** The time when the poll message should be delivered. May be in the future. */
  @Column(nullable = false)
  DateTime eventTime;

  /** Human-readable message that will be returned with this poll message. */
  @Column(name = "message")
  String msg;

  String domainRepoId;

  String contactRepoId;

  String hostRepoId;

  Long domainHistoryRevisionId;

  Long contactHistoryRevisionId;

  Long hostHistoryRevisionId;

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
   * Returns the host repo id.
   *
   * <p>This may only be used on a Host poll event.
   */
  public String getHostRepoId() {
    checkArgument(getType() == Type.DOMAIN);
    return hostRepoId;
  }

  /**
   * Gets the repo ID of the underlying resource that the PollMessage is for, regardless of the type
   * of the resource.
   */
  public String getResourceId() {
    return domainRepoId != null ? domainRepoId : contactRepoId != null ? contactRepoId : hostRepoId;
  }

  /** Gets the underlying history revision id, regardless of the type of the resource. */
  public Long getHistoryRevisionId() {
    return domainHistoryRevisionId != null
        ? domainHistoryRevisionId
        : contactHistoryRevisionId != null ? contactHistoryRevisionId : hostHistoryRevisionId;
  }

  public Type getType() {
    return domainRepoId != null ? Type.DOMAIN : Type.HOST;
  }

  @Override
  public abstract VKey<? extends PollMessage> createVKey();

  public abstract ImmutableList<ResponseData> getResponseData();

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

    public B setDomainHistoryId(HistoryEntryId historyId) {
      getInstance().domainRepoId = historyId.getRepoId();
      getInstance().domainHistoryRevisionId = historyId.getRevisionId();
      return thisCastToDerived();
    }

    public B setHostHistoryId(HistoryEntryId historyId) {
      getInstance().hostRepoId = historyId.getRepoId();
      getInstance().hostHistoryRevisionId = historyId.getRevisionId();
      return thisCastToDerived();
    }

    public B setHistoryEntry(HistoryEntry history) {
      HistoryEntryId historyId = history.getHistoryEntryId();
      // Set the appropriate field based on the history entry type.
      if (history instanceof DomainHistory) {
        return setDomainHistoryId(historyId);
      }
      if (history instanceof HostHistory) {
        return setHostHistoryId(historyId);
      }
      return thisCastToDerived();
    }

    /**
     * Given an array containing pairs of objects, verifies that both members of exactly one of the
     * pairs is non-null.
     */
    private static boolean exactlyOnePairNonNull(Object... pairs) {
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
          "The repo id and history revision id must be defined for exactly one of domain, "
              + "contact or host: %s",
          instance);
      return super.build();
    }
  }

  /**
   * A one-time poll message.
   *
   * <p>One-time poll messages are deleted from the database once they have been delivered and
   * ACKed.
   */
  @Entity
  @DiscriminatorValue("ONE_TIME")
  @WithVKey(Long.class)
  public static class OneTime extends PollMessage {

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

    @Column(name = "transfer_response_domain_name")
    String domainName;

    @Column(name = "transfer_response_domain_expiration_time")
    DateTime extendedRegistrationExpirationTime;

    @Column(name = "transfer_response_contact_id")
    String contactId;

    @Column(name = "transfer_response_host_id")
    String hostId;

    @Override
    public VKey<OneTime> createVKey() {
      return VKey.create(OneTime.class, getId());
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
        } else if (domainName != null) {
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
        } else if (domainName != null) {
          transferResponse =
              new DomainTransferResponse.Builder()
                  .setDomainName(domainName)
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

      public Builder() {}

      private Builder(OneTime instance) {
        super(instance);
      }

      public Builder setResponseData(ImmutableList<? extends ResponseData> responseData) {
        OneTime instance = getInstance();
        // Note: In its current form, the code will basically just ignore everything but the first
        // PendingActionNotificationResponse and TransferResponse in responseData, and will override
        // any identifier fields (e.g. contactId, domainName) obtained from the
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
          instance.domainName = instance.pendingActionNotificationResponse.nameOrId.value;
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
        } else if (instance.transferResponse instanceof DomainTransferResponse response) {
          instance.domainName = response.getDomainName();
          instance.extendedRegistrationExpirationTime =
              response.getExtendedRegistrationExpirationTime();
        }
        return this;
      }
    }
  }

  /**
   * An autorenew poll message which recurs annually.
   *
   * <p>Auto-renew poll messages are not deleted until the registration of their parent domain has
   * been canceled, because there will always be a speculative renewal for next year until that
   * happens.
   */
  @Entity
  @DiscriminatorValue("AUTORENEW")
  @WithVKey(Long.class)
  public static class Autorenew extends PollMessage {

    /** The target id of the autorenew event. */
    @Column(name = "autorenew_domain_name")
    String targetId;

    /** The autorenew recurs annually between {@link #eventTime} and this time. */
    DateTime autorenewEndTime;

    public String getTargetId() {
      return targetId;
    }

    public DateTime getAutorenewEndTime() {
      return autorenewEndTime;
    }

    @Override
    public VKey<Autorenew> createVKey() {
      return VKey.create(Autorenew.class, getId());
    }

    @Override
    public ImmutableList<ResponseData> getResponseData() {
      // Note that the event time is when the autorenew occurred, so the expiration time in the
      // response should be 1 year past that, since it denotes the new expiration time.
      return ImmutableList.of(DomainRenewData.create(getTargetId(), getEventTime().plusYears(1)));
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
