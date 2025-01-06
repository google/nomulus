// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.console;

import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import google.registry.model.reporting.HistoryEntry;
import google.registry.model.reporting.HistoryEntry.HistoryEntryId;
import google.registry.persistence.VKey;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * A persisted history object representing an EPP action via the console.
 *
 * <p>In addition to the generic history fields (time, URL, etc.) we also persist a reference to the
 * history entry so that we can refer to it if necessary.
 */
@Access(AccessType.FIELD)
@Entity
@Table(
    indexes = {
      @Index(columnList = "historyActingUser"),
      @Index(columnList = "repoId"),
      @Index(columnList = "revisionId")
    })
public class ConsoleEppActionHistory extends ConsoleUpdateHistory {

  @AttributeOverride(name = "repoId", column = @Column(nullable = false))
  HistoryEntryId historyEntryId;

  @Column(nullable = false)
  Class<? extends HistoryEntry> historyEntryClass;

  public HistoryEntryId getHistoryEntryId() {
    return historyEntryId;
  }

  public Class<? extends HistoryEntry> getHistoryEntryClass() {
    return historyEntryClass;
  }

  /** Creates a {@link VKey} instance for this entity. */
  @Override
  public VKey<ConsoleEppActionHistory> createVKey() {
    return VKey.create(ConsoleEppActionHistory.class, getRevisionId());
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  /** Builder for the immutable UserUpdateHistory. */
  public static class Builder
      extends ConsoleUpdateHistory.Builder<ConsoleEppActionHistory, Builder> {

    public Builder() {}

    public Builder(ConsoleEppActionHistory instance) {
      super(instance);
    }

    @Override
    public ConsoleEppActionHistory build() {
      checkArgumentNotNull(getInstance().historyEntryId, "History entry ID must be specified");
      checkArgumentNotNull(
          getInstance().historyEntryClass, "History entry class must be specified");
      return super.build();
    }

    public Builder setHistoryEntryId(HistoryEntryId historyEntryId) {
      getInstance().historyEntryId = historyEntryId;
      return this;
    }

    public Builder setHistoryEntryClass(Class<? extends HistoryEntry> historyEntryClass) {
      getInstance().historyEntryClass = historyEntryClass;
      return this;
    }
  }
}
