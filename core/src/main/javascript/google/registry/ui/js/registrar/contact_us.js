// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

goog.provide('registry.registrar.ContactUs');

goog.require('goog.dom');
goog.require('registry.Resource');
goog.require('registry.ResourceComponent');
goog.require('registry.soy.registrar.console');

goog.forwardDeclare('registry.registrar.Console');



/**
 * Contact Us page.
 * @param {!registry.registrar.Console} console
 * @param {!registry.Resource} resource the RESTful resource for the registrar.
 * @constructor
 * @extends {registry.ResourceComponent}
 * @final
 */
registry.registrar.ContactUs = function(console, resource) {
  registry.registrar.ContactUs.base(
      this, 'constructor', console, resource,
      registry.soy.registrar.console.contactUs, console.params.isOwner, null);
};
goog.inherits(registry.registrar.ContactUs, registry.ResourceComponent);


/** @override */
registry.registrar.ContactUs.prototype.bindToDom = function(id) {
  registry.registrar.ContactUs.base(this, 'bindToDom', '');
  goog.dom.removeChildren(goog.dom.getRequiredElement('reg-app-buttons'));
};
