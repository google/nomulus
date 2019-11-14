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

goog.provide('registry.registrar.Console');

goog.require('goog.Uri');
goog.require('goog.dispose');
goog.require('goog.dom');
goog.require('goog.dom.classlist');
goog.require('goog.net.XhrIo');
goog.require('registry.Console');
goog.require('registry.Resource');
goog.require('registry.registrar.AdminSettings');
goog.require('registry.registrar.ContactSettings');
goog.require('registry.registrar.ContactUs');
goog.require('registry.registrar.Dashboard');
goog.require('registry.registrar.RegistryLock');
goog.require('registry.registrar.Resources');
goog.require('registry.registrar.SecuritySettings');
goog.require('registry.registrar.WhoisSettings');
goog.require('registry.util');

goog.forwardDeclare('registry.Component');



/**
 * The Registrar Console.
 * @param {!Object} params Parameters to be passed into templates.  These are
 *   a combination of configurable parameters (e.g. phone number) and
 *   user/session/registrar specific parameters.  See
 *   registrar/Console.soy#.main for expected contents.
 * @constructor
 * @extends {registry.Console}
 * @final
 */
registry.registrar.Console = function(params) {
  registry.registrar.Console.base(this, 'constructor');

  /**
   * @type {!Object}
   */
  this.params = params;

  /**
   * Component that's currently embedded in the page.
   * @type {?registry.Component}
   * @private
   */
  this.component_ = null;

  /**
   * Last active nav element.
   * @type {?Element}
   */
  this.lastActiveNavElt;

  /**
   * A map from the URL fragment to the component to show.
   *
   * @type {!Object.<string, function(new:registry.Component,
   *                                  !registry.registrar.Console,
   *                                  !registry.Resource)>}
   */
  this.pageMap = {};
  // Homepage. Displayed when there's no fragment, or when the fragment doesn't
  // correspond to any view
  this.pageMap[''] = registry.registrar.Dashboard;
  // Updating the Registrar settings
  this.pageMap['security-settings'] = registry.registrar.SecuritySettings;
  this.pageMap['contact-settings'] = registry.registrar.ContactSettings;
  this.pageMap['whois-settings'] = registry.registrar.WhoisSettings;
  this.pageMap['contact-us'] = registry.registrar.ContactUs;
  this.pageMap['resources'] = registry.registrar.Resources;
  // Registry lock is enabled or not per registrar, but since we don't have the registrar object
  // accessible here yet, show the link no matter what (the page will show an error message if
  // registry lock isn't enabled for this registrar)
  this.pageMap['registry-lock'] = registry.registrar.RegistryLock;
  // For admin use. The relevant tab is only shown in Console.soy for admins,
  // but we also need to remove it here, otherwise it'd still be accessible if
  // the user manually puts '#admin-settings' in the URL.
  //
  // Both the Console.soy and here, the "hiding the admin console for non
  // admins" is purely for "aesthetic / design" reasons and have NO security
  // implications.
  //
  // The security implications are only in the backend where we make sure all
  // changes are made by users with the correct access (in other words - we
  // don't trust the client-side to secure our application anyway)
  if (this.params.isAdmin) {
    this.pageMap['admin-settings'] = registry.registrar.AdminSettings;
  }
};
goog.inherits(registry.registrar.Console, registry.Console);


/**
 * Changes the content area depending on hash path.
 *
 * <p>Hash path is expected to be of the form:
 *
 * <pre>
 *   #type/id
 * </pre>
 *
 * <p>The `id` part may be appended by `()` to specify that the target
 * should be a resource create page.
 *
 * @override
 */
registry.registrar.Console.prototype.handleHashChange = function() {
  var hashToken = this.history.getToken();

  var parts = hashToken.split('/');
  var type = '';
  var id = '';
  if (parts.length >= 1) {
    type = parts[0];
  }
  if (parts.length == 2) {
    id = parts[1];
  }

  goog.net.XhrIo.cleanup();

  var componentCtor = this.pageMap[type];
  if (componentCtor == undefined) {
    componentCtor = this.pageMap[''];
  }
  var oldComponent = this.component_;
  const resource = new registry.Resource(
      new goog.Uri('/registrar-settings'), this.params.clientId,
      this.params.xsrfToken);
  this.component_ = new componentCtor(this, resource);
  this.registerDisposable(this.component_);
  this.component_.basePath = type;
  this.component_.bindToDom(id);

  this.changeNavStyle();

  goog.dispose(oldComponent);
};


/** Change nav style. */
registry.registrar.Console.prototype.changeNavStyle = function() {
  var hashToken = this.history.getToken();
  // Path except id
  var slashNdx = hashToken.lastIndexOf('/');
  slashNdx = slashNdx == -1 ? hashToken.length : slashNdx;
  var regNavlist = goog.dom.getRequiredElement('reg-navlist');
  var path = hashToken.substring(0, slashNdx);
  var active = regNavlist.querySelector('a[href="#' + path + '"]');
  if (goog.isNull(active)) {
    registry.util.log('Unknown path or path form in changeNavStyle.');
    return;
  }
  if (this.lastActiveNavElt) {
    goog.dom.classlist.remove(
        this.lastActiveNavElt, goog.getCssName('domain-active-nav'));
  }
  goog.dom.classlist.add(active, goog.getCssName('domain-active-nav'));
  this.lastActiveNavElt = active;
};
