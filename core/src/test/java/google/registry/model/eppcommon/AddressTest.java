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

package google.registry.model.eppcommon;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

/** Tests for {@link Address}. */
class AddressTest {

  @Test
  void onLoad_setsIndividualStreetLinesSuccessfully() {
    Address address = new Address();
    address.onLoad(ImmutableList.of("line1", "line2", "line3"));
    assertThat(address.streetLine1).isEqualTo("line1");
    assertThat(address.streetLine2).isEqualTo("line2");
    assertThat(address.streetLine3).isEqualTo("line3");
  }

  @Test
  void onLoad_setsOnlyNonNullStreetLines() {
    Address address = new Address();
    address.onLoad(ImmutableList.of("line1", "line2"));
    assertThat(address.streetLine1).isEqualTo("line1");
    assertThat(address.streetLine2).isEqualTo("line2");
    assertThat(address.streetLine3).isNull();
  }

  @Test
  void onLoad_doNothingIfInputIsNull() {
    Address address = new Address();
    address.onLoad(null);
    assertThat(address.streetLine1).isNull();
    assertThat(address.streetLine2).isNull();
    assertThat(address.streetLine3).isNull();
  }

  @Test
  void postLoad_setsStreetListSuccessfully() {
    Address address = new Address();
    address.streetLine1 = "line1";
    address.streetLine2 = "line2";
    address.streetLine3 = "line3";
    address.postLoad();
    assertThat(address.street).containsExactly("line1", "line2", "line3");
  }

  @Test
  void postLoad_setsOnlyNonNullStreetLines() {
    Address address = new Address();
    address.streetLine1 = "line1";
    address.streetLine2 = "line2";
    address.postLoad();
    assertThat(address.street).containsExactly("line1", "line2");
  }

  @Test
  void postLoad_doNothingIfInputIsNull() {
    Address address = new Address();
    address.postLoad();
    assertThat(address.street).isNull();
  }
}
