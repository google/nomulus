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

package google.registry.tools;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static org.junit.Assert.assertThrows;

import google.registry.model.tmch.ClaimsListShard;
import java.io.FileNotFoundException;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link UploadClaimsListCommand}. */
class UploadClaimsListCommandTest extends CommandTestCase<UploadClaimsListCommand> {

  @Test
  void testSuccess() throws Exception {
    String filename = writeToTmpFile(
      "1,2012-08-16T00:00:00.0Z",
      "DNL,lookup-key,insertion-datetime",
      "example,2013041500/2/6/9/rJ1NrDO92vDsAzf7EQzgjX4R0000000001,2010-07-14T00:00:00.0Z",
      "another-example,2013041500/6/A/5/alJAqG2vI2BmCv5PfUvuDkf40000000002,2012-08-16T00:00:00.0Z",
      "anotherexample,2013041500/A/C/7/rHdC4wnrWRvPY6nneCVtQhFj0000000003,2011-08-16T12:00:00.0Z");
    runCommand("--force", filename);

    ClaimsListShard claimsList = ClaimsListShard.get();
    assertThat(claimsList.getCreationTime()).isEqualTo(DateTime.parse("2012-08-16T00:00:00.0Z"));
    assertThat(claimsList.getClaimKey("example"))
        .hasValue("2013041500/2/6/9/rJ1NrDO92vDsAzf7EQzgjX4R0000000001");
    assertThat(claimsList.getClaimKey("another-example"))
        .hasValue("2013041500/6/A/5/alJAqG2vI2BmCv5PfUvuDkf40000000002");
    assertThat(claimsList.getClaimKey("anotherexample"))
        .hasValue("2013041500/A/C/7/rHdC4wnrWRvPY6nneCVtQhFj0000000003");
  }

  @Test
  void testFailure_wrongNumberOfFieldsOnFirstLine() throws Exception {
    String filename = writeToTmpFile(
      "1,2012-08-16T00:00:00.0Z,random-extra-field",
      "DNL,lookup-key,insertion-datetime",
      "example,2013041500/2/6/9/rJ1NrDO92vDsAzf7EQzgjX4R0000000001,2010-07-14T00:00:00.0Z",
      "another-example,2013041500/6/A/5/alJAqG2vI2BmCv5PfUvuDkf40000000002,2012-08-16T00:00:00.0Z",
      "anotherexample,2013041500/A/C/7/rHdC4wnrWRvPY6nneCVtQhFj0000000003,2011-08-16T12:00:00.0Z");
    assertThrows(IllegalArgumentException.class, () -> runCommand("--force", filename));
  }

  @Test
  void testFailure_wrongVersion() throws Exception {
    String filename = writeToTmpFile(
      "2,2012-08-16T00:00:00.0Z",
      "DNL,lookup-key,insertion-datetime",
      "example,2013041500/2/6/9/rJ1NrDO92vDsAzf7EQzgjX4R0000000001,2010-07-14T00:00:00.0Z",
      "another-example,2013041500/6/A/5/alJAqG2vI2BmCv5PfUvuDkf40000000002,2012-08-16T00:00:00.0Z",
      "anotherexample,2013041500/A/C/7/rHdC4wnrWRvPY6nneCVtQhFj0000000003,2011-08-16T12:00:00.0Z");
    assertThrows(IllegalArgumentException.class, () -> runCommand("--force", filename));
  }

  @Test
  void testFailure_badCreationTime() throws Exception {
    String filename = writeToTmpFile(
      "1,foo",
      "DNL,lookup-key,insertion-datetime",
      "example,2013041500/2/6/9/rJ1NrDO92vDsAzf7EQzgjX4R0000000001,2010-07-14T00:00:00.0Z",
      "another-example,2013041500/6/A/5/alJAqG2vI2BmCv5PfUvuDkf40000000002,2012-08-16T00:00:00.0Z",
      "anotherexample,2013041500/A/C/7/rHdC4wnrWRvPY6nneCVtQhFj0000000003,2011-08-16T12:00:00.0Z");
    assertThrows(IllegalArgumentException.class, () -> runCommand("--force", filename));
  }

  @Test
  void testFailure_badFirstHeader() throws Exception {
    String filename = writeToTmpFile(
      "1,foo",
      "SNL,lookup-key,insertion-datetime",
      "example,2013041500/2/6/9/rJ1NrDO92vDsAzf7EQzgjX4R0000000001,2010-07-14T00:00:00.0Z",
      "another-example,2013041500/6/A/5/alJAqG2vI2BmCv5PfUvuDkf40000000002,2012-08-16T00:00:00.0Z",
      "anotherexample,2013041500/A/C/7/rHdC4wnrWRvPY6nneCVtQhFj0000000003,2011-08-16T12:00:00.0Z");
    assertThrows(IllegalArgumentException.class, () -> runCommand("--force", filename));
  }

