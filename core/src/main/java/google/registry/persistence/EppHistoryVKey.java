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

import com.google.common.base.Joiner;
import com.googlecode.objectify.Key;
import google.registry.model.EppResource;
import google.registry.model.reporting.HistoryEntry;
import google.registry.util.TypeUtils.TypeInstantiator;
import javax.annotation.Nullable;
import javax.persistence.MappedSuperclass;
import javax.persistence.PostLoad;

/**
 * Base class for {@link VKey} which {@link #ofyKey} has a {@link HistoryEntry} key as its parent
 * and a key for EPP resource as its grandparent.
 *
 * <p>For such a {@link VKey}, we need to provide two type parameters to indicate the type of {@link
 * VKey} itself and the type of EPP resource respectively.
 *
 * @param <K> type of the {@link VKey}
 * @param <E> type of the EPP resource that the key belongs to
 */
@MappedSuperclass
public abstract class EppHistoryVKey<K, E extends EppResource> extends VKey<K> {

  String repoId;

  Long historyRevisionId;

  EppHistoryVKey() {
    this.kind = getVKeyType();
  }

  private Class<K> getVKeyType() {
    return new TypeInstantiator<K>(getClass()) {}.getExactType();
  }

  /**
   * Returns the kind path for the {@link #ofyKey} in this instance.
   *
   * <p>This method is only used reflectively by {@link EppHistoryVKeyTranslatorFactory} to get the
   * kind path for a given {@link EppHistoryVKey} instance so it is marked as a private method.
   *
   * @see #createKindPath(Key)
   */
  @SuppressWarnings("unused")
  private String getKindPath() {
    String eppKind = Key.getKind(new TypeInstantiator<E>(getClass()) {}.getExactType());
    String keyKind = Key.getKind(new TypeInstantiator<K>(getClass()) {}.getExactType());
    if (keyKind.equals(Key.getKind(HistoryEntry.class))) {
      return createKindPath(eppKind, keyKind);
    } else {
      return createKindPath(eppKind, Key.getKind(HistoryEntry.class), keyKind);
    }
  }

  /**
   * Creates the kind path for the given {@link #ofyKey}.
   *
   * <p>The kind path is a string including all kind names(delimited by slash) of a hierarchical
   * {@link Key}, e.g., the kind path for BillingEvent.OneTime is "DomainBase/HistoryEntry/OneTime".
   */
  @Nullable
  public static String createKindPath(@Nullable Key<?> ofyKey) {
    if (ofyKey == null) {
      return null;
    } else if (ofyKey.getParent() == null) {
      return ofyKey.getKind();
    } else {
      return createKindPath(createKindPath(ofyKey.getParent()), ofyKey.getKind());
    }
  }

  private static String createKindPath(String... kinds) {
    return Joiner.on("/").join(kinds);
  }

  abstract Object createSqlKey();

  abstract Key<K> createOfyKey();

  @PostLoad
  void postLoad() {
    if (repoId != null && historyRevisionId != null) {
      this.kind = getVKeyType();
      this.ofyKey = createOfyKey();
      this.sqlKey = createSqlKey();
    }
  }

  public boolean isNullKey() {
    return historyRevisionId == null;
  }
}
