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

package google.registry.model.index;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.isNullOrEmpty;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.model.ofy.Ofy.RECOMMENDED_MEMCACHE_EXPIRATION;
import static google.registry.util.CollectionUtils.isNullOrEmpty;

import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Cache;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import google.registry.model.BackupGroupRoot;
import google.registry.model.annotations.ReportedOn;
import google.registry.model.domain.DomainApplication;
import google.registry.util.CollectionUtils;
import java.util.Set;
import javax.annotation.Nullable;
import org.joda.time.DateTime;

/**
 * Entity for tracking all domain applications with a given fully qualified domain name. Since this
 * resource is always kept up to date as additional domain applications are created, it is never
 * necessary to query them explicitly from Datastore.
 */
@ReportedOn
@Entity
@Cache(expirationSeconds = RECOMMENDED_MEMCACHE_EXPIRATION)
public class DomainApplicationIndex extends BackupGroupRoot {

  @Id
  String fullyQualifiedDomainName;

  /**
   * A set of all domain applications with this fully qualified domain name. Never null or empty.
   *
   * <p>Although this stores {@link Key}s it is named "references" for historical reasons.
   */
  Set<Key<DomainApplication>> references;

  /** Returns a cloned list of all keys on this index. */
  public ImmutableSet<Key<DomainApplication>> getKeys() {
    return ImmutableSet.copyOf(references);
  }

  public String getFullyQualifiedDomainName() {
    return fullyQualifiedDomainName;
  }

  /**
   * Creates a DomainApplicationIndex with the specified list of keys. Only use this method for data
   * migrations. You probably want {@link #createUpdatedInstance}.
   */
  public static DomainApplicationIndex createWithSpecifiedKeys(
      String fullyQualifiedDomainName,
      ImmutableSet<Key<DomainApplication>> keys) {
    checkArgument(!isNullOrEmpty(fullyQualifiedDomainName),
        "fullyQualifiedDomainName must not be null or empty.");
    checkArgument(!isNullOrEmpty(keys), "Keys must not be null or empty.");
    DomainApplicationIndex instance = new DomainApplicationIndex();
    instance.fullyQualifiedDomainName = fullyQualifiedDomainName;
    instance.references = keys;
    return instance;
  }

  public static Key<DomainApplicationIndex> createKey(DomainApplication application) {
    return Key.create(DomainApplicationIndex.class, application.getFullyQualifiedDomainName());
  }

  /**
   * Returns the set of all DomainApplications for the given fully qualified domain name that do
   * not have a deletion time before the supplied DateTime.
   */
  public static ImmutableSet<DomainApplication> loadActiveApplicationsByDomainName(
      String fullyQualifiedDomainName, DateTime now) {
    DomainApplicationIndex index = load(fullyQualifiedDomainName);
    if (index == null) {
      return ImmutableSet.of();
    }
    ImmutableSet.Builder<DomainApplication> apps = new ImmutableSet.Builder<>();
    for (DomainApplication app : ofy().load().keys(index.getKeys()).values()) {
      if (app.getDeletionTime().isAfter(now)) {
        apps.add(app);
      }
    }
    return apps.build();
  }

  /**
   * Returns the DomainApplicationIndex for the given fully qualified domain name. Note that this
   * can return null if there are no domain applications for this fully qualified domain name.
   */
  @Nullable
  public static DomainApplicationIndex load(String fullyQualifiedDomainName) {
    return ofy()
        .load()
        .type(DomainApplicationIndex.class)
        .id(fullyQualifiedDomainName)
        .now();
  }

  /**
   * Saves a new DomainApplicationIndex for this resource or updates the existing one. This is
   * the preferred method for creating an instance of DomainApplicationIndex because this performs
   * the correct merging logic to add the given domain application to an existing index if there
   * is one.
   */
  public static DomainApplicationIndex createUpdatedInstance(DomainApplication application) {
    DomainApplicationIndex existing = load(application.getFullyQualifiedDomainName());
    ImmutableSet<Key<DomainApplication>> newKeys = CollectionUtils.union(
        (existing == null ? ImmutableSet.<Key<DomainApplication>>of() : existing.getKeys()),
        Key.create(application));
    return createWithSpecifiedKeys(application.getFullyQualifiedDomainName(), newKeys);
  }
}
