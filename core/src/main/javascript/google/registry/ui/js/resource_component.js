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

goog.provide('registry.ResourceComponent');

goog.require('goog.dom');
goog.require('goog.json');
goog.require('goog.object');
goog.require('registry.EditItem');
goog.require('registry.forms');
goog.require('registry.util');

goog.forwardDeclare('registry.Console');
goog.forwardDeclare('registry.Resource');



/**
 * The ResourceComponent class respresents server state for a named
 * resource and binds UI CRUD operations on it, or its constituent
 * collection.
 * @param {!registry.Console} console console singleton.
 * @param {!registry.Resource} resource the RESTful resource.
 * @param {!Function} itemTmpl
 * @param {boolean} isEditable if true, the "edit" button will be enabled
 * @param {Function} renderSetCb may be null if this resource is only
 *     ever an item.
 * @constructor
 * @extends {registry.EditItem}
 */
registry.ResourceComponent = function(
    console,
    resource,
    itemTmpl,
    isEditable,
    renderSetCb) {
  registry.ResourceComponent.base(
      this, 'constructor', console, itemTmpl, isEditable);

  /** @type {!registry.Resource} */
  this.resource = resource;

  /** @type {Function} */
  this.renderSetCb = renderSetCb;
};
goog.inherits(registry.ResourceComponent, registry.EditItem);


/** @override */
registry.ResourceComponent.prototype.renderItem = function(rspObj) {
  // Augment the console parameters with the response object, we'll need both.
  var params = goog.object.clone(this.console.params);
  goog.object.extend(params, rspObj);
  registry.ResourceComponent.base(this, 'renderItem', params);
};


/** @override */
registry.ResourceComponent.prototype.bindToDom = function(id) {
  registry.ResourceComponent.base(this, 'bindToDom', id);
  this.fetchItem(id);
};


/** @override */
registry.ResourceComponent.prototype.back = function() {
  this.console.view(this.basePath);
};


/** @override */
registry.ResourceComponent.prototype.fetchItem = function(id) {
  this.resource.read({}, goog.bind(this.handleFetchItem, this, id));
};


/** @override */
registry.ResourceComponent.prototype.handleFetchItem = function(id, rsp) {
  // XXX: Two different protocols are supported here. The new style used in the
  //      registrar console is first, followed by the item/set style used by
  //      the admin console.
  if ('status' in rsp) {
    // New style.
    if (rsp.status == 'SUCCESS') {
      this.model = rsp.results[0];
      this.model.readonly = true;
      this.processItem();
      this.renderItem(this.model);
    } else {
      // XXX: Happens if the server restarts while the client has an
      //      open-session. This should retry.
      registry.forms.resetErrors();
      registry.forms.displayError(rsp['message'], rsp['field']);
    }
  } else if ('item' in rsp) {
    this.model = rsp.item;
    this.model.readonly = true;
    this.processItem();
    this.renderItem(this.model);
  } else if ('set' in rsp && this.renderSetCb != null) {
    // XXX: This conditional logic should be hoisted to edit_item when
    //      collection support is improved.
    goog.dom.removeChildren(goog.dom.getRequiredElement('reg-app-buttons'));
    this.renderSetCb(goog.dom.getRequiredElement('reg-content'), rsp);
  } else {
    registry.util.log('unknown message type in handleFetchItem');
  }
};


/** @override */
registry.ResourceComponent.prototype.sendUpdate = function() {
  var modelCopy = /** @type {!Object}
                   */ (JSON.parse(goog.json.serialize(this.model)));
  this.prepareUpdate(modelCopy);
  if (this.id) {
    this.resource.update(modelCopy, goog.bind(this.handleUpdateResponse, this));
  } else {
    this.resource.update(modelCopy, goog.bind(this.handleCreateResponse, this));
  }
};


/** @override */
registry.ResourceComponent.prototype.prepareUpdate = function(modelCopy) {
  var form = registry.util.parseForm('item');
  for (var ndx in form) {
    modelCopy[ndx] = form[ndx];
  }
};


/** @override */
registry.ResourceComponent.prototype.handleUpdateResponse = function(rsp) {
  if (rsp.status) {
    if (rsp.status != 'SUCCESS') {
      registry.forms.resetErrors();
      registry.forms.displayError(rsp['message'], rsp['field']);
      return rsp;
    }
    // XXX: Vestigial state from admin console. Shouldn't be possible to be
    //      null.
    if (this.id) {
      this.fetchItem(this.id || '');
      this.toggleEdit();
    }
    return rsp;
  }
  // XXX: Should be removed when admin console uses new response format.
  if (rsp instanceof Object && 'results' in rsp) {
    registry.util.butter(rsp['results']);
    this.bindToDom(this.nextId || '');
    this.nextId = null;
  } else {
    registry.util.butter(rsp.toString());
  }
  return rsp;
};


/**
 * Handle resource create response.
 * @param {!Object} rsp Decoded JSON response from the server.
 * @return {!Object} a result object describing next steps.  On
 *     success, if next is defined, visit(ret.next) is called, otherwise
 *     if err is set, the butterbar message is set to it.
 */
registry.ResourceComponent.prototype.handleCreateResponse =
    goog.abstractMethod;


/**
 * Handle resource delete response.
 * @param {!Object} rsp Decoded JSON response from the server.
 * @return {!Object} a result object describing next steps.  On
 *     success, if next is defined, visit(ret.next) is called, otherwise
 *     if err is set, the butterbar message is set to it.
 */
registry.ResourceComponent.prototype.handleDeleteResponse =
    goog.abstractMethod;
