sap.ui.define([
    "sap/ui/model/xml/XMLModel"
], function (XMLModel) {
    "use strict";

    /**
     * Wraps a single sap.ui.model.xml.XMLModel that holds the FULL SOAP envelope for one
     * operation. The form view and the raw-XML view both bind to / derive from this one
     * model, so they can never diverge (single source of truth).
     *
     * Responsibilities: build the envelope from the operation template + invocation context,
     * serialize to text for the raw view, replace from edited raw text, and gate validity so
     * malformed XML is never sent.
     */
    var SoapEnvelope = function () {
        this._model = new XMLModel();
        this._namespaces = {};
    };

    SoapEnvelope.prototype.getModel = function () {
        return this._model;
    };

    /**
     * Build the envelope from an operation's template, substituting {{context.*}} tokens.
     * @param {object} operation catalog operation (envelopeTemplate, namespaces)
     * @param {object} context invocation context (mandantId, clientSystemId, workplaceId, userId)
     */
    SoapEnvelope.prototype.init = function (operation, context) {
        var sXml = this._fillContext(operation.envelopeTemplate || "", context || {});
        this._model.setXML(sXml);
        this._namespaces = operation.namespaces || {};
        Object.keys(this._namespaces).forEach(function (prefix) {
            this._model.setNameSpace(this._namespaces[prefix], prefix);
        }.bind(this));
        return this;
    };

    SoapEnvelope.prototype._fillContext = function (sTemplate, context) {
        return sTemplate
            .replace(/\{\{context\.mandantId\}\}/g, this._escape(context.mandantId))
            .replace(/\{\{context\.clientSystemId\}\}/g, this._escape(context.clientSystemId))
            .replace(/\{\{context\.workplaceId\}\}/g, this._escape(context.workplaceId))
            .replace(/\{\{context\.userId\}\}/g, this._escape(context.userId));
    };

    SoapEnvelope.prototype._escape = function (v) {
        return String(v == null ? "" : v)
            .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
    };

    /** @returns {string} the current envelope serialized to XML text (compact — used on the wire). */
    SoapEnvelope.prototype.serialize = function () {
        return this._model.getXML();
    };

    /**
     * @returns {string} the current envelope pretty-printed for display in the raw CodeEditor.
     * Display-only: the compact serialize() is what gets sent, so no indentation whitespace
     * leaks onto the wire as text nodes.
     */
    SoapEnvelope.prototype.serializeFormatted = function () {
        return SoapEnvelope.formatXml(this.serialize());
    };

    /**
     * Pretty-print a well-formed XML string with two-space indentation. Reflows by inserting
     * breaks between adjacent tags, then indents per nesting depth; elements with inline text
     * (e.g. <CCTX:MandantId>m1</CCTX:MandantId>) stay on one line. Malformed input is returned
     * unchanged so the editor never blanks out an in-progress edit.
     */
    SoapEnvelope.formatXml = function (sXml) {
        if (!sXml || !sXml.trim()) {
            return sXml;
        }
        var withBreaks = sXml.replace(/>\s*</g, ">\n<");
        var pad = 0;
        return withBreaks.split("\n").map(function (sLine) {
            var line = sLine.trim();
            if (!line) { return null; }
            var isClosing = /^<\//.test(line);
            var isSelfContained = /^<[^!?][^>]*>.*<\/[^>]+>$/.test(line);   // <a>text</a> on one line
            var isOpening = /^<[^!?/][^>]*[^/]>$/.test(line) && !isSelfContained;
            if (isClosing && pad > 0) { pad -= 1; }
            var sIndent = new Array(pad + 1).join("  ");
            if (isOpening) { pad += 1; }
            return sIndent + line;
        }).filter(function (s) { return s !== null; }).join("\n");
    };

    /**
     * Replace the envelope from edited raw text, but only if it is well-formed.
     * @returns {{valid:boolean, reason?:string}} — on invalid, the model is left unchanged.
     */
    SoapEnvelope.prototype.setFromRaw = function (sText) {
        var check = SoapEnvelope.checkWellFormed(sText);
        if (!check.valid) {
            return check;
        }
        this._model.setXML(sText);
        Object.keys(this._namespaces).forEach(function (prefix) {
            this._model.setNameSpace(this._namespaces[prefix], prefix);
        }.bind(this));
        return { valid: true };
    };

    /** @returns {{valid:boolean, reason?:string}} validity of the current envelope. */
    SoapEnvelope.prototype.isEnvelopeValid = function () {
        return SoapEnvelope.checkWellFormed(this.serialize());
    };

    /** Static well-formedness check via DOMParser parsererror detection. */
    SoapEnvelope.checkWellFormed = function (sText) {
        if (!sText || !sText.trim()) {
            return { valid: false, reason: "Empty document" };
        }
        var doc = new DOMParser().parseFromString(sText, "application/xml");
        var err = doc.getElementsByTagName("parsererror");
        if (err && err.length > 0) {
            return { valid: false, reason: (err[0].textContent || "Malformed XML").trim() };
        }
        return { valid: true };
    };

    return SoapEnvelope;
});
