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

goog.provide('registry.registrar.ConsoleTestUtil');
goog.setTestOnly('registry.registrar.ConsoleTestUtil');

goog.require('goog.History');
goog.require('goog.soy');
goog.require('registry.registrar.Console');
goog.require('registry.soy.registrar.console');


/**
 * Utility method that attaches mocks to a `TestCase`.  This was
 * originally in the ctor for ConsoleTest and should simply be
 * inherited but jstd_test breaks inheritance in test cases.
 * @param {!Object} test the test case to configure.
 */
registry.registrar.ConsoleTestUtil.setup = function(test) {
  test.historyMock = test.mockControl.createLooseMock(goog.History, true);
  test.mockControl.createConstructorMock(goog, 'History')()
      .$returns(test.historyMock);
};

/**
 * Utility method that renders the registry.soy.registrar.console.main element.
 *
 * This element has a lot of parameters. We use defaults everywhere, but you can
 * override them with 'opt_args'.
 *
 * @param {!Element} element the element whose content we are rendering into.
 * @param {?Object=} opt_args override for the default values of the soy params.
 */
registry.registrar.ConsoleTestUtil.renderConsoleMain = function(
    element, opt_args) {
  const args = opt_args || {};
  goog.soy.renderElement(element, registry.soy.registrar.console.main, {
    xsrfToken: args.xsrfToken || 'ignore',
    username: args.username || 'jart',
    logoutUrl: args.logoutUrl || 'https://logout.url.com',
    isAdmin: !!args.isAdmin,
    isOwner: !!args.isOwner,
    clientId: args.clientId || 'ignore',
    allClientIds: args.allClientIds || ['clientId1', 'clientId2'],
    logoFilename: args.logoFilename || 'logo.png',
    productName: args.productName || 'Nomulus',
    integrationEmail: args.integrationEmail || 'integration@example.com',
    supportEmail: args.supportEmail || 'support@example.com',
    announcementsEmail: args.announcementsEmail || 'announcement@example.com',
    supportPhoneNumber: args.supportPhoneNumber || '+1 (888) 555 0123',
    technicalDocsUrl: args.technicalDocsUrl || 'http://example.com/techdocs',
    environment: args.environment || 'UNITTEST',
    analyticsConfig: args.analyticsConfig || {googleAnalyticsId: null},
  });
};


/**
 * Simulates visiting a page on the console.  Sets path, then calls
 * `handleHashChange_`.
 * @param {!Object} test the test case to configure.
 * @param {?Object=} opt_args may include path.
 * @param {?Function=} opt_moar extra setup after called just before
 *     `$replayAll`.  See memegen/3437690.
 */
registry.registrar.ConsoleTestUtil.visit = function(
    test, opt_args, opt_moar) {
  opt_args = opt_args || {};
  opt_args.path = opt_args.path || '';
  opt_args.clientId = opt_args.clientId || 'dummyRegistrarId';
  opt_args.xsrfToken = opt_args.xsrfToken || 'dummyXsrfToken';
  opt_args.isAdmin = !!opt_args.isAdmin;
  opt_args.analyticsConfig =
      opt_args.analyticsConfig || {googleAnalyticsIds: null};

  // set the default isOwner to be the opposite of isAdmin.
  // That way, if we don't explicitly state them both we get what we'd expect:
  // {} -> OWNER (the "regular" case of a visitor to the console)
  // {isOwner:true} -> OWNER
  // {isAdmin:true} -> ADMIN (the "regular" case of an admin visitor)
  // {isOwner:true, isAdmin:true} -> OWNER + ADMIN together
  if (opt_args.isOwner === undefined) {
    opt_args.isOwner = !opt_args.isAdmin;
  }
  test.historyMock.$reset();
  test.historyMock.getToken().$returns(opt_args.path).$anyTimes();
  if (opt_moar) {
    opt_moar();
  }
  test.mockControl.$replayAll();
  /** @type {!registry.registrar.Console} */
  test.console = new registry.registrar.Console(opt_args);
  test.console.setUp();
  // Should be triggered via the History object in test.console.setUp(), but
  // since we're using a mock that isn't happening. So we call it manually.
  test.console.handleHashChange();
  test.mockControl.$verifyAll();
};
