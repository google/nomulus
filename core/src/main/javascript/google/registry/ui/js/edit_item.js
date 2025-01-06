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

goog.provide('registry.EditItem');

goog.require('goog.dom');
goog.require('goog.dom.classlist');
goog.require('goog.events');
goog.require('goog.events.EventType');
goog.require('goog.soy');
goog.require('registry.Component');
goog.require('registry.soy.console');
goog.require('registry.util');

goog.forwardDeclare('registry.Console');



/**
 * An editable item, with Edit and Save/Cancel buttons in the appbar.
 * @param {!registry.Console} cons
 * @param {function()} itemTmpl
 * @param {boolean} isEditable
 * @constructor
 * @extends {registry.Component}
 */
registry.EditItem = function(cons, itemTmpl, isEditable) {
  registry.EditItem.base(this, 'constructor', cons);

  /**
   * @type {!Function}
   */
  this.itemTmpl = itemTmpl;

  /**
   * Optional current target resource id.
   * @type {?string}
   */
  this.id = null;

  /**
   * Should the "edit" button be enabled?
   * @type {boolean}
   */
  this.isEditable = isEditable;

  /**
   * Transitional id for next resource during create.
   * @type {?string}
   */
  this.nextId = null;
};
goog.inherits(registry.EditItem, registry.Component);


/** @override */
registry.EditItem.prototype.bindToDom = function(id) {
  registry.EditItem.base(this, 'bindToDom', id);
  this.id = id;
  this.nextId = null;
  this.setupAppbar();
};


/** Setup appbar save/edit buttons. */
registry.EditItem.prototype.setupAppbar = function() {
  goog.soy.renderElement(goog.dom.getRequiredElement('reg-app-buttons'),
                         registry.soy.console.appbarButtons);
  goog.events.listen(goog.dom.getRequiredElement('reg-app-btn-add'),
      goog.events.EventType.CLICK,
      goog.bind(this.add, this));
  goog.events.listen(goog.dom.getRequiredElement('reg-app-btn-edit'),
      goog.events.EventType.CLICK,
      goog.bind(this.edit, this));
  goog.events.listen(goog.dom.getRequiredElement('reg-app-btn-save'),
      goog.events.EventType.CLICK,
      goog.bind(this.save, this));
  goog.events.listen(goog.dom.getRequiredElement('reg-app-btn-cancel'),
      goog.events.EventType.CLICK,
      goog.bind(this.cancel, this));
  goog.events.listen(goog.dom.getRequiredElement('reg-app-btn-back'),
      goog.events.EventType.CLICK,
      goog.bind(this.back, this));
  // Show the add/edit buttons only if isEditable.
  // "edit" is shown if we have an item's ID
  // "add" is shown if we don't have an item's ID
  registry.util.setVisible('reg-app-btns-edit', this.isEditable && !!this.id);
  registry.util.setVisible('reg-app-btn-add', this.isEditable && !this.id);
};


/**
 * Retrieve item from server.  Overrides should callback to
 * `#handleFetchItem(string, !Object)`.
 * @param {string} id item id.
 */
registry.EditItem.prototype.fetchItem = goog.abstractMethod;


/**
 * Handle result decoding and display.
 * @param {string} id The requested ID/name, for error message.
 * @param {!Object} rsp The requested object.
 */
registry.EditItem.prototype.handleFetchItem = goog.abstractMethod;


/** Subclasses should override to continue processing after fetch. */
registry.EditItem.prototype.processItem = function() {};


/**
 * Show the item.
 * @param {!Object} objArgs
 */
registry.EditItem.prototype.renderItem = function(objArgs) {
  goog.soy.renderElement(goog.dom.getRequiredElement('reg-content'),
                         this.itemTmpl,
                         objArgs);
  this.runAfterRender(objArgs);
};


/**
 * @return {boolean} if the component is currently being edited.
 */
registry.EditItem.prototype.isEditing = function() {
  return goog.dom.classlist.contains(
      goog.dom.getElement('reg-app'), goog.getCssName('editing'));
};


/**
 * Toggles the editing state of a component.  This will first hide the
 * elements of the `shown` class, then adds the `editing`
 * style to the component, then shows the elements that had the `hidden`
 * class.
 */
