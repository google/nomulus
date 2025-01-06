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

goog.provide('registry.registrar.Dashboard');

goog.require('goog.Timer');
goog.require('goog.dom');
goog.require('goog.events');
goog.require('goog.events.EventType');
goog.require('goog.soy');
goog.require('goog.style');
goog.require('registry.Component');
goog.require('registry.soy.registrar.console');

goog.forwardDeclare('registry.registrar.Console');



/**
 * Dashboard for Registrar Console.
 * @param {!registry.registrar.Console} console
 * @constructor
 * @extends {registry.Component}
 * @final
 */
registry.registrar.Dashboard = function(console) {
  registry.registrar.Dashboard.base(this, 'constructor', console);

  /** @private {number} */
  this.x_ = 0;

  /** @private {?Element} */
  this.gear_ = null;

  /** @private {?goog.Timer} */
  this.timer_ = null;
};
goog.inherits(registry.registrar.Dashboard, registry.Component);


/** @override */
registry.registrar.Dashboard.prototype.bindToDom = function(id) {
  registry.registrar.Dashboard.base(this, 'bindToDom', '');
  goog.dom.removeChildren(goog.dom.getRequiredElement('reg-app-buttons'));
  goog.soy.renderElement(goog.dom.getElement('reg-content'),
                         registry.soy.registrar.console.dashboard,
                         this.console.params);
  goog.events.listen(goog.dom.getElement('rotate'),
                     goog.events.EventType.CLICK,
                     goog.bind(this.rotate_, this));
  this.gear_ = goog.dom.getRequiredElement('gear');
};


/**
 * Let's do the twist.
 * @private
 */
registry.registrar.Dashboard.prototype.rotate_ = function() {
  this.timer_ = new goog.Timer(10);
  this.registerDisposable(this.timer_);
  goog.events.listen(this.timer_, goog.Timer.TICK,
                     goog.bind(this.rotateCall_, this));
  this.timer_.start();
};


/**
 * No really this time!
 * @private
 */
registry.registrar.Dashboard.prototype.rotateCall_ = function() {
  this.x_++;
  goog.style.setStyle(this.gear_, 'transform', 'rotate(' + this.x_ + 'deg)');
  if (this.x_ == 360) {
    this.timer_.stop();
  }
};
