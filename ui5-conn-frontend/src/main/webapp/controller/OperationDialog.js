sap.ui.define([
    "sap/ui/base/Object",
    "sap/ui/core/Fragment",
    "sap/ui/model/json/JSONModel",
    "sap/m/Input",
    "sap/m/Label",
    "sap/m/Select",
    "sap/ui/core/Item",
    "sap/m/MessageBox",
    "de/servicehealtherx/connui/model/SoapEnvelope",
    "de/servicehealtherx/connui/service/SoapClient"
], function (BaseObject, Fragment, JSONModel, Input, Label, Select, Item, MessageBox, SoapEnvelope, SoapClient) {
    "use strict";

    /**
     * Drives one operation request dialog. The form view and the raw-XML CodeEditor are two
     * views of ONE SoapEnvelope (a single XMLModel) — edits in either propagate through that
     * model, and a single validity gate guards both toggling and submitting.
     */
    return BaseObject.extend("de.servicehealtherx.connui.controller.OperationDialog", {

        constructor: function (oView, oComponent) {
            this._view = oView;
            this._component = oComponent;
            this._dlgModel = new JSONModel({ title: "" });
        },

        /** @param {object} oOperation catalog operation @param {function} fnDone callback(result) */
        open: function (oOperation, fnDone) {
            this._operation = oOperation;
            this._fnDone = fnDone;
            this._dlgModel.setProperty("/title", oOperation.title);

            var ctx = this._component.getConfig().getDefaultContext();
            this._envelope = new SoapEnvelope().init(oOperation, ctx);

            this._ensureDialog().then(function () {
                this._dialog.setModel(this._envelope.getModel(), "env");
                this._buildForm();
                this._resetViews();
                this._dialog.open();
            }.bind(this));
        },

        _ensureDialog: function () {
            if (this._dialog) {
                return Promise.resolve(this._dialog);
            }
            return Fragment.load({
                id: this._view.getId() + "--opDlg",
                name: "de.servicehealtherx.connui.view.fragment.OperationDialog",
                controller: this
            }).then(function (oDialog) {
                this._dialog = oDialog;
                this._dialog.setModel(this._dlgModel, "dlg");
                this._view.addDependent(this._dialog);
                return this._dialog;
            }.bind(this));
        },

        _byId: function (sId) {
            return Fragment.byId(this._view.getId() + "--opDlg", sId);
        },

        /**
         * Convert a catalog field's full XPath into a path the XMLModel can resolve.
         *
         * Catalog paths are authoritative, human-readable XPaths that include the document
         * root (e.g. "/soap:Envelope/soap:Body/..."). UI5's XMLModel resolves absolute paths
         * RELATIVE to the document element, so the leading root segment must be stripped —
         * otherwise it looks for a "soap:Envelope" child inside "soap:Envelope" and finds
         * nothing, so reads return "" and writes silently fail (typed values vanish).
         */
        _modelPath: function (sFullPath) {
            var oDoc = this._envelope.getModel().getData();
            var sRoot = oDoc && oDoc.documentElement && oDoc.documentElement.nodeName;
            if (sRoot && sFullPath.indexOf("/" + sRoot + "/") === 0) {
                return sFullPath.substring(("/" + sRoot).length);
            }
            return sFullPath;
        },

        /** Build form controls from the operation field map, each bound to the envelope XMLModel. */
        _buildForm: function () {
            var oBox = this._byId("formContainer");
            oBox.destroyItems();
            (this._operation.fields || []).forEach(function (field) {
                var sPath = this._modelPath(field.path);
                oBox.addItem(new Label({ text: field.label, required: !!field.required })
                    .addStyleClass("sapUiTinyMarginTop"));
                var oControl;
                if (field.type === "enum") {
                    oControl = new Select({
                        selectedKey: "{env>" + sPath + "}",
                        items: (field.enumValues || []).map(function (v) {
                            return new Item({ key: v, text: v });
                        })
                    });
                } else {
                    oControl = new Input({
                        value: "{env>" + sPath + "}",
                        type: field.sensitive ? "Password" : "Text"
                    });
                }
                oControl.setWidth("100%");
                oBox.addItem(oControl);
            }.bind(this));
        },

        _resetViews: function () {
            this._byId("viewToggle").setSelectedKey("form");
            this._byId("formContainer").setVisible(true);
            this._byId("rawEditor").setVisible(false);
            this._byId("responseArea").setVisible(false);
            this._byId("faultStrip").setVisible(false);
        },

        /** Toggle form <-> raw, routing the raw->form direction through the validity gate. */
        onToggleView: function (oEvent) {
            var sKey = oEvent.getParameter("item").getKey();
            var oRaw = this._byId("rawEditor");
            var oForm = this._byId("formContainer");
            if (sKey === "raw") {
                oRaw.setValue(this._envelope.serializeFormatted());
                oRaw.setVisible(true);
                oForm.setVisible(false);
            } else {
                var check = this._envelope.setFromRaw(oRaw.getValue());
                if (!check.valid) {
                    this._byId("viewToggle").setSelectedKey("raw");
                    MessageBox.error(this._text("errMalformedXml") + "\n\n" + check.reason);
                    return;
                }
                oForm.setVisible(true);
                oRaw.setVisible(false);
            }
        },

        /** Validate, then send the envelope to the konnektor on the same origin. */
        onSubmit: function () {
            // If the raw view is active, fold its (validated) text back into the model first.
            if (this._byId("viewToggle").getSelectedKey() === "raw") {
                var raw = this._envelope.setFromRaw(this._byId("rawEditor").getValue());
                if (!raw.valid) {
                    MessageBox.error(this._text("errMalformedXml") + "\n\n" + raw.reason);
                    return;
                }
            }
            if (!this._requiredFilled()) {
                MessageBox.warning(this._text("errRequiredField"));
                return;
            }
            var gate = this._envelope.isEnvelopeValid();
            if (!gate.valid) {
                MessageBox.error(this._text("errMalformedXml") + "\n\n" + gate.reason);
                return;
            }
            var that = this;
            this._dialog.setBusy(true);
            SoapClient.send(this._operation, this._envelope.serialize()).then(function (oResult) {
                that._dialog.setBusy(false);
                that._showResult(oResult);
                if (that._fnDone) { that._fnDone(oResult); }
            });
        },

        _requiredFilled: function () {
            var oModel = this._envelope.getModel();
            return (this._operation.fields || []).every(function (field) {
                if (!field.required) { return true; }
                var v = oModel.getProperty(this._modelPath(field.path));
                return v != null && String(v).trim() !== "";
            }.bind(this));
        },

        /** Render success response or SOAP fault (output-encoded; raw always inspectable). */
        _showResult: function (oResult) {
            this._byId("responseArea").setVisible(true);
            var oFault = this._byId("faultStrip");
            if (oResult.error && !oResult.fault) {
                oFault.setVisible(true).setText(this._text("errNetwork") + " (" + oResult.error + ")");
            } else if (oResult.fault) {
                oFault.setVisible(true).setText(
                    this._text("faultTitle") + ": [" + oResult.fault.code + "] " +
                    oResult.fault.message + (oResult.fault.detail ? " — " + oResult.fault.detail : ""));
            } else {
                oFault.setVisible(false);
            }
            // CodeEditor renders text content (no HTML injection) — safe for konnektor-returned data.
            this._byId("responseEditor").setValue(SoapEnvelope.formatXml(oResult.rawXml || ""));
        },

        onCancel: function () {
            this._dialog.close();
        },

        _text: function (sKey) {
            return this._component.getModel("i18n").getResourceBundle().getText(sKey);
        }
    });
});
