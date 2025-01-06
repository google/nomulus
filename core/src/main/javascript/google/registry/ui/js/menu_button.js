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

goog.provide('registry.MenuButton');

goog.require('goog.array');
goog.require('goog.asserts');
goog.require('goog.dom');
goog.require('goog.dom.classlist');
goog.require('goog.events');
goog.require('goog.events.EventHandler');
goog.require('goog.events.EventType');

goog.forwardDeclare('goog.events.BrowserEvent');



/**
 * Kennedy style menu button.
 * @param {!Element} button Menu button element.
 * @constructor
 * @extends {goog.events.EventHandler}
 * @final
 */
registry.MenuButton = function(button) {
  registry.MenuButton.base(this, 'constructor');

  /**
   * Outer menu button element.
   * @private {!Element}
   * @const
   */
  this.button_ = button;
  this.listen(button, goog.events.EventType.CLICK, this.onButtonClick_);

  /**
   * Label that displays currently selected item.
   * @private {!Element}
   * @const
   */
  this.label_ =
      goog.dom.getRequiredElementByClass(goog.getCssName('label'), button);

  /**
   * List of selectable items.
   * @private {!Element}
   * @const
   */
  this.menu_ =
      goog.dom.getRequiredElementByClass(goog.getCssName('kd-menulist'),
                                         button);

  goog.array.forEach(
      goog.dom.getElementsByClass(goog.getCssName('kd-menulistitem'), button),
      function(item) {
        this.listen(item, goog.events.EventType.CLICK, this.onItemClick_);
      },
      this);
};
goog.inherits(registry.MenuButton, goog.events.EventHandler);


/**
 * Returns selected value in menu.
 * @return {string}
 */
registry.MenuButton.prototype.getValue = function() {
  return goog.dom.getTextContent(
      goog.dom.getRequiredElementByClass(
          goog.getCssName('selected'),
          this.button_));
};


/**
 * Main menu button handler.
 * @param {!goog.events.BrowserEvent} e
 * @private
 */
registry.MenuButton.prototype.onButtonClick_ = function(e) {
  if (goog.dom.classlist.contains(this.button_, goog.getCssName('selected'))) {
    return;
  }
  e.stopPropagation();
  goog.dom.classlist.add(this.button_, goog.getCssName('selected'));
  goog.dom.classlist.add(this.menu_, goog.getCssName('shown'));
  this.listenOnce(goog.dom.getDocument().body, goog.events.EventType.CLICK,
                  this.hideMenu_);
};


/**
 * Menu item selection handler.
 * @param {!goog.events.BrowserEvent} e
 * @private
 */
registry.MenuButton.prototype.onItemClick_ = function(e) {
  e.stopPropagation();
  if (goog.dom.classlist.contains(this.button_, goog.getCssName('disabled'))) {
    return;
  }
  goog.array.forEach(
      goog.dom.getElementsByClass(goog.getCssName('kd-menulistitem'),
                                  this.button_),
      function(item) {
        goog.dom.classlist.remove(item, goog.getCssName('selected'));
      },
      this);
  goog.asserts.assert(e.target instanceof Element);
  goog.dom.classlist.add(e.target, goog.getCssName('selected'));
  var text = goog.dom.getTextContent(e.target);
  goog.dom.setTextContent(this.label_, text);
  goog.events.fireListeners(this.button_, goog.events.EventType.CHANGE,
                            false, {newValue: text});
  this.hideMenu_();
};


/**
 * Hide the menu.
 * @private
 */
registry.MenuButton.prototype.hideMenu_ = function() {
  goog.dom.classlist.remove(this.menu_, goog.getCssName('shown'));
  goog.dom.classlist.remove(this.button_, goog.getCssName('selected'));
};
