// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.bulkquery;

import com.googlecode.objectify.Key;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainContent;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.DomainHistory.DomainHistoryId;
import google.registry.model.domain.Period;
import google.registry.model.replay.SqlOnlyEntity;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.VKey;
import javax.annotation.Nullable;
import javax.persistence.Access;
import javax.persistence.AccessType;
import javax.persistence.AttributeOverride;
import javax.persistence.AttributeOverrides;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.IdClass;
import javax.persistence.PostLoad;

/**
 * A 'light' version of {@link DomainHistory} with only base table ("DomainHistory") attributes,
 * which allows fast bulk loading. They are used in in-memory assembly of {@code DomainHistory}
 * instances along with bulk-loaded child entities ({@code GracePeriodHistory} etc). The in-memory
 * assembly achieves much higher performance than loading {@code DomainHistory} directly.
 *
 * <p>Please refer to {@link BulkQueryEntities} for more information.
 *
 * <p>This class is adapted from {@link DomainHistory} by removing the {@code dsDataHistories},
 * {@code gracePeriodHistories}, and {@code nsHosts} fields and associated methods.
 */
@Entity(name = "DomainHistory")
@Access(AccessType.FIELD)
@IdClass(DomainHistoryId.class)
public class DomainHistoryLite extends HistoryEntry implements SqlOnlyEntity {

  // Store DomainContent instead of DomainBase so we don't pick up its @Id
  // Nullable for the sake of pre-Registry-3.0 history objects
  @Nullable DomainContent domainContent;

  @Id
  @Access(AccessType.PROPERTY)
  public String getDomainRepoId() {
    // We need to handle null case here because Hibernate sometimes accesses this method before
    // parent gets initialized
    return parent == null ? null : parent.getName();
  }

  /** This method is private because it is only used by Hibernate. */
  @SuppressWarnings("unused")
  private void setDomainRepoId(String domainRepoId) {
    parent = Key.create(DomainBase.class, domainRepoId);
  }

  @Override
  @Nullable
  @Access(AccessType.PROPERTY)
  @AttributeOverrides({
    @AttributeOverride(name = "unit", column = @Column(name = "historyPeriodUnit")),
    @AttributeOverride(name = "value", column = @Column(name = "historyPeriodValue"))
  })
  public Period getPeriod() {
    return super.getPeriod();
  }

  /**
   * For transfers, the id of the other registrar.
   *
   * <p>For requests and cancels, the other registrar is the losing party (because the registrar
   * sending the EPP transfer command is the gaining party). For approves and rejects, the other
   * registrar is the gaining party.
   */
  @Nullable
  @Access(AccessType.PROPERTY)
  @Column(name = "historyOtherRegistrarId")
  @Override
  public String getOtherRegistrarId() {
    return super.getOtherRegistrarId();
  }

  @Id
  @Column(name = "historyRevisionId")
  @Access(AccessType.PROPERTY)
  @Override
  public long getId() {
    return super.getId();
  }

  /** The key to the {@link DomainBase} this is based off of. */
  public VKey<DomainBase> getParentVKey() {
    return VKey.create(DomainBase.class, getDomainRepoId());
  }

  @PostLoad
  void postLoad() {
    if (domainContent == null) {
      return;
    }
    // See inline comments in DomainHistory.postLoad for reasons for the following lines.
    if (domainContent.getDomainName() == null) {
      domainContent = null;
    } else if (domainContent.getRepoId() == null) {
      domainContent.setRepoId(parent.getName());
    }
  }
}
