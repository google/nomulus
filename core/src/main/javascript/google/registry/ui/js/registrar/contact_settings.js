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

goog.provide('registry.registrar.ContactSettings');

goog.require('goog.array');
goog.require('goog.dom');
goog.require('goog.dom.TagName');
goog.require('goog.events');
goog.require('goog.events.EventType');
goog.require('goog.json');
goog.require('goog.soy');
goog.require('registry.Resource');
goog.require('registry.ResourceComponent');
goog.require('registry.soy.registrar.contacts');
goog.require('registry.util');

goog.forwardDeclare('registry.registrar.Console');



/**
 * Contact Settings page.  Registrar Contacts are not really a proper
 * REST resource as they're still passed from the server as a member
 * field of Registrar, so this class behaves like an item page,
 * updating only that field of the Registrar object and not
 * implementing the create action from edit_item.
 * @param {!registry.registrar.Console} console
 * @param {!registry.Resource} resource the RESTful resource for the registrar.
 * @constructor
 * @extends {registry.ResourceComponent}
 * @final
 */
registry.registrar.ContactSettings = function(console, resource) {
  registry.registrar.ContactSettings.base(
      this, 'constructor', console, resource,
      registry.soy.registrar.contacts.contact, console.params.isOwner, null);
};
goog.inherits(registry.registrar.ContactSettings, registry.ResourceComponent);


/** @override */
registry.registrar.ContactSettings.prototype.setupAppbar = function() {
  registry.registrar.ContactSettings.base(this, 'setupAppbar');
  // Setup delete only on existing items, not on creates.
  if (this.model != null) {
    var deleteBtn = goog.dom.createDom(
        goog.dom.TagName.BUTTON, {
          type: 'button',
          id: 'reg-app-btn-delete',
          className: goog.getCssName('kd-button')
        },
        'Delete');
    goog.events.listen(deleteBtn, goog.events.EventType.CLICK,
        goog.bind(this.sendDelete, this));
    goog.dom.insertSiblingBefore(deleteBtn,
        goog.dom.getRequiredElement('reg-app-btn-cancel'));
  }
};


/** @override */
registry.registrar.ContactSettings.prototype.renderItem = function(rspObj) {
  var contentElt = goog.dom.getRequiredElement('reg-content');
  /** @type {!registry.json.RegistrarContact} */
  var contacts = rspObj.contacts;
  if (this.id) {
    var targetContactNdx;
    var targetContact;
    for (var c in contacts) {
      var ct = contacts[c];
      if (ct.emailAddress == this.id) {
        targetContactNdx = c;
        targetContact = ct;
        break;
      }
    }
    if (!targetContact) {
      registry.util.butter('No contact with the given email.');
      return;
    }
    var typesList = targetContact.types.toLowerCase().split(',');
    var actualTypesLookup = {};
    for (var t in typesList) {
      actualTypesLookup[typesList[t]] = true;
    }
    goog.soy.renderElement(
        contentElt,
        registry.soy.registrar.contacts.contact,
        {
          item: targetContact,
          namePrefix: 'contacts[' + targetContactNdx + '].',
          actualTypesLookup: actualTypesLookup,
          readonly: (rspObj.readonly || false),
          registryLockAllowedForRegistrar: rspObj.registryLockAllowed
        });
    this.setupAppbar();
    this.setupPasswordElemIfNecessary_(targetContactNdx);
  } else {
    var contactsByType = {};
    for (var c in contacts) {
      var contact = contacts[c];
      var types = contact.types;
      if (!types) {
        // If the contact has no types, synthesize an "OTHER" type so that it
        // still will be displayed in the console.
        types = 'OTHER';
      }
      types = types.split(',');
      for (var t in types) {
        var type = types[t].toLowerCase();
        var contactsList = contactsByType[type];
        if (!contactsList) {
          contactsByType[type] = contactsList = [];
        }
        contactsList.push(contact);
      }
    }
    goog.soy.renderElement(
        contentElt,
        registry.soy.registrar.contacts.set,
        {contactsByType: contactsByType });
  }
};


/** @override */
registry.registrar.ContactSettings.prototype.add = function() {
  var newContactNdx = this.model.contacts.length;
  goog.soy.renderElement(goog.dom.getRequiredElement('reg-content'),
                         registry.soy.registrar.contacts.contact,
                         {
                           item: {},
                           namePrefix: 'contacts[' + newContactNdx + '].',
                           actualTypesLookup: {},
                           readonly: false
                         });
  this.toggleEdit();
};


