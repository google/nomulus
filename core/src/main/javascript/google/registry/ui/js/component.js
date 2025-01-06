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

goog.provide('registry.Component');

goog.forwardDeclare('registry.Console');
goog.require('goog.events.EventHandler');
goog.require('registry.util');



/**
 * Base component for UI.
 *
 * <pre>
 * ui/js/component.js - Base UI class.
 *       ^
 *       |
 *       edit_item.js - Common controls for editable items.
 *       ^
 *        \
 *         |-ui/js/resource_component.js - JSON resources
 *                 ^
 *                  \
 *                   |- ui/js/registrar/settings.js
 * </pre>
 *
 * @param {!registry.Console} cons the console singleton.
 * @constructor
 * @extends {goog.events.EventHandler}
 */
registry.Component = function(cons) {
  registry.Component.base(this, 'constructor');

  /** @type {!registry.Console} */
  this.console = cons;

  /**
   * The hashPath this component is mapped by.  This is set by the
   * console after construction.
   * @type {string}
   */
  this.basePath = '';

  /**
   * Bean counter that's used by `addRemBtnHandlers`,
   * e.g. `typeCounts['host']++` when user adds or removes.
   * @type {!Object.<string, number>}
   * @protected
   */
  this.typeCounts = {};

  /**
   * Stateful UI/server session model.
   * @type {?Object.<string, ?>}
   */
  this.model = null;
};
goog.inherits(registry.Component, goog.events.EventHandler);


/**
 * Subclasses should override this to implement panel display.
 * @param {string} id The target resource id.
 */
registry.Component.prototype.bindToDom = function(id) {
  registry.util.unbutter();
};
