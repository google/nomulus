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

package google.registry.beam.datastore;

import static google.registry.beam.datastore.BulkDeletePipeline.discoverEntityKinds;
import static google.registry.beam.datastore.BulkDeletePipeline.getDeletionTags;
import static google.registry.beam.datastore.BulkDeletePipeline.getOneDeletionTag;

import com.google.common.base.Verify;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.datastore.v1.Entity;
import com.google.datastore.v1.Key;
import com.google.datastore.v1.Key.PathElement;
import google.registry.beam.TestPipelineExtension;
import google.registry.beam.datastore.BulkDeletePipeline.GenerateQueries;
import google.registry.beam.datastore.BulkDeletePipeline.SplitEntities;
import java.io.Serializable;
import java.util.Map;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.transforms.Count;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.View;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionTuple;
import org.apache.beam.sdk.values.PCollectionView;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.sdk.values.TupleTagList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link BulkDeletePipeline}. */
class BulkDeletePipelineTest implements Serializable {

  @RegisterExtension
  final transient TestPipelineExtension testPipeline =
      TestPipelineExtension.create().enableAbandonedNodeEnforcement(true);

  @Test
  void generateQueries() {
    PCollection<String> queries =
        testPipeline
            .apply("InjectKinds", Create.of("A", "B"))
            .apply("GenerateQueries", ParDo.of(new GenerateQueries()));

    PAssert.that(queries).containsInAnyOrder("select __key__ from `A`", "select __key__ from `B`");
    testPipeline.run();
  }

  @Test
  void mapKindsToTags() {
    TupleTagList tags = getDeletionTags(2);
    PCollection<String> kinds = testPipeline.apply("InjectKinds", Create.of("A", "B"));
    PCollection<Map<String, TupleTag<Entity>>> kindToTagMapping =
        BulkDeletePipeline.mapKindsToDeletionTags(kinds, tags);
    PAssert.that(kindToTagMapping)
        .containsInAnyOrder(
            ImmutableMap.of(
                "A", new TupleTag<Entity>("0"),
                "B", new TupleTag<Entity>("1")));
    testPipeline.run();
  }

  @Test
  void mapKindsToTags_fewerKindsThanTags() {
    TupleTagList tags = getDeletionTags(3);
    PCollection<String> kinds = testPipeline.apply("InjectKinds", Create.of("A", "B"));
    PCollection<Map<String, TupleTag<Entity>>> kindToTagMapping =
        BulkDeletePipeline.mapKindsToDeletionTags(kinds, tags);
    PAssert.that(kindToTagMapping)
        .containsInAnyOrder(
            ImmutableMap.of(
                "A", new TupleTag<Entity>("0"),
                "B", new TupleTag<Entity>("1")));
    testPipeline.run();
  }

  @Test
  void mapKindsToTags_moreKindsThanTags() {
    TupleTagList tags = getDeletionTags(2);
    PCollection<String> kinds = testPipeline.apply("InjectKinds", Create.of("A", "B", "C"));
    PCollection<Map<String, TupleTag<Entity>>> kindToTagMapping =
        BulkDeletePipeline.mapKindsToDeletionTags(kinds, tags);
    PAssert.that(kindToTagMapping)
        .containsInAnyOrder(
            ImmutableMap.of(
                "A", new TupleTag<Entity>("0"),
                "B", new TupleTag<Entity>("1"),
                "C", new TupleTag<Entity>("0")));
    testPipeline.run();
  }

  @Test
  void splitEntitiesByKind() {
    TupleTagList tags = getDeletionTags(2);
    PCollection<String> kinds = testPipeline.apply("InjectKinds", Create.of("A", "B"));
    PCollectionView<Map<String, TupleTag<Entity>>> kindToTagMapping =
        BulkDeletePipeline.mapKindsToDeletionTags(kinds, tags).apply(View.asSingleton());
    Entity entityA = createTestEntity("A", 1);
    Entity entityB = createTestEntity("B", 2);
    PCollection<Entity> entities =
        testPipeline.apply("InjectEntities", Create.of(entityA, entityB));
    PCollectionTuple allCollections =
        entities.apply(
            "SplitByKind",
            ParDo.of(new SplitEntities(kindToTagMapping))
                .withSideInputs(kindToTagMapping)
                .withOutputTags(getOneDeletionTag("placeholder"), tags));
    PAssert.that(allCollections.get((TupleTag<Entity>) tags.get(0))).containsInAnyOrder(entityA);
    PAssert.that(allCollections.get((TupleTag<Entity>) tags.get(1))).containsInAnyOrder(entityB);
    testPipeline.run();
  }

  private static Entity createTestEntity(String kind, long id) {
    return Entity.newBuilder()
        .setKey(Key.newBuilder().addPath(PathElement.newBuilder().setId(id).setKind(kind)))
        .build();
  }

  @Test
  @EnabledIfSystemProperty(named = "test.gcp_integration.env", matches = "\\S+")
  void discoverKindsFromDatastore() {
    String environmentName = System.getProperty("test.gcp_integration.env");
    String project = "domain-registry-" + environmentName;

    PCollection<String> kinds =
        testPipeline.apply("DiscoverEntityKinds", discoverEntityKinds(project));

    PAssert.that(kinds.apply(Count.globally()))
        .satisfies(
            longs -> {
              Verify.verify(Iterables.size(longs) == 1 && Iterables.getFirst(longs, -1L) > 0);
              return null;
            });
    testPipeline.run();
  }
}
