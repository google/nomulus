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

package google.registry.xjc;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.TestDataHelper.loadFile;
import static java.nio.charset.StandardCharsets.UTF_8;

import google.registry.xjc.rdehost.XjcRdeHostElement;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link JaxbFragment}. */
class JaxbFragmentTest {

  private static final String HOST_FRAGMENT = loadFile(XjcObjectTest.class, "host_fragment.xml");

  /** Verifies that a {@link JaxbFragment} can be serialized and deserialized successfully. */
  @SuppressWarnings("unchecked")
  @Test
  void testJavaSerialization() throws Exception {
    // Load rdeHost xml fragment into a jaxb object, wrap it, marshal, unmarshal, verify host.
    // The resulting host name should be "ns1.example1.test", from the original xml fragment.
    try (InputStream source = new ByteArrayInputStream(HOST_FRAGMENT.getBytes(UTF_8))) {
      // Load xml
      JaxbFragment<XjcRdeHostElement> hostFragment =
          JaxbFragment.create(XjcXmlTransformer.unmarshal(XjcRdeHostElement.class, source));
      // Marshal
      ByteArrayOutputStream bout = new ByteArrayOutputStream();
      new ObjectOutputStream(bout).writeObject(hostFragment);
      // Unmarshal
      ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bout.toByteArray()));
      JaxbFragment<XjcRdeHostElement> restoredHostFragment =
          (JaxbFragment<XjcRdeHostElement>) in.readObject();
      // Verify host name
      assertThat(restoredHostFragment.getInstance().getValue().getName())
          .isEqualTo("ns1.example1.test");
    }
  }
}
