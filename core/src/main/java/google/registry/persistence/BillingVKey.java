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
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.OneTime;
import google.registry.model.billing.BillingEvent.Recurring;
import google.registry.model.domain.DomainBase;
import google.registry.model.reporting.HistoryEntry;
import javax.persistence.AttributeOverride;
import javax.persistence.AttributeOverrides;
import javax.persistence.Column;
import javax.persistence.Embeddable;
import javax.persistence.MappedSuperclass;

/** Base class for {@link BillingEvent}'s {@link VKey}. */
@MappedSuperclass
public abstract class BillingVKey<K> extends EppHistoryVKey<K, DomainBase> {
  Long billingId;

  // Hibernate requires a default constructor.
  BillingVKey() {}

  BillingVKey(String repoId, long historyRevisionId, long billingId) {
    this.repoId = repoId;
    this.historyRevisionId = historyRevisionId;
    this.billingId = billingId;
  }

  Key<HistoryEntry> createHistoryEntryKey() {
    return Key.create(Key.create(DomainBase.class, repoId), HistoryEntry.class, historyRevisionId);
  }

  @Override
  public Object createSqlKey() {
    return billingId;
  }

  /** VKey class for {@link BillingEvent.OneTime} that belongs to a {@link DomainBase} entity. */
  @Embeddable
  @AttributeOverrides({
    @AttributeOverride(name = "repoId", column = @Column(name = "billing_event_domain_repo_id")),
    @AttributeOverride(
        name = "historyRevisionId",
        column = @Column(name = "billing_event_history_id")),
    @AttributeOverride(name = "billingId", column = @Column(name = "billing_event_id"))
  })
  public static class BillingEventVKey extends BillingVKey<OneTime> {

    // Hibernate requires this default constructor
    private BillingEventVKey() {}

    private BillingEventVKey(String repoId, long historyRevisionId, long billingEventId) {
      super(repoId, historyRevisionId, billingEventId);
    }

    @Override
    public Key<OneTime> createOfyKey() {
      return Key.create(createHistoryEntryKey(), BillingEvent.OneTime.class, billingId);
    }

    /** Creates a {@link BillingEventVKey} instance from the given {@link Key} instance. */
    public static BillingEventVKey create(Key<BillingEvent.OneTime> ofyKey) {
      checkArgumentNotNull(ofyKey, "ofyKey must not be null");
      long billingEventId = ofyKey.getId();
      long historyRevisionId = ofyKey.getParent().getId();
      String repoId = ofyKey.getParent().getParent().getName();
      return new BillingEventVKey(repoId, historyRevisionId, billingEventId);
    }

    /** Creates a {@link BillingEventVKey} instance from the given {@link VKey} instance. */
    public static BillingEventVKey create(VKey<BillingEvent.OneTime> vKey) {
      checkArgumentNotNull(vKey, "vKey must not be null");
      return create(vKey.getOfyKey());
    }
  }

  /** VKey class for {@link BillingEvent.Recurring} that belongs to a {@link DomainBase} entity. */
  @Embeddable
  @AttributeOverrides({
    @AttributeOverride(
        name = "repoId",
        column = @Column(name = "billing_recurrence_domain_repo_id")),
    @AttributeOverride(
        name = "historyRevisionId",
        column = @Column(name = "billing_recurrence_history_id")),
    @AttributeOverride(name = "billingId", column = @Column(name = "billing_recurrence_id"))
  })
  public static class BillingRecurrenceVKey extends BillingVKey<Recurring> {

    // Hibernate requires this default constructor
    private BillingRecurrenceVKey() {}

    private BillingRecurrenceVKey(String repoId, long historyRevisionId, long billingEventId) {
      super(repoId, historyRevisionId, billingEventId);
    }

    @Override
    public Key<Recurring> createOfyKey() {
      return Key.create(createHistoryEntryKey(), BillingEvent.Recurring.class, billingId);
    }

    /** Creates a {@link BillingRecurrenceVKey} instance from the given {@link Key} instance. */
    public static BillingRecurrenceVKey create(Key<BillingEvent.Recurring> ofyKey) {
      checkArgumentNotNull(ofyKey, "ofyKey must not be null");
      long billingEventId = ofyKey.getId();
      long historyRevisionId = ofyKey.getParent().getId();
      String repoId = ofyKey.getParent().getParent().getName();
      return new BillingRecurrenceVKey(repoId, historyRevisionId, billingEventId);
    }

    /** Creates a {@link BillingRecurrenceVKey} instance from the given {@link VKey} instance. */
    public static BillingRecurrenceVKey create(VKey<BillingEvent.Recurring> vKey) {
      checkArgumentNotNull(vKey, "vKey must not be null");
      return create(vKey.getOfyKey());
    }
  }
}
