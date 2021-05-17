/**
 * @fileoverview Provides Soy runtime checks for safe types.
 *
  * NOTE (gbrodman) this file is taken from the open source version located at
  * https://github.com/google/closure-templates/blob/6c8cf1c7916abd0ab5d7e9d259985873f8af4fd2/javascript/checks.js
  */

goog.provide('soy.checks');

goog.require('goog.asserts');
goog.require('goog.soy.data.SanitizedContentKind');
goog.require('goog.soy.data.SanitizedCss');
goog.require('goog.soy.data.SanitizedHtml');
goog.require('goog.soy.data.SanitizedHtmlAttribute');
goog.require('goog.soy.data.SanitizedJs');
goog.require('goog.soy.data.SanitizedTrustedResourceUri');
goog.require('goog.soy.data.SanitizedUri');

/**
 * Checks whether a given value is of a given content kind.
 *
 * @param {?} value The value to be examined.
 * @param {!goog.soy.data.SanitizedContentKind} contentKind The desired content
 *     kind.
 * @param {!Object} constructor
 * @return {boolean} Whether the given value is of the given kind.
 * @private
 */
soy.checks.isContentKind_ = function(value, contentKind, constructor) {
  var ret = value != null && value.contentKind === contentKind;
  if (ret) {
    goog.asserts.assert(value.constructor === constructor);
  }
  return ret;
};

/**
 * @param {?} value
 * @return {boolean}
 */
soy.checks.isHtml = function(value) {
  return soy.checks.isContentKind_(
      value, goog.soy.data.SanitizedContentKind.HTML,
      goog.soy.data.SanitizedHtml);
};

/**
 * @param {?} value
 * @return {boolean}
 */
soy.checks.isCss = function(value) {
  return soy.checks.isContentKind_(
      value, goog.soy.data.SanitizedContentKind.CSS,
      goog.soy.data.SanitizedCss);
};

/**
 * @param {?} value
 * @return {boolean}
 */
soy.checks.isAttribute = function(value) {
  return soy.checks.isContentKind_(
      value, goog.soy.data.SanitizedContentKind.ATTRIBUTES,
      goog.soy.data.SanitizedHtmlAttribute);
};

/**
 * @param {?} value
 * @return {boolean}
 */
soy.checks.isJS = function(value) {
  return soy.checks.isContentKind_(
      value, goog.soy.data.SanitizedContentKind.JS, goog.soy.data.SanitizedJs);
};

/**
 * @param {?} value
 * @return {boolean}
 */
soy.checks.isTrustedResourceURI = function(value) {
  return soy.checks.isContentKind_(
      value, goog.soy.data.SanitizedContentKind.TRUSTED_RESOURCE_URI,
      goog.soy.data.SanitizedTrustedResourceUri);
};

/**
 * @param {?} value
 * @return {boolean}
 */
soy.checks.isURI = function(value) {
  return soy.checks.isContentKind_(
      value, goog.soy.data.SanitizedContentKind.URI,
      goog.soy.data.SanitizedUri);
};
