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

goog.provide('registry.forms');

goog.require('goog.array');
goog.require('goog.dom');
goog.require('goog.dom.TagName');
goog.require('goog.dom.classlist');
goog.require('goog.dom.forms');
goog.require('goog.events');
goog.require('goog.events.EventType');
goog.require('goog.events.KeyCodes');
goog.require('registry.util');

goog.forwardDeclare('goog.events.KeyEvent');


/**
 * Sets the focus on a form field (if it exists).
 * @param {Element|string} field Form field (or ID) to focus.
 */
registry.forms.focus = function(field) {
  field = goog.dom.getElement(field);
  if (field !== null && goog.dom.isFocusable(field)) {
    goog.dom.forms.focusAndSelect(field);
  }
};


/**
 * Displays a form field error, or butters if no field is specified.
 * @param {string} message Human-readable explanation of why this field is evil.
 * @param {string=} opt_field Erroneous field name.
 */
registry.forms.displayError = function(message, opt_field) {
  if (opt_field === undefined) {
    registry.util.butter(message);
    return;
  }
  var input = goog.dom.getElement(opt_field) ||
      goog.dom.getElement(opt_field + '[0]');
  // XXX: Transitioning to use of form.eltId instead of DOM id. If DOM id
  //      lookup fails, then search forms for the named field.
  if (opt_field != null && input === null) {
    for (var fNdx in document.forms) {
      var form = document.forms[fNdx];
      if (form[opt_field]) {
        input = form[opt_field];
        break;
      }
    }
  }
  if (input !== null) {
    goog.dom.classlist.add(input, goog.getCssName('kd-formerror'));
    goog.dom.insertSiblingAfter(
        goog.dom.createDom(goog.dom.TagName.DIV,
                           goog.getCssName('kd-errormessage'),
                           message),
        input);
    registry.forms.focus(input);
  } else {
    registry.util.butter(opt_field + ': ' + message);
  }
};


/** Removes error markup from whois settings form. */
registry.forms.resetErrors = function() {
  registry.util.unbutter();
  goog.array.forEach(
      goog.dom.getElementsByClass(goog.getCssName('kd-formerror')),
      function(field) {
        goog.dom.classlist.remove(field, goog.getCssName('kd-formerror'));
      });
  goog.array.forEach(
      goog.dom.getElementsByClass(goog.getCssName('kd-errormessage')),
      goog.dom.removeNode);
};


/**
 * Adds enter key listeners to all form fields.
 * @param {!Element} container Parent element containing INPUT fields.
 * @param {function()} callback Called when enter is pressed in a field.
 */
registry.forms.listenFieldsOnEnter = function(container, callback) {
  var handler = goog.partial(registry.forms.onFieldKeyUp_, callback);
  goog.array.forEach(
      goog.dom.getElementsByTagNameAndClass(
          goog.dom.TagName.INPUT, undefined, container),
      function(field) {
        goog.events.listen(field, goog.events.EventType.KEYUP, handler);
      });
};


/**
 * Event handler that saves form when enter is pressed in a form field.
 * @param {function()} callback Called when key pressed is ENTER.
 * @param {!goog.events.KeyEvent} e Key event to handle.
 * @return {boolean} Whether the event should be continued or cancelled.
 * @private
 */
registry.forms.onFieldKeyUp_ = function(callback, e) {
  if (e.keyCode == goog.events.KeyCodes.ENTER) {
    callback();
    return false;
  }
  return true;
};


/**
 * Toggles disabled state of a button or form element.
 * @param {!Element} element Form element to disable.
 * @param {boolean} enabled Enables element if true, or else disables it.
 */
registry.forms.setEnabled = function(element, enabled) {
  if (enabled) {
    goog.dom.classlist.remove(element, goog.getCssName('disabled'));
  } else {
    goog.dom.classlist.add(element, goog.getCssName('disabled'));
    element.blur();
  }
};