registry.EditItem.prototype.toggleEdit = function() {
  // Toggle appbar buttons.
  var addBtn = goog.dom.getRequiredElement('reg-app-btn-add');
  var editBtns = goog.dom.getRequiredElement('reg-app-btns-edit');
  var saveBtns = goog.dom.getRequiredElement('reg-app-btns-save');
  var editing = goog.dom.classlist.contains(saveBtns,
      registry.util.cssShown);
  if (editing) {
    registry.util.setVisible(saveBtns, false);
    if (this.id) {
      registry.util.setVisible(editBtns, true);
    } else {
      registry.util.setVisible(addBtn, true);
    }
  } else {
    if (this.id) {
      registry.util.setVisible(editBtns, false);
    } else {
      registry.util.setVisible(addBtn, false);
    }
    registry.util.setVisible(saveBtns, true);
  }
  // Then page contents.
  var parentElt = goog.dom.getElement('reg-content');
  var shownCssName = goog.getCssName('shown');
  var hiddenCssName = goog.getCssName('hidden');
  var shown = goog.dom.getElementsByClass(shownCssName, parentElt);
  var hidden = goog.dom.getElementsByClass(hiddenCssName, parentElt);

  for (var i = 0; i < shown.length; i++) {
    goog.dom.classlist.addRemove(shown[i], shownCssName, hiddenCssName);
  }

  // Then add editing styles.
  var editingCssName = goog.getCssName('editing');
  var startingEdit = !goog.dom.classlist.contains(parentElt, editingCssName);
  if (startingEdit) {
    goog.dom.classlist.remove(parentElt, editingCssName);
  } else {
    goog.dom.classlist.add(parentElt, editingCssName);
  }

  // The show hiddens.
  for (var i = 0; i < hidden.length; i++) {
    goog.dom.classlist.addRemove(hidden[i], hiddenCssName, shownCssName);
  }
};


/**
 * Subclasses should override to enhance the default model.
 * @return {!Object.<string, ?>}
 */
registry.EditItem.prototype.newModel = function() {
  return {item: {}};
};


// N.B. setting these as abstract precludes their correct binding in
// setupAppbar.
/** Show add item panel. */
registry.EditItem.prototype.add = function() {};


/** Go back from item to collection view. */
registry.EditItem.prototype.back = function() {};


/** Sets up initial edit model state and then called edit(objArgs). */
registry.EditItem.prototype.edit = function() {
  var objArgs = this.model;
  if (objArgs == null) {
    objArgs = this.newModel();
  }
  // XXX: This is vestigial. In the new msg format, this pollutes the
  //      server-model state. Should be carried elsewhere.
  objArgs.readonly = false;
  this.renderItem(objArgs);
  this.setupEditor(objArgs);
  this.toggleEdit();
};


/**
 * Save the current item.  Calls either create if creating a new
 * object or update otherwise.
 */
registry.EditItem.prototype.save = function() {
  if (this.model == null) {
    this.sendCreate();
  } else {
    this.sendUpdate();
  }
};


/**
 * Resets to non-editing, readonly state, or visits home screen if the
 * page was in on a create panel.
 */
registry.EditItem.prototype.cancel = function() {
  this.toggleEdit();
  // XXX: The presence of a model is sufficient for non-collection pages, but an
  //      empty id also means go to the collection in collection pages. Should
  //      be simplified.
  if (this.model && this.id != '') {
    this.model.readonly = true;
    this.renderItem(this.model);
  } else {
    this.bindToDom('');
  }
};


/**
 * Called after this.renderItem(), to allow for further setup of
 * editing.
 *
 * TODO(b/122661518): merge this with runAfterRender so we don't have two
 * similar methods
 * @param {!Object} objArgs
 */
registry.EditItem.prototype.setupEditor = function(objArgs) {};

/**
 * Called at the end of this.renderItem() to allow for registration
 * of listeners and other tasks that depend on the existence of the items in the
 * DOM
 * @param {!Object} objArgs
 */
registry.EditItem.prototype.runAfterRender = function(objArgs) {};


// XXX: These should really take @param {object} which is the form. Alas the
//      only override which doesn't work this way, ResourceComponent.sendCreate
//      breaks this opportunity. Hmmm...
/** Subclasses should extract form values and send them to the server. */
registry.EditItem.prototype.sendCreate = goog.abstractMethod;


/** Subclasses should extract form values and send them to the server. */
registry.EditItem.prototype.sendUpdate = goog.abstractMethod;


/** Subclasses should extract form values and send them to the server. */
registry.EditItem.prototype.sendDelete = goog.abstractMethod;


/**
 * Subclasses should override to populate update queryParams with form
 * fields as needed. `queryParams.nextId` MUST be set to the
 * new object's ID.
 * @param {!Object} queryParams
 */
registry.EditItem.prototype.prepareUpdate = goog.abstractMethod;


/**
 * Subclasses should provide a function to parse JSON response from server and
 * return a result object as described below.
 * @param {!Object} rsp Decoded JSON response from the server.
 * @return {!Object} a result object describing next steps.  On
 *     success, if next is defined, visit(ret.next) is called, otherwise
 *     if err is set, the butterbar message is set to it.
 */
registry.EditItem.prototype.handleUpdateResponse = goog.abstractMethod;
