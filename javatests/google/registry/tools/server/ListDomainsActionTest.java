// Copyright 2016 The Nomulus Authors. All Rights Reserved.
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

package google.registry.tools.server;

import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.createTlds;
import static google.registry.testing.DatastoreHelper.persistActiveDomain;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import google.registry.testing.FakeClock;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link ListDomainsAction}.
 */
@RunWith(JUnit4.class)
public class ListDomainsActionTest extends ListActionTestCase {

  ListDomainsAction action;

  @Before
  public void init() throws Exception {
    createTld("foo");
    action = new ListDomainsAction();
    action.clock = new FakeClock(DateTime.parse("2000-01-01TZ"));
  }

  @Test
  public void testRun_invalidRequest_missingTlds() throws Exception {
    action.tlds = ImmutableSet.of();
    testRunError(
        action,
        null,
        null,
        null,
        "^Must specify TLDs to query$");
  }

  @Test
  public void testRun_invalidRequest_invalidTld() throws Exception {
    action.tlds = ImmutableSet.of("%%%badtld%%%");
    testRunError(
        action,
        null,
        null,
        null,
        "^TLD %%%badtld%%% does not exist$");
  }

  @Test
  public void testRun_noParameters() throws Exception {
    action.tlds = ImmutableSet.of("foo");
    testRunSuccess(
        action,
        null,
        null,
        null);
  }

  @Test
  public void testRun_twoLinesWithIdOnly() throws Exception {
    action.tlds = ImmutableSet.of("foo");
    createTlds("bar", "sim");
    persistActiveDomain("dontlist.bar");
    persistActiveDomain("example1.foo");
    persistActiveDomain("example2.foo");
    persistActiveDomain("notlistedaswell.sim");
    // Only list the two domains in .foo, not the .bar or .sim ones.
    testRunSuccess(
        action,
        null,
        null,
        null,
        "^example1.foo$",
        "^example2.foo$");
  }

  @Test
  public void testRun_multipleTlds() throws Exception {
    action.tlds = ImmutableSet.of("bar", "foo");
    createTlds("bar", "sim");
    persistActiveDomain("dolist.bar");
    persistActiveDomain("example1.foo");
    persistActiveDomain("example2.foo");
    persistActiveDomain("notlistedaswell.sim");
    testRunSuccess(
        action,
        null,
        null,
        null,
        "^dolist.bar",
        "^example1.foo$",
        "^example2.foo$");
  }


  @Test
  public void testRun_twoLinesWithIdOnlyNoHeader() throws Exception {
    action.tlds = ImmutableSet.of("foo");
    persistActiveDomain("example1.foo");
    persistActiveDomain("example2.foo");
    testRunSuccess(
        action,
        null,
        Optional.of(false),
        null,
        "^example1.foo$",
        "^example2.foo$");
  }

  @Test
  public void testRun_twoLinesWithIdOnlyExplicitHeader() throws Exception {
    action.tlds = ImmutableSet.of("foo");
    persistActiveDomain("example1.foo");
    persistActiveDomain("example2.foo");
    testRunSuccess(
        action,
        null,
        Optional.of(true),
        null,
        "^fullyQualifiedDomainName$",
        "^-+\\s*$",
        "^example1.foo\\s*$",
        "^example2.foo\\s*$");
  }

  @Test
  public void testRun_twoLinesWithRepoId() throws Exception {
    action.tlds = ImmutableSet.of("foo");
    persistActiveDomain("example1.foo");
    persistActiveDomain("example3.foo");
    testRunSuccess(
        action,
        Optional.of("repoId"),
        null,
        null,
        "^fullyQualifiedDomainName\\s+repoId\\s*$",
        "^-+\\s+-+\\s*$",
        "^example1.foo\\s+2-FOO\\s*$",
        "^example3.foo\\s+4-FOO\\s*$");
  }

  @Test
  public void testRun_twoLinesWithRepoIdNoHeader() throws Exception {
    action.tlds = ImmutableSet.of("foo");
    persistActiveDomain("example1.foo");
    persistActiveDomain("example3.foo");
    testRunSuccess(
        action,
        Optional.of("repoId"),
        Optional.of(false),
        null,
        "^example1.foo  2-FOO$",
        "^example3.foo  4-FOO$");
  }

  @Test
  public void testRun_twoLinesWithRepoIdExplicitHeader() throws Exception {
    action.tlds = ImmutableSet.of("foo");
    persistActiveDomain("example1.foo");
    persistActiveDomain("example3.foo");
    testRunSuccess(
        action,
        Optional.of("repoId"),
        Optional.of(true),
        null,
        "^fullyQualifiedDomainName\\s+repoId\\s*$",
        "^-+\\s+-+\\s*$",
        "^example1.foo\\s+2-FOO\\s*$",
        "^example3.foo\\s+4-FOO\\s*$");
  }

  @Test
  public void testRun_twoLinesWithWildcard() throws Exception {
    action.tlds = ImmutableSet.of("foo");
    persistActiveDomain("example1.foo");
    persistActiveDomain("example3.foo");
    testRunSuccess(
        action,
        Optional.of("*"),
        null,
        null,
        "^fullyQualifiedDomainName\\s+.*repoId",
        "^-+\\s+-+",
        "^example1.foo\\s+.*2-FOO",
        "^example3.foo\\s+.*4-FOO");
  }

  @Test
  public void testRun_twoLinesWithWildcardAndAnotherField() throws Exception {
    action.tlds = ImmutableSet.of("foo");
    persistActiveDomain("example1.foo");
    persistActiveDomain("example3.foo");
    testRunSuccess(
        action,
        Optional.of("*,repoId"),
        null,
        null,
        "^fullyQualifiedDomainName\\s+.*repoId",
        "^-+\\s+-+",
        "^example1.foo\\s+.*2-FOO",
        "^example3.foo\\s+.*4-FOO");
  }

  @Test
  public void testRun_withBadField_returnsError() throws Exception {
    action.tlds = ImmutableSet.of("foo");
    persistActiveDomain("example2.foo");
    persistActiveDomain("example1.foo");
    testRunError(
        action,
        Optional.of("badfield"),
        null,
        null,
        "^Field 'badfield' not found - recognized fields are:");
  }
}