/** @override */
registry.registrar.ContactSettings.prototype.sendDelete = function() {
  var ndxToDel = null;
  for (var i = 0; i < this.model.contacts.length; i++) {
    var contact = this.model.contacts[i];
    if (contact.emailAddress == this.id) {
      ndxToDel = i;
    }
  }
  if (ndxToDel === null) {
    throw new Error('Email to delete does not match model.');
  }
  var modelCopy = /** @type {!Object}
                   */ (JSON.parse(goog.json.serialize(this.model)));
  goog.array.removeAt(modelCopy.contacts, ndxToDel);
  this.resource.update(modelCopy, goog.bind(this.handleDeleteResponse, this));
};


/** @override */
registry.registrar.ContactSettings.prototype.prepareUpdate =
    function(modelCopy) {
  var form = registry.util.parseForm('item');
  var contact;
  // Handle update/create.
  if (this.id) {
    // Update contact, so overwrite it in the model before sending
    // back to server.
    var once = false;
    for (var c in form.contacts) {
      if (once) {
        throw new Error('More than one contact parsed from form: ' + c);
      }
      contact = form.contacts[c];
      modelCopy.contacts[c] = contact;
      once = true;
    }
  } else {
    // Add contact.
    contact = form.contacts.pop();
    modelCopy.contacts.push(contact);
  }
  contact.visibleInWhoisAsAdmin = contact.visibleInWhoisAsAdmin == 'true';
  contact.visibleInWhoisAsTech = contact.visibleInWhoisAsTech == 'true';
  contact.visibleInDomainWhoisAsAbuse =
      contact.visibleInDomainWhoisAsAbuse == 'true';
  contact.types = '';
  for (var tNdx in contact.type) {
    if (contact.type[tNdx]) {
      if (contact.types.length > 0) {
        contact.types += ',';
      }
      contact.types += ('' + tNdx).toUpperCase();
    }
  }
  delete contact['type'];
  // Override previous domain WHOIS abuse contact.
  if (contact.visibleInDomainWhoisAsAbuse) {
    for (var c in modelCopy.contacts) {
      if (modelCopy.contacts[c].emailAddress != contact.emailAddress) {
        modelCopy.contacts[c].visibleInDomainWhoisAsAbuse = false;
      }
    }
  }
  this.nextId = contact.emailAddress;
};


// XXX: Should be hoisted up.
/**
 * Handler for contact save that navigates to that item on success.
 * Does nothing on failure as UI will be left with error messages for
 * the user to resolve.
 * @param {!Object} rsp Decoded JSON response from the server.
 * @override
 */
registry.registrar.ContactSettings.prototype.handleCreateResponse =
    function(rsp) {
  this.handleUpdateResponse(rsp);
  if (rsp.status == 'SUCCESS') {
    this.console.view('contact-settings/' + this.nextId);
  }
  return rsp;
};


/**
 * Handler for contact delete that navigates back to the collection on success.
 * Does nothing on failure as UI will be left with error messages for
 * the user to resolve.
 * @param {!Object} rsp Decoded JSON response from the server.
 * @override
 */
registry.registrar.ContactSettings.prototype.handleDeleteResponse =
    function(rsp) {
  this.handleUpdateResponse(rsp);
  if (rsp.status == 'SUCCESS') {
    this.id = null;
    this.console.view('contact-settings');
  }
  return rsp;
};

// Show or hide the password based on what the user chooses
registry.registrar.ContactSettings.prototype.setupPasswordElemIfNecessary_ =
    function(contactIndex) {
  var showOrHidePasswordButton = goog.dom.getElement('showOrHideRegistryLockPassword')
  var showOrHidePassword = function() {
    var inputElement = goog.dom.getRequiredElement(
        'contacts[' + contactIndex + '].registryLockPassword')
    var type = inputElement.getAttribute('type')
    if (type === 'password') {
      showOrHidePasswordButton.text = 'Hide password';
      inputElement.setAttribute('type', 'text');
    } else {
      showOrHidePasswordButton.text = 'Show password';
      inputElement.setAttribute('type', 'password');
    }
  };

  if (showOrHidePasswordButton != null) {
    goog.events.listen(showOrHidePasswordButton,
        goog.events.EventType.CLICK, showOrHidePassword, false, this);
  }
};
