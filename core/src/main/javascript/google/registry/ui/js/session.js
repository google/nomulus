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

goog.provide('registry.Session');

goog.require('goog.json');
goog.require('goog.net.XhrIo');
goog.require('registry.util');

goog.forwardDeclare('goog.Uri');



/**
 * XHR launcher for JSON requests.
 * @param {!goog.Uri} defaultUri URI to which requests are POSTed.
 * @param {string} xsrfToken Cross-site request forgery protection token.
 * @constructor
 * @template REQUEST, RESPONSE
 */
registry.Session = function(defaultUri, xsrfToken) {

  /**
   * URI to which requests are posted.
   * @protected {!goog.Uri}
   * @const
   */
  this.uri = defaultUri;

  /**
   * XHR request headers.
   * @private {!Object<string, string>}
   * @const
   */
  this.headers_ = {
    'Content-Type': 'application/json; charset=utf-8',
    'X-CSRF-Token': xsrfToken,
    'X-Requested-With': 'XMLHttpRequest'
  };
};


/**
 * Abstract method to send a request to the server.
 * @param {REQUEST} body HTTP request body as a string or JSON object.
 * @param {function(RESPONSE)} onSuccess XHR success callback.
 * @param {function(string)=} opt_onError XHR error callback. The default action
 *     is to show a bloody butterbar.
 * @final
 */
registry.Session.prototype.sendXhrIo =
    function(body, onSuccess, opt_onError) {
  goog.net.XhrIo.send(
      this.uri.toString(),
      goog.bind(this.onXhrComplete_, this, onSuccess,
                opt_onError || goog.bind(this.displayError_, this)),
      'POST',
      goog.isObject(body) ? goog.json.serialize(body) : body,
      this.headers_);
};


/**
 * Handler invoked when an asynchronous request is complete.
 * @param {function(RESPONSE)} onSuccess Success callback.
 * @param {function(string)} onError Success callback.
 * @param {{target: !goog.net.XhrIo}} e XHR event.
 * @private
 */
registry.Session.prototype.onXhrComplete_ = function(onSuccess, onError, e) {
  if (e.target.isSuccess()) {
    onSuccess(/** @type {!RESPONSE} */ (
        e.target.getResponseJson(registry.Session.PARSER_BREAKER_)));
  } else {
    onError(e.target.getLastError());
  }
};


/**
 * JSON response prefix which prevents evaluation.
 * @private {string}
 * @const
 */
registry.Session.PARSER_BREAKER_ = ')]}\'\n';


/**
 * Displays `message` to user in bloody butterbar.
 * @param {string} message
 * @private
 */
registry.Session.prototype.displayError_ = function(message) {
  registry.util.butter(
      this.uri.toString() + ': ' + message + '. Please reload.', true);
};
