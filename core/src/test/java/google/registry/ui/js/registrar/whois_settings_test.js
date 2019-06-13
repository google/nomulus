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

goog.setTestOnly();

goog.require('goog.dispose');
goog.require('goog.dom');
goog.require('goog.dom.classlist');
goog.require('goog.testing.MockControl');
goog.require('goog.testing.PropertyReplacer');
goog.require('goog.testing.asserts');
goog.require('goog.testing.jsunit');
goog.require('goog.testing.net.XhrIo');
goog.require('registry.registrar.ConsoleTestUtil');
goog.require('registry.testing');
goog.require('registry.util');


const $ = goog.dom.getRequiredElement;
const $$ = goog.dom.getRequiredElementByClass;
const stubs = new goog.testing.PropertyReplacer();

const test = {
  testXsrfToken: '༼༎෴ ༎༽',
  testClientId: 'testClientId',
  mockControl: new goog.testing.MockControl()
};


function setUp() {
  registry.testing.addToDocument('<div id="test"/>');
  registry.testing.addToDocument('<div class="kd-butterbar"/>');
  registry.registrar.ConsoleTestUtil.renderConsoleMain($('test'), {
    xsrfToken: test.testXsrfToken,
    clientId: test.testClientId,
  });
  stubs.setPath('goog.net.XhrIo', goog.testing.net.XhrIo);
  registry.registrar.ConsoleTestUtil.setup(test);
}


function tearDown() {
  goog.dispose(test.console);
  stubs.reset();
  goog.testing.net.XhrIo.cleanup();
  test.mockControl.$tearDown();
}


/**
 * Creates test registrar.
 * @return {!Object}
 */
function createTestRegistrar() {
  return {
    emailAddress: 'test2.ui@example.com',
    clientIdentifier: 'theRegistrar',
    ianaIdentifier: 1,
    icannReferralEmail: 'lol@sloth.test',
    whoisServer: 'foo.bar.baz',
    url: 'blah.blar',
    phoneNumber: '+1.2125650000',
    faxNumber: '+1.2125650001',
    localizedAddress: {
      street: ['111 Eighth Avenue', 'Eleventh Floor', 'lol'],
      city: 'New York',
      state: 'NY',
      zip: '10011',
      countryCode: 'US'
    }};
}


function testView() {
  registry.registrar.ConsoleTestUtil.visit(test, {
    path: 'whois-settings',
    xsrfToken: test.testXsrfToken,
    clientId: test.testClientId
  });
  const testRegistrar = createTestRegistrar();
  registry.testing.assertReqMockRsp(
      test.testXsrfToken,
      '/registrar-settings',
      {op: 'read', id: 'testClientId', args: {}},
      {
        status: 'SUCCESS',
        message: 'OK',
        results: [testRegistrar]
      });
  const parsed = registry.util.parseForm('item');
  parsed.ianaIdentifier = parseInt(parsed.ianaIdentifier);
  registry.testing.assertObjectEqualsPretty(testRegistrar, parsed);
}


function testEdit() {
  testView();
  registry.testing.click($('reg-app-btn-edit'));
  $('emailAddress').value = 'test2.ui@example.com';
  $('localizedAddress.street[0]').value = 'look at me i am';
  $('localizedAddress.street[1]').value = 'the mistress of the night';
  $('localizedAddress.street[2]').value = '';
  const parsed = registry.util.parseForm('item');
  parsed.readonly = false;
  registry.testing.click($('reg-app-btn-save'));
  registry.testing.assertReqMockRsp(
      test.testXsrfToken,
      '/registrar-settings',
      {op: 'update', id: 'testClientId', args: parsed},
      {
        status: 'SUCCESS',
        message: 'OK',
        results: [parsed]
      });
}


function testEditFieldError_insertsError() {
  testView();
  registry.testing.click($('reg-app-btn-edit'));
  $('phoneNumber').value = 'foo';
  const parsed = registry.util.parseForm('item');
  parsed.readonly = false;
  registry.testing.click($('reg-app-btn-save'));
  const errMsg = 'Carpe brunchus. --Pablo';
  registry.testing.assertReqMockRsp(
      test.testXsrfToken,
      '/registrar-settings',
      {op: 'update', id: 'testClientId', args: parsed},
      {
        status: 'ERROR',
        field: 'phoneNumber',
        message: errMsg
      });
  const msgBox = goog.dom.getNextElementSibling($('phoneNumber'));
  assertTrue(goog.dom.classlist.contains(msgBox, 'kd-errormessage'));
  assertTrue(goog.dom.classlist.contains($('phoneNumber'), 'kd-formerror'));
  assertEquals(errMsg, goog.dom.getTextContent(msgBox));
}


function testEditNonFieldError_showsButterBar() {
  testView();
  registry.testing.click($('reg-app-btn-edit'));
  const parsed = registry.util.parseForm('item');
  parsed.readonly = false;
  registry.testing.click($('reg-app-btn-save'));
  const errMsg = 'One must still have chaos in oneself to be able to give ' +
      'birth to a dancing star. --Nietzsche';
  registry.testing.assertReqMockRsp(
      test.testXsrfToken,
      '/registrar-settings',
      {op: 'update', id: 'testClientId', args: parsed},
      {
        status: 'ERROR',
        message: errMsg
      });
  assertEquals(errMsg, goog.dom.getTextContent($$('kd-butterbar-text')));
}
