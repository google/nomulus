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

package google.registry.dns.writer;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link BaseDnsWriter}. */
class BaseDnsWriterTest {

  static class StubDnsWriter extends BaseDnsWriter {

    int commitCallCount = 0;

    @Override
    protected void commitUnchecked() {
      commitCallCount++;
    }

    @Override
    public void publishDomain(String domainName) {
      // No op
    }

    @Override
    public void publishHost(String hostName) {
      // No op
    }
  }

  @Test
  void test_cannotBeCalledTwice() {
    StubDnsWriter writer = new StubDnsWriter();
    assertThat(writer.commitCallCount).isEqualTo(0);
    writer.commit();
    assertThat(writer.commitCallCount).isEqualTo(1);
    IllegalStateException thrown = assertThrows(IllegalStateException.class, writer::commit);
    assertThat(thrown).hasMessageThat().isEqualTo("commit() has already been called");
    assertThat(writer.commitCallCount).isEqualTo(1);
  }
}
