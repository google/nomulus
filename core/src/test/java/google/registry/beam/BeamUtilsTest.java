// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

package google.registry.beam;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.beam.sdk.io.gcp.bigquery.SchemaAndRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link BeamUtils} */
class BeamUtilsTest {

  private static final String GENERIC_SCHEMA =
      "{\"name\": \"AnObject\", "
          + "\"type\": \"record\", "
          + "\"fields\": ["
          + "{\"name\": \"aString\", \"type\": \"string\"},"
          + "{\"name\": \"aFloat\", \"type\": \"float\"}"
          + "]}";

  private SchemaAndRecord schemaAndRecord;

  @BeforeEach
  void beforeEach() {
    // Create a record with a given JSON schema.
    GenericRecord record = new GenericData.Record(new Schema.Parser().parse(GENERIC_SCHEMA));
    record.put("aString", "hello world");
    record.put("aFloat", 2.54);
    schemaAndRecord = new SchemaAndRecord(record, null);
  }

  @Test
  void testExtractField_fieldExists_returnsExpectedStringValues() {
    assertThat(BeamUtils.extractField(schemaAndRecord.getRecord(), "aString"))
        .isEqualTo("hello world");
    assertThat(BeamUtils.extractField(schemaAndRecord.getRecord(), "aFloat")).isEqualTo("2.54");
  }

  @Test
  void testExtractField_fieldDoesntExist_returnsNull() {
    schemaAndRecord.getRecord().put("aFloat", null);
    assertThat(BeamUtils.extractField(schemaAndRecord.getRecord(), "aFloat")).isEqualTo("null");
    assertThat(BeamUtils.extractField(schemaAndRecord.getRecord(), "missing")).isEqualTo("null");
  }

  @Test
  void testCheckFieldsNotNull_noExceptionIfAllPresent() {
    BeamUtils.checkFieldsNotNull(ImmutableList.of("aString", "aFloat"), schemaAndRecord);
  }

  @Test
  void testCheckFieldsNotNull_fieldMissing_throwsException() {
    IllegalStateException expected =
        assertThrows(
            IllegalStateException.class,
            () ->
                BeamUtils.checkFieldsNotNull(
                    ImmutableList.of("aString", "aFloat", "notAField"), schemaAndRecord));
    assertThat(expected)
        .hasMessageThat()
        .isEqualTo(
            "Read unexpected null value for field(s) notAField for record "
                + "{\"aString\": \"hello world\", \"aFloat\": 2.54}");
  }
}
