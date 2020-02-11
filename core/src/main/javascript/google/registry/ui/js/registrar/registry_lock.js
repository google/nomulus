// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

goog.provide('registry.registrar.RegistryLock');

goog.forwardDeclare('registry.registrar.Console');
goog.require('goog.array');
goog.require('goog.dom');
goog.require('goog.dom.classlist');
goog.require('goog.events');
goog.require('goog.events.KeyCodes');
goog.require('goog.events.EventType');
goog.require('goog.json');
goog.require('goog.net.XhrIo');
goog.require('goog.soy');
goog.require('registry.Resource');
goog.require('registry.ResourceComponent');
goog.require('registry.soy.registrar.registrylock');


/**
 * Registry Lock page, allowing the user to lock / unlock domains.
 * @param {!registry.registrar.Console} console
 * @param {!registry.Resource} resource the RESTful resource for the registrar.
 * @constructor
 * @extends {registry.ResourceComponent}
 * @final
 */
registry.registrar.RegistryLock = function(console, resource) {
  registry.registrar.RegistryLock.base(
      this, 'constructor', console, resource,
      registry.soy.registrar.registrylock.settings, false, null);
};
goog.inherits(registry.registrar.RegistryLock, registry.ResourceComponent);

registry.registrar.RegistryLock.prototype.runAfterRender = function(objArgs) {
  this.clientId = objArgs.clientId;
  this.xsrfToken = objArgs.xsrfToken;

  if (objArgs.registryLockAllowed) {
    // Load the existing locks and display them in the table
    goog.net.XhrIo.send('/registry-lock-get?clientId=' + objArgs.clientId,
      // note: bind this.onUnlockDomain because we lose the "this" reference in the XhrIo callback
      // so we won't have it in fillLocksPage_()
      goog.bind(this.fillLocksPage_, this, this.onUnlockDomain_));
  } else {
    goog.soy.renderElement(goog.dom.getRequiredElement('locks-content'),
      registry.soy.registrar.registrylock.lockNotAllowedOnRegistrar,
      {supportEmail: objArgs.supportEmail});
  }
};

/**
 * Removes the lock/unlock-confirmation modal if it exists
 * @private
 */
const removeModalIfExists_ = function() {
  var modalElement = goog.dom.getElement("lock-confirm-modal");
  if (modalElement != null) {
    modalElement.parentElement.removeChild(modalElement);
  }
}

/**
 * Clears the modal and displays the locks content (lock a new domain, existing locks) that was
 * retrieved from the server.
 * @private
 */
registry.registrar.RegistryLock.prototype.fillLocksPage_ = function(onUnlockClick, e) {
  var response =
          /** @type {!registry.json.locks.ExistingLocksResponse} */
          (e.target.getResponseJson(registry.Resource.PARSER_BREAKER_));
  if (response.status === "SUCCESS") {
    removeModalIfExists_();
    var locksDetails = response.results[0]
    var locksContentDiv = goog.dom.getRequiredElement('locks-content');
    goog.soy.renderElement(
        locksContentDiv, registry.soy.registrar.registrylock.locksContent,
        {locks: locksDetails.locks,
          email: locksDetails.email,
          lockEnabledForContact: locksDetails.lockEnabledForContact});

    if (locksDetails.lockEnabledForContact) {
      // Listen to the lock-domain "submit" button click as well as the enter key
      var submitButton = goog.dom.getRequiredElement('lock-domain-submit');
      goog.events.listen(submitButton,
                         goog.events.EventType.CLICK,
                         this.onLockDomain_,
                         false,
                         this);
      goog.events.listen(goog.dom.getRequiredElement('lock-domain-input'),
                         goog.events.EventType.KEYUP,
                         this.onInputKeyUp_,
                         false,
                         this);
      // For all unlock buttons, listen and perform the unlock action if they're clicked
      var unlockButtons = goog.dom.getElementsByClass('domain-unlock-button', locksContentDiv);
      for (let i = 0; i < unlockButtons.length; i++) {
        goog.events.listen(unlockButtons[i], goog.events.EventType.CLICK, onUnlockClick, false, this);
      }
    }
  } else {
    var errorDiv = goog.dom.getRequiredElement('modal-error-message');
    errorDiv.textContent = response.message;
    errorDiv.removeAttribute("hidden");
  }
}

/**
 * Shows the lock/unlock confirmation modal
 * @private
 */
registry.registrar.RegistryLock.prototype.showModal_ = function(targetElement, domain, isLock) {
  var parentElement = targetElement.parentElement;
  var modalElement = goog.soy.renderAsElement(registry.soy.registrar.registrylock.confirmModal, {domain: domain, isLock: isLock});
  parentElement.prepend(modalElement);
  goog.dom.getRequiredElement("domain-lock-password").focus();
  // delete the modal when the user clicks the cancel button
  goog.events.listen(goog.dom.getRequiredElement('domain-lock-cancel'),
                     goog.events.EventType.CLICK,
                     removeModalIfExists_,
                     false,
                     this);

  goog.events.listen(goog.dom.getRequiredElement('domain-lock-submit'),
                     goog.events.EventType.CLICK,
                     goog.bind(this.lockOrUnlockDomain_, this, domain, isLock),
                     false,
                     this);
}

/**
 * Locks or unlocks the specified domain
 * @private
 */
registry.registrar.RegistryLock.prototype.lockOrUnlockDomain_ = function(domain, isLock, e) {
  goog.net.XhrIo.send('/registry-lock-post',
    // note: bind this.onUnlockDomain because we lose the "this" reference in the XhrIo callback
    // so we won't have it in fillLocksPage_()
    goog.bind(this.fillLocksPage_, this, this.onUnlockDomain_),
    'POST',
    goog.json.serialize({
      'clientId': this.clientId,
      "fullyQualifiedDomainName": domain,
      "isLock": isLock,
      "password": goog.dom.getRequiredElement('domain-lock-password').value
    }), {
      'X-CSRF-Token': this.xsrfToken,
      'Content-Type': 'application/json; charset=UTF-8'
    });
}

/**
 * Click handler for unlocking domains (button click).
 * @private
 */
registry.registrar.RegistryLock.prototype.onUnlockDomain_ = function(e) {
  // the domain is stored in the button ID if it's the right type of button
  var idRegex = /button-unlock-(.*)/
  var targetId = e.target.id;
  var match = targetId.match(idRegex);
  if (match) {
    var domain = match[1];
    this.showModal_(e.target, domain, false);
  }
}

/**
 * Keyup handler for lock-domain input (used for enter -> submit).
 * @private
 */
registry.registrar.RegistryLock.prototype.onInputKeyUp_ = function(e) {
  if (e.keyCode === goog.events.KeyCodes.ENTER) {
    e.preventDefault();
    this.onLockDomain_(e);
  }
}

/**
 * Click handler for lock-domain button.
 * @private
 */
registry.registrar.RegistryLock.prototype.onLockDomain_ = function(e) {
  var domain = goog.dom.getRequiredElement('lock-domain-input').value;
  this.showModal_(e.target, domain, true);
};
