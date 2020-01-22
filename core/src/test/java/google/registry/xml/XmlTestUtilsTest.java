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

package google.registry.xml;

import static google.registry.testing.TestDataHelper.loadFile;
import static google.registry.xml.XmlTestUtils.assertXmlEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link XmlTestUtils}. */
@RunWith(JUnit4.class)
public class XmlTestUtilsTest {

  void runTest(String file1, String file2) throws Exception {
    assertXmlEquals(loadFile(getClass(), file1), loadFile(getClass(), file2));
  }

  @Test
  public void testSelfEquality() throws Exception {
    runTest("simple.xml", "simple.xml");
  }

  @Test
  public void testInequality() {
    assertThrows(
        AssertionError.class, () -> runTest("simple.xml", "twoextensions_feeThenLaunch.xml"));
  }

  @Test
  public void testMultipleElementsInDifferentNamespaces() throws Exception {
    runTest("twoextensions_feeThenLaunch.xml", "twoextensions_launchThenFee.xml");
  }

  @Test
  public void testMultipleElementsInDifferentNamespaces_differentValues() {
    assertThrows(
        AssertionError.class,
        () -> runTest("twoextensions_feeThenLaunch.xml", "twoextensions_feeThenLaunch2.xml"));
  }
}
