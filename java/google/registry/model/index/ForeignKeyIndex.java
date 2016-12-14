// Copyright 2016 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.collect.Maps.filterValues;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.model.ofy.Ofy.RECOMMENDED_MEMCACHE_EXPIRATION;
import static google.registry.util.TypeUtils.instantiate;

import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Cache;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Index;
import google.registry.model.BackupGroupRoot;
import google.registry.model.EppResource;
import google.registry.model.annotations.ReportedOn;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainResource;
import google.registry.model.host.HostResource;
import java.util.Map;
import javax.annotation.Nullable;
import org.joda.time.DateTime;

/**
 * Class to map a foreign key to the active instance of {@link EppResource} whose unique id matches
 * the foreign key string. The instance is never deleted, but it is updated if a newer entity
 * becomes the active entity.
 */
public abstract class ForeignKeyIndex<E extends EppResource> extends BackupGroupRoot {

  /** The {@link ForeignKeyIndex} type for {@link ContactResource} entities. */
  @Cache(expirationSeconds = RECOMMENDED_MEMCACHE_EXPIRATION)
  @ReportedOn
  @Entity
  public static class ForeignKeyContactIndex extends ForeignKeyIndex<ContactResource> {}

  /** The {@link ForeignKeyIndex} type for {@link DomainResource} entities. */
  @Cache(expirationSeconds = RECOMMENDED_MEMCACHE_EXPIRATION)
  @ReportedOn
  @Entity
  public static class ForeignKeyDomainIndex extends ForeignKeyIndex<DomainResource> {}

  /** The {@link ForeignKeyIndex} type for {@link HostResource} entities. */
  @Cache(expirationSeconds = RECOMMENDED_MEMCACHE_EXPIRATION)
  @ReportedOn
  @Entity
  public static class ForeignKeyHostIndex extends ForeignKeyIndex<HostResource> {}

  private static final Map<
      Class<? extends EppResource>,
      Class<? extends ForeignKeyIndex<?>>> RESOURCE_CLASS_TO_FKI_CLASS =
          ImmutableMap.<Class<? extends EppResource>, Class<? extends ForeignKeyIndex<?>>>of(
              ContactResource.class, ForeignKeyContactIndex.class,
              DomainResource.class, ForeignKeyDomainIndex.class,
              HostResource.class, ForeignKeyHostIndex.class);

  @Id
  String foreignKey;

  /**
   * The deletion time of this {@link ForeignKeyIndex}.
   *
   * <p>This will generally be equal to the deletion time of {@link #topReference}. However, in the
   * case of a {@link HostResource} that was renamed, this field will hold the time of the rename.
   */
  @Index
  DateTime deletionTime;

  /**
   * The referenced resource.
   *
   * <p>This field holds a key to the only referenced resource. It is named "topReference" for
   * historical reasons.
   */
  Key<E> topReference;

  public String getForeignKey() {
    return foreignKey;
  }

  public DateTime getDeletionTime() {
    return deletionTime;
  }

  public Key<E> getResourceKey() {
    return topReference;
  }

  /**
   * Create a {@link ForeignKeyIndex} instance for a resource, expiring at a specified time.
   */
  public static <E extends EppResource> ForeignKeyIndex<E> create(
      E resource, DateTime deletionTime) {
    @SuppressWarnings("unchecked")
    Class<? extends ForeignKeyIndex<E>> fkiClass =
        (Class<? extends ForeignKeyIndex<E>>) RESOURCE_CLASS_TO_FKI_CLASS.get(resource.getClass());
    ForeignKeyIndex<E> instance = instantiate(fkiClass);
    instance.topReference = Key.create(resource);
    instance.foreignKey = resource.getForeignKey();
    instance.deletionTime = deletionTime;
    return instance;
  }

  /** Create a {@link ForeignKeyIndex} key for a resource. */
  public static Key<ForeignKeyIndex<?>> createKey(EppResource resource) {
    return Key.<ForeignKeyIndex<?>>create(
        RESOURCE_CLASS_TO_FKI_CLASS.get(resource.getClass()), resource.getForeignKey());
  }

  /**
   * Loads a {@link Key} to an {@link EppResource} from the datastore by foreign key.
   *
   * <p>Returns null if no foreign key index with this foreign key was ever created, or if the
   * most recently created foreign key index was deleted before time "now". This method does not
   * actually check that the referenced resource actually exists. However, for normal epp resources,
   * it is safe to assume that the referenced resource exists if the foreign key index does.
   *
   * @param clazz the resource type to load
   * @param foreignKey id to match
   * @param now the current logical time to use when checking for soft deletion of the foreign key
   *        index
   */
  @Nullable
  public static <E extends EppResource> Key<E> loadAndGetKey(
      Class<E> clazz, String foreignKey, DateTime now) {
    ForeignKeyIndex<E> index = load(clazz, foreignKey, now);
    return (index == null) ? null : index.getResourceKey();
  }

  /**
   * Load a {@link ForeignKeyIndex} by class and id string that is active at or after the specified
   * moment in time.
   *
   * <p>This will return null if the {@link ForeignKeyIndex} doesn't exist or has been soft deleted.
   */
  @Nullable
  public static <E extends EppResource> ForeignKeyIndex<E> load(
      Class<E> clazz, String foreignKey, DateTime now) {
    return load(clazz, ImmutableList.of(foreignKey), now).get(foreignKey);
  }

  /**
   * Load a list of {@link ForeignKeyIndex} instances by class and id strings that are active at or
   * after the specified moment in time.
   *
   * <p>The returned map will omit any keys for which the {@link ForeignKeyIndex} doesn't exist or
   * has been soft deleted.
   */
  @SuppressWarnings("unchecked")
  public static <E extends EppResource> Map<String, ForeignKeyIndex<E>> load(
      Class<E> clazz, Iterable<String> foreignKeys, final DateTime now) {
    return (Map<String, ForeignKeyIndex<E>>) filterValues(
        ofy().load().type(RESOURCE_CLASS_TO_FKI_CLASS.get(clazz)).ids(foreignKeys),
        new Predicate<ForeignKeyIndex<?>>() {
          @Override
          public boolean apply(ForeignKeyIndex<?> fki) {
            return now.isBefore(fki.deletionTime);
          }});
  }
}
