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
goog.require('goog.testing.MockControl');
goog.require('goog.testing.PropertyReplacer');
goog.require('goog.testing.net.XhrIo');
goog.require('registry.registrar.ConsoleTestUtil');
goog.require('registry.testing');
goog.require('registry.util');

describe('security settings test', function() {
  const $ = goog.dom.getRequiredElement;
  const stubs = new goog.testing.PropertyReplacer();

  const expectedRegistrar = {
    ipAddressAllowList: [],
    phonePasscode: '12345',
    clientCertificate: null,
    clientCertificateHash: null,
    failoverClientCertificate: null
  };

  const test = {
    testXsrfToken: '༼༎෴ ༎༽',
    testClientId: 'testClientId',
    mockControl: new goog.testing.MockControl()
  };

  beforeEach(function() {
    registry.testing.addToDocument('<div id="test"/>');
    registry.testing.addToDocument('<div class="kd-butterbar"/>');
    registry.registrar.ConsoleTestUtil.renderConsoleMain($('test'), {
      xsrfToken: test.testXsrfToken,
      clientId: test.testClientId,
    });
    stubs.setPath('goog.net.XhrIo', goog.testing.net.XhrIo);
    registry.registrar.ConsoleTestUtil.setup(test);
  });

  afterEach(function() {
    goog.dispose(test.console);
    goog.testing.net.XhrIo.cleanup();
    stubs.reset();
    test.mockControl.$tearDown();
  });

  function testView() {
    registry.registrar.ConsoleTestUtil.visit(test, {
      path: 'security-settings',
      xsrfToken: test.testXsrfToken,
      clientId: test.testClientId
    });
    registry.testing.assertReqMockRsp(
        test.testXsrfToken,
        '/registrar-settings',
        {op: 'read', id: 'testClientId', args: {}},
        {
          status: 'SUCCESS',
          message: 'OK',
          results: [expectedRegistrar]
        });
    expect(registry.util.parseForm('item').phonePasscode).toEqual(expectedRegistrar.phonePasscode);
  }

  it("testView", function() {
    testView();
  });

  it("testEdit", function() {
    testView();

    registry.testing.click($('reg-app-btn-edit'));

    const form = document.forms.namedItem('item');
    form.elements['newIp'].value = '1.1.1.1';
    registry.testing.click($('btn-add-ip'));
    form.elements['newIp'].value = '2.2.2.2';
    registry.testing.click($('btn-add-ip'));

    const exampleCert = $('exampleCert').value;
    const exampleCertHash = '6NKKNBnd2fKFooBINmn3V7L3JOTHh02+2lAqYHdlTgk';
    form.elements['clientCertificate'].value = exampleCert;
    form.elements['failoverClientCertificate'].value = 'bourgeois blues';
    registry.testing.click($('reg-app-btn-save'));

    registry.testing.assertReqMockRsp(
        test.testXsrfToken,
        '/registrar-settings',
        {op: 'update', id: 'testClientId', args: {
          clientCertificate: exampleCert,
          clientCertificateHash: null,
          failoverClientCertificate: 'bourgeois blues',
          ipAddressAllowList: ['1.1.1.1', '2.2.2.2'],
          phonePasscode: expectedRegistrar.phonePasscode,
          readonly: false }},
        {status: 'SUCCESS',
          message: 'OK',
          results: [{}]});
    // XXX: The response above is ignored as the page re-issues a fetch. Should
    //      either provide the real response here and use anyTimes(), or have
    //      resource_component use this directly.

    expectedRegistrar.clientCertificate = exampleCert;
    expectedRegistrar.clientCertificateHash = exampleCertHash;
    expectedRegistrar.failoverClientCertificate = 'bourgeois blues';
    expectedRegistrar.ipAddressAllowList = ['1.1.1.1/32', '2.2.2.2/32'];
    registry.testing.assertReqMockRsp(
        test.testXsrfToken,
        '/registrar-settings',
        {op: 'read', id: 'testClientId', args: {}},
        {status: 'SUCCESS',
          message: 'OK',
          results: [expectedRegistrar]});

    delete expectedRegistrar['clientCertificateHash'];
    registry.testing.assertObjectEqualsPretty(
        expectedRegistrar, registry.util.parseForm('item'));
  });
});
