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
import google.registry.model.ImmutableObject;
import google.registry.model.reporting.HistoryEntry;
import google.registry.util.TypeUtils.TypeInstantiator;
import java.io.Serializable;
import javax.annotation.Nullable;
import javax.persistence.Access;
import javax.persistence.AccessType;
import javax.persistence.MappedSuperclass;

/**
 * Base class for {@link VKey} which ofyKey has a {@link HistoryEntry} key as its parent and a key
 * for EPP resource as its grandparent.
 *
 * <p>For such a {@link VKey}, we need to provide two type parameters to indicate the type of {@link
 * VKey} itself and the type of EPP resource respectively.
 *
 * @param <K> type of the {@link VKey}
 * @param <E> type of the EPP resource that the key belongs to
 */
@MappedSuperclass
@Access(AccessType.FIELD)
public abstract class EppHistoryVKey<K, E extends EppResource> extends ImmutableObject
    implements Serializable {

  private static final long serialVersionUID = -3906580677709539818L;

  String repoId;

  Long historyRevisionId;

  // Hibernate requires a default constructor.
  EppHistoryVKey() {}

  EppHistoryVKey(String repoId, long historyRevisionId) {
    this.repoId = repoId;
    this.historyRevisionId = historyRevisionId;
  }

  /**
   * Returns the kind path for the ofyKey in this instance.
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
   * Creates the kind path for the given ofyKey}.
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

  /** Creates a {@link VKey} from this instance. */
  public VKey<K> createVKey() {
    Class<K> vKeyType = new TypeInstantiator<K>(getClass()) {}.getExactType();
    return VKey.create(vKeyType, createSqlKey(), createOfyKey());
  }

  public abstract Serializable createSqlKey();

  public abstract Key<K> createOfyKey();
}
