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

package google.registry.beam.initsql;

import static com.google.common.truth.Truth8.assertThat;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static org.apache.beam.sdk.values.TypeDescriptors.kvs;
import static org.apache.beam.sdk.values.TypeDescriptors.strings;

import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.EntityTranslator;
import com.google.appengine.api.datastore.Key;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;
import com.google.common.truth.Truth;
import com.google.storage.onestore.v3.OnestoreEntity.EntityProto;
import google.registry.backup.AppEngineEnvironment;
import google.registry.backup.VersionedEntity;
import java.io.Serializable;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.GroupByKey;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.TypeDescriptor;

/** Test helpers for populating SQL with Datastore backups. */
public final class InitSqlTestUtils {

  // Generates unique ids to distinguish reused transforms.
  private static final AtomicInteger TRANSFORM_ID_GEN = new AtomicInteger(0);

  /** Converts a Datastore {@link Entity} to an Objectify entity. */
  public static Object datastoreToOfyEntity(Entity entity) {
    return ofy().load().fromEntity(entity);
  }

  /** Serializes a Datastore {@link Entity} to byte array. */
  public static byte[] entityToBytes(Entity entity) {
    return EntityTranslator.convertToPb(entity).toByteArray();
  }

  /** Deserializes raw bytes into {@link Entity}. */
  public static Entity bytesToEntity(byte[] bytes) {
    EntityProto proto = new EntityProto();
    proto.parseFrom(bytes);
    return EntityTranslator.createFromPb(proto);
  }

  /**
   * Asserts that the {@code actual} {@link Collection} of {@link VersionedEntity VersionedEntities}
   * contains exactly the same elements in the {@code expected} array.
   *
   * <p>Each {@code expected} {@link KV key-value pair} refers to a versioned state of an Ofy
   * entity. The {@link KV#getKey key} is the timestamp, while the {@link KV#getValue value} is
   * either a Datastore {@link Entity} (for an existing entity) or a Datastore {@link Key} (for a
   * deleted entity).
   *
   * <p>The {@Entity} instances in both actual and expected data are converted to Objectify entities
   * so that value-equality checks can be performed. Datastore {@link Entity#equals Entity's equals
   * method} only checks key-equality.
   */
  @SafeVarargs
  public static void assertContainsExactlyElementsIn(
      Collection<VersionedEntity> actual, KV<Long, Serializable>... expected) {
    assertThat(actual.stream().map(InitSqlTestUtils::rawEntityToOfyWithTimestamp))
        .containsExactlyElementsIn(
            Stream.of(expected)
                .map(InitSqlTestUtils::expectedToOfyWithTimestamp)
                .collect(ImmutableList.toImmutableList()));
  }

  /**
   * Asserts that the {@code actual} {@link PCollection} of {@link VersionedEntity
   * VersionedEntities} contains exactly the same elements in the {@code expected} array.
   *
   * <p>This method makes assertions in the pipeline and only use {@link PAssert} on the result.
   * This has two advantages over {@code PAssert}:
   *
   * <ul>
   *   <li>It supports assertions on 'containsExactlyElementsIn', which is not available in {@code
   *       PAssert}.
   *   <li>It supports assertions on Objectify entities, which {@code PAssert} cannot not do.
   *       Compared with PAssert-compatible options like {@code google.registry.tools.EntityWrapper}
   *       or {@link EntityProto}, Objectify entities in Java give better-formatted error messages
   *       when assertions fail.
   * </ul>
   *
   * <p>Each {@code expected} {@link KV key-value pair} refers to a versioned state of an Ofy
   * entity. The {@link KV#getKey key} is the timestamp, while the {@link KV#getValue value} is
   * either a Datastore {@link Entity} (for an existing entity) or a Datastore {@link Key} (for a
   * deleted entity).
   *
   * <p>The {@Entity} instances in both actual and expected data are converted to Objectify entities
   * so that value-equality checks can be performed. Datastore {@link Entity#equals Entity's equals
   * method} only checks key-equality.
   */
  @SafeVarargs
  public static void assertContainsExactlyElementsIn(
      PCollection<VersionedEntity> actual, KV<Long, Serializable>... expected) {
    PCollection<String> errMsgs =
        actual
            .apply(
                "MapElements_" + TRANSFORM_ID_GEN.getAndIncrement(),
                MapElements.into(kvs(strings(), TypeDescriptor.of(VersionedEntity.class)))
                    .via(rawEntity -> KV.of("The One Key", rawEntity)))
            .apply("GroupByKey_" + TRANSFORM_ID_GEN.getAndIncrement(), GroupByKey.create())
            .apply(
                "assertContainsExactlyElementsIn_" + TRANSFORM_ID_GEN.getAndIncrement(),
                ParDo.of(
                    new DoFn<KV<String, Iterable<VersionedEntity>>, String>() {
                      @ProcessElement
                      public void processElement(
                          @Element KV<String, Iterable<VersionedEntity>> input,
                          OutputReceiver<String> out) {
                        try (AppEngineEnvironment env = new AppEngineEnvironment()) {
                          ImmutableList<KV<Long, Object>> actual =
                              Streams.stream(input.getValue())
                                  .map(InitSqlTestUtils::rawEntityToOfyWithTimestamp)
                                  .collect(ImmutableList.toImmutableList());
                          try {
                            Truth.assertThat(actual)
                                .containsExactlyElementsIn(
                                    Stream.of(expected)
                                        .map(InitSqlTestUtils::expectedToOfyWithTimestamp)
                                        .collect(ImmutableList.toImmutableList()));
                          } catch (AssertionError e) {
                            out.output(e.toString());
                          }
                        }
                      }
                    }));
    PAssert.that(errMsgs).empty();
  }

  private static KV<Long, Object> rawEntityToOfyWithTimestamp(VersionedEntity rawEntity) {
    return KV.of(
        rawEntity.commitTimeMills(),
        rawEntity.getEntity().map(InitSqlTestUtils::datastoreToOfyEntity).orElse(rawEntity.key()));
  }

  private static KV<Long, Object> expectedToOfyWithTimestamp(KV<Long, Serializable> kv) {
    return KV.of(
        kv.getKey(),
        kv.getValue() instanceof Key
            ? kv.getValue()
            : datastoreToOfyEntity((Entity) kv.getValue()));
  }
}
