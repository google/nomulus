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
 * A Datastore {@link Entity Entity's} timestamped state.
 *
 * <p>For a new or updated Entity, its ProtocolBuffer bytes are stored along with its {@link Key}.
 * For a deleted entity, only its {@link Key} is stored, and the {@link #entityProtoBytes} is left
 * as null.
 *
 * <p>Note that {@link Optional java.util.Optional} is not serializable, therefore cannot be used as
 * property type in this class.
 */
@AutoValue
public abstract class VersionedEntity implements Serializable {

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

  public static Stream<VersionedEntity> fromManifest(CommitLogManifest manifest) {
    long commitTimeMillis = manifest.getCommitTime().getMillis();
    return manifest.getDeletions().stream()
        .map(com.googlecode.objectify.Key::getRaw)
        .map(key -> builder().commitTimeMills(commitTimeMillis).key(key).build());
  }

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

  /** Wraps a byte array and prevents it from being modified by its original owner. */
  static final class ImmutableBytes implements Serializable {
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
