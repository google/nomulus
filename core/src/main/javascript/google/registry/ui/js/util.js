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

goog.provide('registry.util');

goog.require('goog.dom');
goog.require('goog.dom.classlist');
goog.require('goog.soy');


/**
 * Logging function that delegates to `console.log()`.
 * @param {...*} var_args
 */
registry.util.log = function(var_args) {
  if (goog.DEBUG) {
    if (goog.global.console !== undefined && goog.global.console['log'] !== undefined) {
      goog.global.console.log.apply(goog.global.console, arguments);
    }
  }
};


/**
 * CSS class for hiding an element whose visibility can be toggled.
 * @type {string}
 * @const
 */
registry.util.cssHidden = goog.getCssName('hidden');


/**
 * CSS class for showing an element whose visibility can be toggled.
 * @type {string}
 * @const
 */
registry.util.cssShown = goog.getCssName('shown');


/**
 * Changes element visibility by toggling CSS `shown` to `hidden`.
 * @param {!Element|string} element Element or id attribute of element.
 * @param {boolean} visible Shows `element` if true, or else hides it.
 */
registry.util.setVisible = function(element, visible) {
  goog.dom.classlist.addRemove(
      goog.dom.getElement(element),
      visible ? registry.util.cssHidden : registry.util.cssShown,
      visible ? registry.util.cssShown : registry.util.cssHidden);
};


/**
 * Show a buttebar with the given message.  A dismiss link will be added.
 * @param {string} message the message to show the user.
 * @param {boolean=} opt_isFatal indicates butterbar should be blood red. This
 *     should only be used when an RPC returns a non-200 status.
 */
registry.util.butter = function(message, opt_isFatal) {
  goog.dom.setTextContent(
      goog.dom.getElementByClass(goog.getCssName('kd-butterbar-text')),
      message);
  var butterbar =
      goog.dom.getRequiredElementByClass(goog.getCssName('kd-butterbar'));
  registry.util.setVisible(butterbar, true);
  if (opt_isFatal) {
    goog.dom.classlist.add(butterbar, goog.getCssName('fatal'));
  } else {
    goog.dom.classlist.remove(butterbar, goog.getCssName('fatal'));
  }
};


/**
 * Hides the butterbar.
 */
registry.util.unbutter = function() {
  registry.util.setVisible(
      goog.dom.getRequiredElementByClass(goog.getCssName('kd-butterbar')),
      false);
};


/**
 * Renders the tmpl at and then moves it before the given elt.
 * @param {Element|string} id dom id of the refElt to render before.
 * @param {function()} tmpl template to render.
 * @param {!Object} tmplParams params to pass to the template.
 * @return {!Element} the rendered row.
 */
registry.util.renderBeforeRow = function(id, tmpl, tmplParams) {
  var refElt = goog.dom.getElement(id);
  goog.soy.renderElement(refElt, tmpl, tmplParams);
  var prevSib = goog.dom.getPreviousElementSibling(refElt);
  goog.dom.removeNode(refElt);
  refElt.removeAttribute('id');
  goog.dom.insertSiblingAfter(refElt, prevSib);
  var newAnchorRefElt = goog.dom.createDom(refElt.tagName, {'id': id});
  goog.dom.insertSiblingAfter(newAnchorRefElt, refElt);
  return refElt;
};


/**
 * Turns an HTML form's named elements into a JSON data structure with
 * Pablo's black magick.
 * @param {string} formName the name of the form to be parsed.
 * @throws {Error} if `formName` couldn't be found.
 * @return {!Object} the parsed form values as an object.
 */
registry.util.parseForm = function(formName) {
  var form = /** @type {HTMLFormElement}
              */ (document.querySelector('form[name=' + formName + ']'));
  if (form == null) {
    throw new Error('No such form named ' + formName);
  }
  var obj = {};
  // Find names first, since e.g. radio buttons have two elts with the
  // same name.
  var eltNames = {};
  for (var i = 0; i < form.elements.length; i++) {
    var elt = form.elements[i];
    if (elt.name == '') {
      continue;
    }
    eltNames[elt.name] = null;
  }
  for (var eltName in eltNames) {
    var elt = form.elements[eltName];
    var val;
    if (elt.type == 'checkbox') {
      val = elt.checked;
    } else {
      val = elt.value;
    }
    registry.util.expandObject_(obj, eltName, val);
  }
  return obj;
};


/**
 * Give the object a value at the given path.  Paths are split on dot
 * and array elements are recognized.
 *
 * <ul>
 * <li>a = 1
 * <li>foo:a = 1.5
 * <li>b.c = 2
 * <li>b.d = 3
 * <li>c[0] = 4
 * <li>c[1] = 5
 * <li>d[0].a = 6
 * <li>d[1].foo:b = 7
 * <li>d[1].@b = 8
 * </ul>
 *
 * Yields the following object:
 * <pre>
 * {
 *   a: '1',
 *   'foo:a': '1.5',
 *   b: {
 *     c: '2',
 *     d: '3'
 *   },
 *   c: ['4','5'],
 *   d: [{a:'6'},{'foo:b':'7'},{'@b':'8'}]
 * }
 * </pre>
 *
 * @param {!Object} obj
 * @param {string} pathSpec
 * @param {string|Array.<string>|null} val
 * @private
 */
registry.util.expandObject_ = function(obj, pathSpec, val) {
  var path = pathSpec.split('.');
  for (var p = 0; p < path.length; p++) {
    var fieldName = path[p];
    var arrElt = fieldName.match(/^([\w:]+)\[(\d+)\]$/);
    if (arrElt) {
      var arrName = arrElt[1];
      var arrNdx = arrElt[2];
      if (!obj[arrName]) {
        obj[arrName] = [];
      }
      obj = obj[arrName];
      fieldName = arrNdx;
      if (!obj[arrNdx]) {
        obj[arrNdx] = {};
      }
    } else {
      if (!obj[fieldName]) {
        obj[fieldName] = {};
      }
    }
    if (p == path.length - 1) {
      obj[fieldName] = val;
    } else {
      obj = obj[fieldName];
    }
  }
};
