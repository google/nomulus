// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

package google.registry.persistence;

import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.googlecode.objectify.Key;
import google.registry.model.EppResource;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.poll.PollMessage;
import google.registry.model.reporting.HistoryEntry;
import javax.persistence.Embeddable;
import javax.persistence.MappedSuperclass;

/** Base class for {@link PollMessage}'s {@link VKey}. */
@MappedSuperclass
public abstract class PollMessageVKey<E extends EppResource>
    extends EppHistoryVKey<PollMessage, E> {

  Long pollMessageId;

  // Hibernate requires a default constructor.
  PollMessageVKey() {}

  PollMessageVKey(String repoId, long historyRevisionId, long pollMessageId) {
    this.kind = PollMessage.class;
    this.ofyKey = createOfyKey(repoId, historyRevisionId, pollMessageId);
    this.sqlKey = pollMessageId;
    this.repoId = repoId;
    this.historyRevisionId = historyRevisionId;
    this.pollMessageId = pollMessageId;
  }

  @Override
  Object createSqlKey() {
    return pollMessageId;
  }

  @Override
  Key<PollMessage> createOfyKey() {
    return createOfyKey(repoId, historyRevisionId, pollMessageId);
  }

  private Key<PollMessage> createOfyKey(String repoId, long historyRevisionId, long pollMessageId) {
    Key<E> grandparent = Key.create(getEppType(), repoId);
    Key<HistoryEntry> parent = Key.create(grandparent, HistoryEntry.class, historyRevisionId);
    return Key.create(parent, PollMessage.class, pollMessageId);
  }

  abstract Class<E> getEppType();

  /** Converts this instance to a {@link VKey} of {@link PollMessage.OneTime} instance. */
  public VKey<PollMessage.OneTime> toOneTimeVKey() {
    return VKey.create(
        PollMessage.OneTime.class,
        pollMessageId,
        Key.create(getOfyKey().getParent(), PollMessage.OneTime.class, pollMessageId));
  }

  /** Converts this instance to a {@link VKey} of {@link PollMessage.Autorenew} instance. */
  public VKey<PollMessage.Autorenew> toAutoRenewVKey() {
    return VKey.create(
        PollMessage.Autorenew.class,
        pollMessageId,
        Key.create(getOfyKey().getParent(), PollMessage.Autorenew.class, pollMessageId));
  }

  /** VKey class for {@link PollMessage} that belongs to a {@link DomainBase} entity. */
  @Embeddable
  public static class DomainPollMessageVKey extends PollMessageVKey<DomainBase> {

    // Hibernate requires a default constructor.
    DomainPollMessageVKey() {}

    DomainPollMessageVKey(String repoId, long historyRevisionId, long pollMessageId) {
      super(repoId, historyRevisionId, pollMessageId);
    }

    @Override
    Class<DomainBase> getEppType() {
      return DomainBase.class;
    }

    /** Creates a {@link DomainPollMessageVKey} instance from the given {@link VKey} instance. */
    public static DomainPollMessageVKey create(VKey<? extends PollMessage> pollMessageVKey) {
      checkArgumentNotNull(pollMessageVKey, "pollMessageVKey must not be null");
      return create(pollMessageVKey.getOfyKey());
    }

    /** Creates a {@link DomainPollMessageVKey} instance from the given {@link Key} instance. */
    public static DomainPollMessageVKey create(Key<? extends PollMessage> ofyKey) {
      checkArgumentNotNull(ofyKey, "ofyKey must not be null");
      long pollMessageId = ofyKey.getId();
      long historyRevisionId = ofyKey.getParent().getId();
      String repoId = ofyKey.getParent().getParent().getName();
      return new DomainPollMessageVKey(repoId, historyRevisionId, pollMessageId);
    }
  }

  /** VKey class for {@link PollMessage} that belongs to a {@link ContactResource} entity. */
  @Embeddable
  public static class ContactPollMessageVKey extends PollMessageVKey<ContactResource> {

    // Hibernate requires a default constructor.
    ContactPollMessageVKey() {}

    ContactPollMessageVKey(String repoId, long historyRevisionId, long pollMessageId) {
      super(repoId, historyRevisionId, pollMessageId);
    }

    @Override
    Class<ContactResource> getEppType() {
      return ContactResource.class;
    }

    /** Creates a {@link ContactPollMessageVKey} instance from the given {@link VKey} instance. */
    public static ContactPollMessageVKey create(VKey<? extends PollMessage> pollMessageVKey) {
      checkArgumentNotNull(pollMessageVKey, "pollMessageVKey must not be null");
      return create(pollMessageVKey.getOfyKey());
    }

    /** Creates a {@link ContactPollMessageVKey} instance from the given {@link Key} instance. */
    public static ContactPollMessageVKey create(Key<? extends PollMessage> ofyKey) {
      checkArgumentNotNull(ofyKey, "ofyKey must not be null");
      long pollMessageId = ofyKey.getId();
      long historyRevisionId = ofyKey.getParent().getId();
      String repoId = ofyKey.getParent().getParent().getName();
      return new ContactPollMessageVKey(repoId, historyRevisionId, pollMessageId);
    }
  }
}
