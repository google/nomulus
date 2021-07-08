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
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.DomainHistory.DomainHistoryId;
import google.registry.model.reporting.HistoryEntry;
import java.io.Serializable;
import javax.persistence.Embeddable;

/** {@link VKey} for {@link HistoryEntry} which parent is {@link DomainBase}. */
@Embeddable
public class DomainHistoryVKey extends EppHistoryVKey<HistoryEntry, DomainBase> {

  // Hibernate requires a default constructor
  private DomainHistoryVKey() {}

  private DomainHistoryVKey(String repoId, long historyRevisionId) {
    super(repoId, historyRevisionId);
  }

  @Override
  public Serializable createSqlKey() {
    return new DomainHistoryId(repoId, historyRevisionId);
  }

  @Override
  public Key<HistoryEntry> createOfyKey() {
    return Key.create(Key.create(DomainBase.class, repoId), HistoryEntry.class, historyRevisionId);
  }

  /** Creates {@link DomainHistoryVKey} from the given {@link Key} instance. */
  public static DomainHistoryVKey create(Key<? extends HistoryEntry> ofyKey) {
    checkArgumentNotNull(ofyKey, "ofyKey must be specified");
    String repoId = ofyKey.getParent().getName();
    long historyRevisionId = ofyKey.getId();
    return new DomainHistoryVKey(repoId, historyRevisionId);
  }

  public VKey<? extends HistoryEntry> createDomainHistoryVKey() {
    return VKey.create(
        DomainHistory.class,
        createSqlKey(),
        Key.create(Key.create(DomainBase.class, repoId), DomainHistory.class, historyRevisionId));
  }
}
