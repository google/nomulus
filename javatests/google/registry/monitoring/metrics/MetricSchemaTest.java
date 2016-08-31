// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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

package google.registry.monitoring.metrics;

import com.google.common.collect.ImmutableSet;
import google.registry.monitoring.metrics.MetricSchema.Kind;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link MetricSchema}. */
@RunWith(JUnit4.class)
public class MetricSchemaTest {

  @Rule
  public final ExpectedException thrown = ExpectedException.none();

  @Test
  public void testCreate_blankNameField_throwsException() {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("Name must not be blank");
    MetricSchema.create(
        "", "description", "valueDisplayName", Kind.GAUGE, ImmutableSet.<LabelDescriptor>of());
  }

  @Test
  public void testCreate_blankDescriptionField_throwsException() {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("Description must not be blank");
    MetricSchema.create(
        "/name", "", "valueDisplayName", Kind.GAUGE, ImmutableSet.<LabelDescriptor>of());
  }

  @Test
  public void testCreate_blankValueDisplayNameField_throwsException() {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("Value Display Name must not be empty");
    MetricSchema.create("/name", "description", "", Kind.GAUGE, ImmutableSet.<LabelDescriptor>of());
  }

  @Test
  public void testCreate_nakedNames_throwsException() {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("Name must be URL-like and start with a '/'");
    MetricSchema.create(
        "foo", "description", "valueDisplayName", Kind.GAUGE, ImmutableSet.<LabelDescriptor>of());
  }
}
