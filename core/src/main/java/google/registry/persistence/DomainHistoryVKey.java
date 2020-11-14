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
import google.registry.model.domain.DomainHistory.DomainHistoryId;
import google.registry.model.reporting.HistoryEntry;
import javax.persistence.Embeddable;
import javax.persistence.PostLoad;

/** {@link VKey} for {@link HistoryEntry} which parent is {@link DomainBase}. */
@Embeddable
public class DomainHistoryVKey extends VKey<HistoryEntry> {

  private String domainRepoId;

  private Long domainHistoryId;

  // Hibernate requires a default constructor
  private DomainHistoryVKey() {}

  private DomainHistoryVKey(String domainRepoId, long domainHistoryId) {
    initWith(domainRepoId, domainHistoryId);
  }

  @PostLoad
  void postLoad() {
    initWith(domainRepoId, domainHistoryId);
  }

  private void initWith(String domainRepoId, long domainHistoryId) {
    this.kind = HistoryEntry.class;
    this.ofyKey =
        Key.create(Key.create(DomainBase.class, domainRepoId), HistoryEntry.class, domainHistoryId);
    this.sqlKey = new DomainHistoryId(domainRepoId, domainHistoryId);
    this.domainRepoId = domainRepoId;
    this.domainHistoryId = domainHistoryId;
  }

  /** Creates {@link DomainHistoryVKey} from the given {@link Key} instance. */
  public static DomainHistoryVKey create(Key<HistoryEntry> ofyKey) {
    checkArgumentNotNull(ofyKey, "ofyKey must be specified");
    String domainRepoId = ofyKey.getParent().getName();
    long domainHistoryId = ofyKey.getId();
    return new DomainHistoryVKey(domainRepoId, domainHistoryId);
  }
}
