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

package google.registry.tools.server;

import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.createTlds;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;

import com.google.common.collect.ImmutableSet;
import google.registry.testing.FakeClock;
import java.util.Optional;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ListDomainsAction}. */
class ListDomainsActionTest extends ListActionTestCase {

  private ListDomainsAction action;

  @BeforeEach
  void beforeEach() {
    createTld("foo");
    action = new ListDomainsAction();
    action.clock = new FakeClock(DateTime.parse("2018-01-01TZ"));
    action.limit = Integer.MAX_VALUE;
  }

  @Test
  void testRun_invalidRequest_missingTlds() {
    action.tlds = ImmutableSet.of();
    testRunError(
        action,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        "^Must specify TLDs to query$");
  }

  @Test
  void testRun_invalidRequest_invalidTld() {
    action.tlds = ImmutableSet.of("%%%badtld%%%");
    testRunError(
        action,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        "^TLDs do not exist: %%%badtld%%%$");
  }

  @Test
  void testRun_noParameters() {
    action.tlds = ImmutableSet.of("foo");
    testRunSuccess(action, null, null, null);
  }

  @Test
  void testRun_twoLinesWithIdOnly() {
    action.tlds = ImmutableSet.of("foo");
    createTlds("bar", "sim");
    persistActiveDomain("dontlist.bar", DateTime.parse("2015-02-14T15:15:15Z"));
    persistActiveDomain("example1.foo", DateTime.parse("2015-02-15T15:15:15Z"));
    persistActiveDomain("example2.foo", DateTime.parse("2015-02-16T15:15:15Z"));
    persistActiveDomain("notlistedaswell.sim", DateTime.parse("2015-02-17T15:15:15Z"));
    // Only list the two domains in .foo, not the .bar or .sim ones.
    testRunSuccess(
        action,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        "^example1.foo$",
        "^example2.foo$");
  }

  @Test
  void testRun_multipleTlds() {
    action.tlds = ImmutableSet.of("bar", "foo");
    createTlds("bar", "sim");
    persistActiveDomain("dolist.bar", DateTime.parse("2015-01-15T15:15:15Z"));
    persistActiveDomain("example1.foo", DateTime.parse("2015-02-15T15:15:15Z"));
    persistActiveDomain("example2.foo", DateTime.parse("2015-03-15T15:15:15Z"));
    persistActiveDomain("notlistedaswell.sim", DateTime.parse("2015-04-15T15:15:15Z"));
    testRunSuccess(
        action,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        "^dolist.bar",
        "^example1.foo$",
        "^example2.foo$");
  }

  @Test
  void testRun_moreTldsThanMaxNumSubqueries() {
    ListDomainsAction.maxNumSubqueries = 2;
    createTlds("baa", "bab", "bac", "bad");
    action.tlds = ImmutableSet.of("baa", "bab", "bac", "bad");
    action.limit = 4;
    persistActiveDomain("domain1.baa", DateTime.parse("2010-03-04T16:00:00Z"));
    persistActiveDomain("domain2.bab", DateTime.parse("2009-03-04T16:00:00Z"));
    persistActiveDomain("domain3.bac", DateTime.parse("2011-03-04T16:00:00Z"));
    persistActiveDomain("domain4.bad", DateTime.parse("2010-06-04T16:00:00Z"));
    persistActiveDomain("domain5.baa", DateTime.parse("2008-01-04T16:00:00Z"));
    // Since the limit is 4, expect all but domain5.baa (the oldest), sorted by creationTime asc.
    testRunSuccess(
        action,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        "^domain2.bab$",
        "^domain1.baa$",
        "^domain4.bad$",
        "^domain3.bac$");
  }

  @Test
  void testRun_twoLinesWithIdOnlyNoHeader() {
    action.tlds = ImmutableSet.of("foo");
    persistActiveDomain("example1.foo", DateTime.parse("2010-03-04T16:00:00Z"));
    persistActiveDomain("example2.foo", DateTime.parse("2011-03-04T16:00:00Z"));
    testRunSuccess(
        action,
        Optional.empty(),
        Optional.of(false),
        Optional.empty(),
        "^example1.foo$",
        "^example2.foo$");
  }

  @Test
  void testRun_twoLinesWithIdOnlyExplicitHeader() {
    action.tlds = ImmutableSet.of("foo");
    persistActiveDomain("example1.foo", DateTime.parse("2010-03-04T16:00:00Z"));
    persistActiveDomain("example2.foo", DateTime.parse("2011-03-04T16:00:00Z"));
    testRunSuccess(
        action,
        Optional.empty(),
        Optional.of(true),
        Optional.empty(),
        "^fullyQualifiedDomainName$",
        "^-+\\s*$",
        "^example1.foo\\s*$",
        "^example2.foo\\s*$");
  }

