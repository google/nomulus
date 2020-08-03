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

package google.registry.mapreduce.inputs;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.google.appengine.tools.mapreduce.Input;
import com.google.appengine.tools.mapreduce.InputReader;
import com.googlecode.objectify.Key;
import google.registry.model.ofy.CommitLogBucket;
import google.registry.model.ofy.CommitLogManifest;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.DatastoreHelper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link CommitLogManifestInput}. */
final class CommitLogManifestInputTest {

  private static final DateTime DATE_TIME_OLD = DateTime.parse("2015-12-19T12:00Z");
  private static final DateTime DATE_TIME_OLD2 = DateTime.parse("2016-12-19T11:59Z");

  private static final DateTime DATE_TIME_THRESHOLD = DateTime.parse("2016-12-19T12:00Z");

  private static final DateTime DATE_TIME_NEW = DateTime.parse("2016-12-19T12:01Z");
  private static final DateTime DATE_TIME_NEW2 = DateTime.parse("2017-12-19T12:00Z");

  @RegisterExtension
  final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().build();

  @Test
  void testInputOlderThan_allFound() throws Exception {
    Set<Key<CommitLogManifest>> created = new HashSet<>();
    for (int i = 1; i <= 3; i++) {
      created.add(createManifest(CommitLogBucket.getBucketKey(i), DATE_TIME_OLD));
    }
    List<Key<CommitLogManifest>> seen = new ArrayList<>();
    Input<Key<CommitLogManifest>> input = new CommitLogManifestInput(DATE_TIME_THRESHOLD);
    for (InputReader<Key<CommitLogManifest>> reader : input.createReaders()) {
      reader.beginShard();
      reader.beginSlice();
      seen.add(reader.next());
      try {
        Key<CommitLogManifest> key = reader.next();
        assertWithMessage("Unexpected element: %s", key).fail();
      } catch (NoSuchElementException expected) {
      }
    }
    assertThat(seen).containsExactlyElementsIn(created);
  }

  @Test
  void testInputOlderThan_skipsNew() throws Exception {
    Set<Key<CommitLogManifest>> old = new HashSet<>();
    for (int i = 1; i <= 3; i++) {
      createManifest(CommitLogBucket.getBucketKey(i), DATE_TIME_NEW);
      createManifest(CommitLogBucket.getBucketKey(i), DATE_TIME_NEW2);
      old.add(createManifest(CommitLogBucket.getBucketKey(i), DATE_TIME_OLD));
      old.add(createManifest(CommitLogBucket.getBucketKey(i), DATE_TIME_OLD2));
    }
    List<Key<CommitLogManifest>> seen = new ArrayList<>();
    Input<Key<CommitLogManifest>> input = new CommitLogManifestInput(DATE_TIME_THRESHOLD);
    for (InputReader<Key<CommitLogManifest>> reader : input.createReaders()) {
      reader.beginShard();
      reader.beginSlice();
      try {
        Key<CommitLogManifest> key = null;
        for (int i = 0; i < 10; i++) {
          key = reader.next();
          seen.add(key);
        }
        assertWithMessage("Unexpected element: %s", key).fail();
      } catch (NoSuchElementException expected) {
      }
    }
    assertThat(seen).containsExactlyElementsIn(old);
  }

  @Test
  void testInputAll() throws Exception {
    Set<Key<CommitLogManifest>> created = new HashSet<>();
    for (int i = 1; i <= 3; i++) {
      created.add(createManifest(CommitLogBucket.getBucketKey(i), DATE_TIME_NEW));
      created.add(createManifest(CommitLogBucket.getBucketKey(i), DATE_TIME_NEW2));
      created.add(createManifest(CommitLogBucket.getBucketKey(i), DATE_TIME_OLD));
      created.add(createManifest(CommitLogBucket.getBucketKey(i), DATE_TIME_OLD2));
    }
    List<Key<CommitLogManifest>> seen = new ArrayList<>();
    Input<Key<CommitLogManifest>> input = new CommitLogManifestInput();
    for (InputReader<Key<CommitLogManifest>> reader : input.createReaders()) {
      reader.beginShard();
      reader.beginSlice();
      try {
        Key<CommitLogManifest> key = null;
        for (int i = 0; i < 10; i++) {
          key = reader.next();
          seen.add(key);
        }
        assertWithMessage("Unexpected element: %s", key).fail();
      } catch (NoSuchElementException expected) {
      }
    }
    assertThat(seen).containsExactlyElementsIn(created);
  }

  private static Key<CommitLogManifest> createManifest(
      Key<CommitLogBucket> parent, DateTime dateTime) {
    CommitLogManifest commitLogManifest = CommitLogManifest.create(parent, dateTime, null);
    DatastoreHelper.persistResource(commitLogManifest);
    return Key.create(commitLogManifest);
  }
}
