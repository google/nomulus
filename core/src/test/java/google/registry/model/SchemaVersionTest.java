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

package google.registry.model;

import google.registry.testing.AppEngineRule;
import google.registry.testing.GoldenFileTestHelper;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link SchemaVersion}.
 *
 * <p>If the test breaks, the instructions below will be printed.
 */
@RunWith(JUnit4.class)
public class SchemaVersionTest {

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder().withDatastoreAndCloudSql().build();

  @Test
  public void testGoldenSchemaFile() {
    GoldenFileTestHelper.assertThat(SchemaVersion.getSchema())
        .describedAs("Datastore schema")
        .createdByNomulusCommand("get_schema")
        .isEqualToGolden(SchemaVersionTest.class, "schema.txt");
  }
}