  @Test
  void testRun_twoLinesWithRepoId() {
    action.tlds = ImmutableSet.of("foo");
    persistActiveDomain("example1.foo", DateTime.parse("2010-03-04T16:00:00Z"));
    persistActiveDomain("example3.foo", DateTime.parse("2011-03-04T16:00:00Z"));
    testRunSuccess(
        action,
        Optional.of("repoId"),
        Optional.empty(),
        Optional.empty(),
        "^fullyQualifiedDomainName\\s+repoId\\s*$",
        "^-+\\s+-+\\s*$",
        "^example1.foo\\s+2-FOO\\s*$",
        "^example3.foo\\s+4-FOO\\s*$");
  }

  @Test
  void testRun_twoLinesWithRepoIdNoHeader() {
    action.tlds = ImmutableSet.of("foo");
    persistActiveDomain("example1.foo", DateTime.parse("2010-03-04T16:00:00Z"));
    persistActiveDomain("example3.foo", DateTime.parse("2011-03-04T16:00:00Z"));
    testRunSuccess(
        action,
        Optional.of("repoId"),
        Optional.of(false),
        Optional.empty(),
        "^example1.foo  2-FOO$",
        "^example3.foo  4-FOO$");
  }

  @Test
  void testRun_twoLinesWithRepoIdExplicitHeader() {
    action.tlds = ImmutableSet.of("foo");
    persistActiveDomain("example1.foo", DateTime.parse("2010-03-04T16:00:00Z"));
    persistActiveDomain("example3.foo", DateTime.parse("2011-03-04T16:00:00Z"));
    testRunSuccess(
        action,
        Optional.of("repoId"),
        Optional.of(true),
        Optional.empty(),
        "^fullyQualifiedDomainName\\s+repoId\\s*$",
        "^-+\\s+-+\\s*$",
        "^example1.foo\\s+2-FOO\\s*$",
        "^example3.foo\\s+4-FOO\\s*$");
  }

  @Test
  void testRun_twoLinesWithWildcard() {
    action.tlds = ImmutableSet.of("foo");
    persistActiveDomain("example1.foo", DateTime.parse("2010-03-04T16:00:00Z"));
    persistActiveDomain("example3.foo", DateTime.parse("2010-03-05T16:00:00Z"));
    testRunSuccess(
        action,
        Optional.of("*"),
        Optional.empty(),
        Optional.empty(),
        "^fullyQualifiedDomainName\\s+.*repoId",
        "^-+\\s+-+",
        "^example1.foo\\s+.*2-FOO",
        "^example3.foo\\s+.*4-FOO");
  }

  @Test
  void testRun_twoLinesWithWildcardAndAnotherField() {
    action.tlds = ImmutableSet.of("foo");
    persistActiveDomain("example1.foo", DateTime.parse("2010-03-04T16:00:00Z"));
    persistActiveDomain("example3.foo", DateTime.parse("2010-03-04T17:00:00Z"));
    testRunSuccess(
        action,
        Optional.of("*,repoId"),
        Optional.empty(),
        Optional.empty(),
        "^fullyQualifiedDomainName\\s+.*repoId",
        "^-+\\s+-+",
        "^example1.foo\\s+.*2-FOO",
        "^example3.foo\\s+.*4-FOO");
  }

  @Test
  void testRun_withBadField_returnsError() {
    action.tlds = ImmutableSet.of("foo");
    persistActiveDomain("example2.foo");
    persistActiveDomain("example1.foo");
    testRunError(
        action,
        Optional.of("badfield"),
        Optional.empty(),
        Optional.empty(),
        "^Field 'badfield' not found - recognized fields are:");
  }

  @Test
  void testRun_limitFiltersOutOldestDomains() {
    createTlds("bar", "baz");
    action.tlds = ImmutableSet.of("foo", "bar");
    action.limit = 2;
    persistActiveDomain("example4.foo", DateTime.parse("2017-04-01TZ"));
    persistActiveDomain("example1.foo", DateTime.parse("2017-01-01TZ"));
    persistActiveDomain("example2.bar", DateTime.parse("2017-02-01TZ"));
    persistActiveDomain("example3.bar", DateTime.parse("2017-03-01TZ"));
    persistActiveDomain("example5.baz", DateTime.parse("2018-01-01TZ"));
    testRunSuccess(
        action,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        "^example3.bar$",
        "^example4.foo$");
  }
}
