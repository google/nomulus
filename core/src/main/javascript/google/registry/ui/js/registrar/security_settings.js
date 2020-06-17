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

goog.provide('registry.registrar.SecuritySettings');

goog.require('goog.array');
goog.require('goog.dom');
goog.require('goog.dom.classlist');
goog.require('goog.events');
goog.require('goog.events.EventType');
goog.require('goog.soy');
goog.require('registry.Resource');
goog.require('registry.ResourceComponent');
goog.require('registry.soy.registrar.security');

goog.forwardDeclare('registry.registrar.Console');



/**
 * Security Settings page.
 * @param {!registry.registrar.Console} console
 * @param {!registry.Resource} resource the RESTful resource for the registrar.
 * @constructor
 * @extends {registry.ResourceComponent}
 * @final
 */
registry.registrar.SecuritySettings = function(console, resource) {
  registry.registrar.SecuritySettings.base(
      this, 'constructor', console, resource,
      registry.soy.registrar.security.settings, console.params.isOwner, null);
};
goog.inherits(registry.registrar.SecuritySettings, registry.ResourceComponent);


/** @override */
registry.registrar.SecuritySettings.prototype.bindToDom = function(id) {
  registry.registrar.SecuritySettings.base(this, 'bindToDom', 'fake');
  goog.dom.removeNode(goog.dom.getRequiredElement('reg-app-btn-back'));
};


/** @override */
registry.registrar.SecuritySettings.prototype.setupEditor =
    function(objArgs) {
  goog.dom.classlist.add(goog.dom.getRequiredElement('ips'),
                         goog.getCssName('editing'));
  var ips = goog.dom.getElementsByClass(goog.getCssName('ip'),
                                        goog.dom.getRequiredElement('ips'));
  goog.array.forEach(ips, function(ip) {
    var remBtn = goog.dom.getChildren(ip)[0];
    goog.events.listen(remBtn,
                       goog.events.EventType.CLICK,
                       goog.bind(this.onIpRemove_, this, remBtn));
  }, this);
  this.typeCounts['reg-ips'] = objArgs.ipAddressAllowList ?
      objArgs.ipAddressAllowList.length : 0;

  goog.events.listen(goog.dom.getRequiredElement('btn-add-ip'),
                     goog.events.EventType.CLICK,
                     this.onIpAdd_,
                     false,
                     this);
};


/**
 * Click handler for IP add button.
 * @private
 */
registry.registrar.SecuritySettings.prototype.onIpAdd_ = function() {
  var ipInputElt = goog.dom.getRequiredElement('newIp');
  var ipElt = goog.soy.renderAsFragment(registry.soy.registrar.security.ip, {
    name: 'ipAddressAllowList[' + this.typeCounts['reg-ips'] + ']',
    ip: ipInputElt.value
  });
  goog.dom.appendChild(goog.dom.getRequiredElement('ips'), ipElt);
  var remBtn = goog.dom.getFirstElementChild(ipElt);
  goog.dom.classlist.remove(remBtn, goog.getCssName('hidden'));
  goog.events.listen(remBtn, goog.events.EventType.CLICK,
                     goog.bind(this.onIpRemove_, this, remBtn));
  this.typeCounts['reg-ips']++;
  ipInputElt.value = '';
};


/**
 * Click handler for IP remove button.
 * @param {!Element} remBtn The remove button.
 * @private
 */
registry.registrar.SecuritySettings.prototype.onIpRemove_ =
    function(remBtn) {
  goog.dom.removeNode(goog.dom.getParentElement(remBtn));
};