  @Test
  void testFailure_badSecondHeader() throws Exception {
    String filename = writeToTmpFile(
      "1,foo",
      "DNL,lookup-keys,insertion-datetime",
      "example,2013041500/2/6/9/rJ1NrDO92vDsAzf7EQzgjX4R0000000001,2010-07-14T00:00:00.0Z",
      "another-example,2013041500/6/A/5/alJAqG2vI2BmCv5PfUvuDkf40000000002,2012-08-16T00:00:00.0Z",
      "anotherexample,2013041500/A/C/7/rHdC4wnrWRvPY6nneCVtQhFj0000000003,2011-08-16T12:00:00.0Z");
    assertThrows(IllegalArgumentException.class, () -> runCommand("--force", filename));
  }

  @Test
  void testFailure_badThirdHeader() throws Exception {
    String filename = writeToTmpFile(
      "1,foo",
      "DNL,lookup-key,insertion-datetimes",
      "example,2013041500/2/6/9/rJ1NrDO92vDsAzf7EQzgjX4R0000000001,2010-07-14T00:00:00.0Z",
      "another-example,2013041500/6/A/5/alJAqG2vI2BmCv5PfUvuDkf40000000002,2012-08-16T00:00:00.0Z",
      "anotherexample,2013041500/A/C/7/rHdC4wnrWRvPY6nneCVtQhFj0000000003,2011-08-16T12:00:00.0Z");
    assertThrows(IllegalArgumentException.class, () -> runCommand("--force", filename));
  }

  @Test
  void testFailure_wrongNumberOfHeaders() throws Exception {
    String filename = writeToTmpFile(
      "1,foo",
      "DNL,lookup-key,insertion-datetime,extra-field",
      "example,2013041500/2/6/9/rJ1NrDO92vDsAzf7EQzgjX4R0000000001,2010-07-14T00:00:00.0Z",
      "another-example,2013041500/6/A/5/alJAqG2vI2BmCv5PfUvuDkf40000000002,2012-08-16T00:00:00.0Z",
      "anotherexample,2013041500/A/C/7/rHdC4wnrWRvPY6nneCVtQhFj0000000003,2011-08-16T12:00:00.0Z");
    assertThrows(IllegalArgumentException.class, () -> runCommand("--force", filename));
  }

  @Test
  void testFailure_wrongNumberOfFields() throws Exception {
    String filename = writeToTmpFile(
      "1,foo",
      "DNL,lookup-key,insertion-datetime",
      "example,2013041500/2/6/9/rJ1NrDO92vDsAzf7EQzgjX4R0000000001,2010-07-14T00:00:00.0Z,extra",
      "another-example,2013041500/6/A/5/alJAqG2vI2BmCv5PfUvuDkf40000000002,2012-08-16T00:00:00.0Z",
      "anotherexample,2013041500/A/C/7/rHdC4wnrWRvPY6nneCVtQhFj0000000003,2011-08-16T12:00:00.0Z");
    assertThrows(IllegalArgumentException.class, () -> runCommand("--force", filename));
  }

  @Test
  void testFailure_badInsertionTime() throws Exception {
    String filename = writeToTmpFile(
      "1,foo",
      "DNL,lookup-key,insertion-datetime",
      "example,2013041500/2/6/9/rJ1NrDO92vDsAzf7EQzgjX4R0000000001,2010-07-14T00:00:00.0Z",
      "another-example,2013041500/6/A/5/alJAqG2vI2BmCv5PfUvuDkf40000000002,foo",
      "anotherexample,2013041500/A/C/7/rHdC4wnrWRvPY6nneCVtQhFj0000000003,2011-08-16T12:00:00.0Z");
    assertThrows(IllegalArgumentException.class, () -> runCommand("--force", filename));
  }

  @Test
  void testFailure_fileDoesNotExist() {
    assertThrows(FileNotFoundException.class, () -> runCommand("--force", "nonexistent_file.csv"));
  }

  @Test
  void testFailure_noFileNamePassed() {
    assertThrows(IllegalArgumentException.class, () -> runCommand("--force"));
  }

  @Test
  void testFailure_tooManyArguments() {
    assertThrows(IllegalArgumentException.class, () -> runCommand("--force", "foo", "bar"));
  }
}
