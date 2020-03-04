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

goog.require('goog.array');
goog.require('goog.dispose');
goog.require('goog.dom');
goog.require('goog.testing.MockControl');
goog.require('goog.testing.PropertyReplacer');
goog.require('goog.testing.net.XhrIo');
goog.require('registry.registrar.ConsoleTestUtil');
goog.require('registry.testing');
goog.require('registry.util');

describe("contact settings test", function() {
  const $ = goog.dom.getRequiredElement;
  const stubs = new goog.testing.PropertyReplacer();
  let testContact = null;

  const test = {
    testXsrfToken: '༼༎෴ ༎༽',
    testClientId: 'testClientId',
    mockControl: new goog.testing.MockControl()
  };

  beforeEach(function() {
    registry.testing.addToDocument('<div id="test"/>');
    registry.testing.addToDocument('<div class="kd-butterbar"/>');
    testContact = createTestContact();
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

  it("testCollectionView", function() {
    testContactWithoutType = createTestContact('notype@example.com');
    testContactWithoutType.types = '';
    registry.registrar.ConsoleTestUtil.visit(test, {
      path: 'contact-settings',
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
          results: [{
            contacts: [testContact, testContactWithoutType]
          }]
        }
    );
    expect($('admin-contacts').childNodes.length).toEqual(1);
    expect($('other-contacts').childNodes.length).toEqual(1);
    // XXX: Needs more field testing.
  });


  it("testItemView", function() {
    testItemView();
    expect($('contacts[0].name').value).toEqual(testContact.name);
    expect($('contacts[0].emailAddress').value).toEqual(testContact.emailAddress);
    expect($('contacts[0].phoneNumber').value).toEqual(testContact.phoneNumber);
    expect($('contacts[0].faxNumber').value).toEqual(testContact.faxNumber);
    // XXX: Types are no longer broken out as individual settings, so relying on
    //      screenshot test.
  });

  // XXX: Should be hoisted.
  it("testItemEditButtons", function() {
    testItemView();
    registry.testing.assertVisible($('reg-app-btns-edit'));
    registry.testing.assertHidden($('reg-app-btns-save'));
    registry.testing.click($('reg-app-btn-edit'));
    registry.testing.assertHidden($('reg-app-btns-edit'));
    registry.testing.assertVisible($('reg-app-btns-save'));
    registry.testing.click($('reg-app-btn-cancel'));
    registry.testing.assertVisible($('reg-app-btns-edit'));
    registry.testing.assertHidden($('reg-app-btns-save'));
  });

  it("testItemEdit", function() {
    testItemView();
    registry.testing.click($('reg-app-btn-edit'));
    $('contacts[0].name').setAttribute('value', 'bob');
    registry.testing.click($('reg-app-btn-save'));
    testContact.name = 'bob';
    registry.testing.assertReqMockRsp(
        test.testXsrfToken,
        '/registrar-settings',
        {
          op: 'update',
          id: 'testClientId',
          args: {
            contacts: [testContact],
            readonly: false
          }
        },
        {
          status: 'SUCCESS',
          message: 'OK',
          results: [{
            contacts: [testContact]
          }]
        }
    );
    registry.testing.assertObjectEqualsPretty(
        testContact,
        simulateJsonForContact(registry.util.parseForm('item').contacts[0]));
  });

  it("testChangeContactTypes", function() {
    testItemView();
    registry.testing.click($('reg-app-btn-edit'));
    $('contacts[0].type.admin').removeAttribute('checked');
    $('contacts[0].type.legal').setAttribute('checked', 'checked');
    $('contacts[0].type.marketing').setAttribute('checked', 'checked');
    registry.testing.click($('reg-app-btn-save'));
    testContact.types = 'LEGAL,MARKETING';
    registry.testing.assertReqMockRsp(
        test.testXsrfToken,
        '/registrar-settings',
        {
          op: 'update',
          id: 'testClientId',
          args: {
            contacts: [testContact],
            readonly: false
          }
        },
        {
          status: 'SUCCESS',
          message: 'OK',
          results: [{
            contacts: [testContact]
          }]
        }
    );
    registry.testing.assertObjectEqualsPretty(
        testContact,
        simulateJsonForContact(registry.util.parseForm('item').contacts[0]));
  });

  it("testOneOfManyUpdate", function() {
    registry.registrar.ConsoleTestUtil.visit(test, {
      path: 'contact-settings/test@example.com',
      xsrfToken: test.testXsrfToken,
      clientId: test.testClientId
    });
    const testContacts = [
      createTestContact('new1@asdf.com'),
      testContact,
      createTestContact('new2@asdf.com')
    ];
    registry.testing.assertReqMockRsp(
        test.testXsrfToken,
        '/registrar-settings',
        {op: 'read', id: 'testClientId', args: {}},
        {
          status: 'SUCCESS',
          message: 'OK',
          results: [{
            contacts: testContacts
          }]
        }
    );
    // Edit testContact.
    registry.testing.click($('reg-app-btn-edit'));
    $('contacts[1].type.admin').removeAttribute('checked');
    $('contacts[1].type.legal').setAttribute('checked', 'checked');
    $('contacts[1].type.marketing').setAttribute('checked', 'checked');
    registry.testing.click($('reg-app-btn-save'));

    // Should save them all back, with only testContact changed.
    testContacts[1].types = 'LEGAL,MARKETING';
    registry.testing.assertReqMockRsp(
        test.testXsrfToken,
        '/registrar-settings',
        {
          op: 'update',
          id: 'testClientId',
          args: {
            contacts: testContacts,
            readonly: false,
          },
        },
        {
          status: 'SUCCESS',
          message: 'OK',
          results: [{
            contacts: testContacts
          }]
        }
    );
  });

  it("testDomainWhoisAbuseContactOverride", function() {
    registry.registrar.ConsoleTestUtil.visit(test, {
      path: 'contact-settings/test@example.com',
      xsrfToken: test.testXsrfToken,
      clientId: test.testClientId
    });
    const oldDomainWhoisAbuseContact = createTestContact('old@asdf.com');
    oldDomainWhoisAbuseContact.visibleInDomainWhoisAsAbuse = true;
    const testContacts = [oldDomainWhoisAbuseContact, testContact];
    registry.testing.assertReqMockRsp(
        test.testXsrfToken,
        '/registrar-settings',
        {op: 'read', id: 'testClientId', args: {}},
        {status: 'SUCCESS', message: 'OK', results: [{contacts: testContacts}]});
    // Edit testContact.
    registry.testing.click($('reg-app-btn-edit'));
    $('contacts[1].visibleInDomainWhoisAsAbuse.true')
        .setAttribute('checked', 'checked');
    $('contacts[1].visibleInDomainWhoisAsAbuse.false').removeAttribute('checked');
    registry.testing.click($('reg-app-btn-save'));

    // Should save them all back, and flip the old abuse contact's visibility
    // boolean.
    testContact.visibleInDomainWhoisAsAbuse = true;
    oldDomainWhoisAbuseContact.visibleInDomainWhoisAsAbuse = false;
    registry.testing.assertReqMockRsp(
        test.testXsrfToken,
        '/registrar-settings',
        {
          op: 'update',
          id: 'testClientId',
          args: {contacts: testContacts, readonly: false},
        },
        {status: 'SUCCESS', message: 'OK', results: [{contacts: testContacts}]});
  });

  it("testDelete", function() {
    registry.registrar.ConsoleTestUtil.visit(test, {
      path: 'contact-settings/test@example.com',
      xsrfToken: test.testXsrfToken,
      clientId: test.testClientId
    });
    const testContacts = [
      createTestContact('new1@asdf.com'),
      testContact,
      createTestContact('new2@asdf.com')
    ];
    registry.testing.assertReqMockRsp(
        test.testXsrfToken,
        '/registrar-settings',
        {op: 'read', id: 'testClientId', args: {}},
        {
          status: 'SUCCESS',
          message: 'OK',
          results: [{
            contacts: testContacts
          }]
        }
    );
    // Delete testContact.
    registry.testing.click($('reg-app-btn-edit'));
    registry.testing.click($('reg-app-btn-delete'));

    // Should save them all back, with testContact gone.
    goog.array.removeAt(testContacts, 1);
    registry.testing.assertReqMockRsp(
        test.testXsrfToken,
        '/registrar-settings',
        {
          op: 'update',
          id: 'testClientId',
          args: {contacts: testContacts, readonly: false},
        },
        {
          status: 'SUCCESS',
          message: 'OK',
          results: [{
            contacts: testContacts
          }]
        }
    );
  });

  function testItemView() {
    registry.registrar.ConsoleTestUtil.visit(test, {
      path: 'contact-settings/test@example.com',
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
          results: [{
            contacts: [testContact]
          }]
        }
    );
  }

  /**
   * @param {string=} opt_email
   * @return {!Object}
   */
  function createTestContact(opt_email) {
    const nameMail = opt_email || 'test@example.com';
    return {
      name: nameMail,
      emailAddress: nameMail,
      phoneNumber: '+1.2345551234',
      faxNumber: '+1.2345551234',
      visibleInWhoisAsAdmin: false,
      visibleInWhoisAsTech: false,
      visibleInDomainWhoisAsAbuse: false,
      types: 'ADMIN',
      allowedToSetRegistryLockPassword: 'false'
    };
  }


  /**
   * Convert parsed formContact to simulated wire form.
   * @param {!Element} contact
   * @return {!Object}
   */
  function simulateJsonForContact(contact) {
    contact.visibleInWhoisAsAdmin = contact.visibleInWhoisAsAdmin == 'true';
    contact.visibleInWhoisAsTech = contact.visibleInWhoisAsTech == 'true';
    contact.visibleInDomainWhoisAsAbuse = contact.visibleInDomainWhoisAsAbuse == 'true';
    contact.types = '';
    for (const tNdx in contact.type) {
      if (contact.type[tNdx]) {
        if (contact.types.length > 0) {
          contact.types += ',';
        }
        contact.types += ('' + tNdx).toUpperCase();
      }
    }
    delete contact['type'];
    return contact;
  }
});
