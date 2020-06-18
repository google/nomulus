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

package google.registry.backup;

import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.EntityTranslator;
import com.google.appengine.api.datastore.Key;
import com.google.auto.value.AutoValue;
import com.google.auto.value.extension.memoized.Memoized;
import google.registry.model.ofy.CommitLogManifest;
import google.registry.model.ofy.CommitLogMutation;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/**
 * A Datastore {@link Entity Entity's} serialized state with timestamp. The intended use case is a
 * multi-stage pipeline where an Entity's Java form is not needed in most stages.
 *
 * <p>For a new or updated Entity, its serialized bytes are stored along with its Datastore {@link
 * Key}. For a deleted entity, only its Datastore {@link Key} is stored, and the {@link
 * #entityProtoBytes} field is left unset.
 *
 * <p>Storing raw bytes is motivated by two factors. First, since I/O is frequent and the Java
 * objects are rarely needed in our target use case, storing raw bytes is the most efficient
 * approach. More importantly, due to our data model and our customization of {@link
 * google.registry.model.ofy.ObjectifyService ObjectifyService}, it is challenging to implement a
 * serializer for Objectify entities that preserves the value of all properties. Without such
 * serializers, Objectify entities cannot be used in a pipeline.
 *
 * <p>Objectify entities do not implement {@link Serializable}, serialization of such objects is as
 * follows:
 *
 * <ul>
 *   <li>Convert an Objectify entity to a Datastore {@link Entity}: {@code
 *       ofy().save().toEntity(..)}
 *   <li>Entity is serializable, but the more efficient approach is to convert an Entity to a
 *       ProtocolBuffer ({@link com.google.storage.onestore.v3.OnestoreEntity.EntityProto}) and then
 *       to raw bytes.
 * </ul>
 *
 * <p>When the first conversion above is applied to an Objectify entity, a property value in the
 * output may differ from the input in two situations:
 *
 * <ul>
 *   <li>If a property is of an assign-on-persist data type, e.g., {@link
 *       google.registry.model.UpdateAutoTimestamp}.
 *   <li>If it is related to CommitLog management, e.g., {@link google.registry.model.EppResource
 *       EppResource.revisions}.
 * </ul>
 *
 * <p>Working around the side effects caused by our customization is difficult. Any solution would
 * likely rely on Objectify's stack of context. However, many Objectify invocations in our code base
 * are hardcoded to call the customized version of ObjectifyService, rendering Objectify's stack
 * useless.
 *
 * <p>For now, this inability to use Objectify entities in pipelines is mostly a testing problem: we
 * can not perform {@link org.apache.beam.sdk.testing.PAssert BEAM pipeline assertions} on Objectify
 * entities. {@code InitSqlTestUtils.assertContainsExactlyElementsIn} is an example of a workaround.
 *
 * <p>Note that {@link Optional java.util.Optional} is not serializable, therefore cannot be used as
 * property type in this class.
 */
@AutoValue
public abstract class VersionedEntity implements Serializable {

  private static final long serialVersionUID = 1L;

  public abstract long commitTimeMills();

  /** The {@link Key} of the {@link Entity}. */
  public abstract Key key();

  /** Serialized form of the {@link Entity}. This property is {@code null} for a deleted Entity. */
  @Nullable
  abstract ImmutableBytes entityProtoBytes();

  @Memoized
  public Optional<Entity> getEntity() {
    return Optional.ofNullable(entityProtoBytes())
        .map(ImmutableBytes::getBytes)
        .map(EntityTranslator::createFromPbBytes);
  }

  public boolean isDelete() {
    return entityProtoBytes() == null;
  }

  /**
   * Converts deleted entity keys in {@code manifest} into a {@link Stream} of {@link
   * VersionedEntity VersionedEntities}. See {@link CommitLogImports#loadEntities} for more
   * information.
   */
  public static Stream<VersionedEntity> fromManifest(CommitLogManifest manifest) {
    long commitTimeMillis = manifest.getCommitTime().getMillis();
    return manifest.getDeletions().stream()
        .map(com.googlecode.objectify.Key::getRaw)
        .map(key -> builder().commitTimeMills(commitTimeMillis).key(key).build());
  }

  /* Converts a {@link CommitLogMutation} to a {@link VersionedEntity}. */
  public static VersionedEntity fromMutation(CommitLogMutation mutation) {
    return from(
        com.googlecode.objectify.Key.create(mutation).getParent().getId(),
        mutation.getEntityProtoBytes());
  }

  public static VersionedEntity from(long commitTimeMillis, byte[] entityProtoBytes) {
    return builder()
        .entityProtoBytes(entityProtoBytes)
        .key(EntityTranslator.createFromPbBytes(entityProtoBytes).getKey())
        .commitTimeMills(commitTimeMillis)
        .build();
  }

  static Builder builder() {
    return new AutoValue_VersionedEntity.Builder();
  }

  @AutoValue.Builder
  public abstract static class Builder {

    public abstract Builder commitTimeMills(long commitTimeMillis);

    abstract Builder entityProtoBytes(ImmutableBytes bytes);

    public abstract Builder key(Key key);

    public abstract VersionedEntity build();

    public Builder entityProtoBytes(byte[] bytes) {
      return entityProtoBytes(new ImmutableBytes(bytes));
    }
  }

  /**
   * Wraps a byte array and prevents it from being modified by its original owner.
   *
   * <p>While this class seems an overkill, it exists for two reasons:
   *
   * <ul>
   *   <li>It is easier to override the {@link #equals} method here (for value-equivalence check)
   *       than to override the AutoValue-generated {@code equals} method.
   *   <li>To appease the style checker, which forbids arrays as AutoValue property.
   * </ul>
   */
  static final class ImmutableBytes implements Serializable {

    private static final long serialVersionUID = 1L;

    private final byte[] bytes;

    ImmutableBytes(byte[] bytes) {
      this.bytes = Arrays.copyOf(bytes, bytes.length);
    }

    /**
     * Returns the saved byte array. Invocation is restricted to trusted callers, who must not
     * modify the array.
     */
    byte[] getBytes() {
      return bytes;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof ImmutableBytes)) {
        return false;
      }
      ImmutableBytes that = (ImmutableBytes) o;
      // Do not use Objects.equals, which checks reference identity instead of data in array.
      return Arrays.equals(bytes, that.bytes);
    }

    @Override
    public int hashCode() {
      // Do not use Objects.hashCode, which hashes the reference, not the data in array.
      return Arrays.hashCode(bytes);
    }
  }
}
