sap.ui.define([
    "sap/ui/core/mvc/Controller",
    "sap/ui/model/json/JSONModel",
    "de/servicehealtherx/connui/controller/OperationDialog",
    "de/servicehealtherx/connui/service/SoapClient",
    "de/servicehealtherx/connui/model/SoapEnvelope"
], function (Controller, JSONModel, OperationDialog, SoapClient, SoapEnvelope) {
    "use strict";

    return Controller.extend("de.servicehealtherx.connui.controller.ServiceClient", {

        onInit: function () {
            this._client = new JSONModel({ service: {}, operations: [], hasList: false });
            this.getView().setModel(this._client, "client");
            this._entities = new JSONModel({ rows: [], busy: false });
            this.getView().setModel(this._entities, "entities");
            this.getOwnerComponent().getRouter()
                .getRoute("serviceClient").attachPatternMatched(this._onRouteMatched, this);
        },

        _onRouteMatched: function (oEvent) {
            var sServiceId = oEvent.getParameter("arguments").serviceId;
            var oService = this.getOwnerComponent().getCatalog().getService(sServiceId);
            if (!oService) {
                this.getOwnerComponent().getRouter().navTo("home");
                return;
            }
            this._service = oService;
            this._client.setData({
                service: oService,
                operations: oService.operations || [],
                hasList: !!oService.listOperationId
            });
            this._entities.setProperty("/rows", []);
            if (oService.listOperationId) {
                this._refreshList();
            }
        },

        onNavBack: function () {
            this.getOwnerComponent().getRouter().navTo("home");
        },

        /** Open the operation dialog for the pressed action. */
        onActionPress: function (oEvent) {
            var oOp = oEvent.getSource().getBindingContext("client").getObject();
            if (!this._dialog) {
                this._dialog = new OperationDialog(this.getView(), this.getOwnerComponent());
            }
            this._dialog.open(oOp, function (oResult) {
                // Refresh the master list after a mutating operation succeeds.
                if (oOp.mutatesEntity && oResult && oResult.ok) {
                    this._refreshList();
                }
            }.bind(this));
        },

        /** Run the service's read operation and project the response into list rows. */
        _refreshList: function () {
            var oListOp = this.getOwnerComponent().getCatalog()
                .getOperation(this._service.id, this._service.listOperationId);
            if (!oListOp) { return; }
            var that = this;
            this._entities.setProperty("/busy", true);
            var oEnv = new SoapEnvelope().init(oListOp, this.getOwnerComponent().getConfig().getDefaultContext());
            SoapClient.send(oListOp, oEnv.serialize()).then(function (oResult) {
                that._entities.setProperty("/busy", false);
                // GetCards (the Event Service list op) carries the card handles every
                // card-handle ComboBox draws from — remember them as soon as the list loads.
                that.getOwnerComponent().getCardRegistry().ingest(oResult.doc);
                that._entities.setProperty("/rows", that._mapRows(oResult, oListOp.rowMapping));
            });
        },

        /** Generic projection of a SOAP response into {title, description} rows. */
        _mapRows: function (oResult, oMapping) {
            if (!oResult || !oResult.doc || !oMapping) { return []; }
            var all = oResult.doc.getElementsByTagName("*");
            var rows = [];
            for (var i = 0; i < all.length; i++) {
                if (all[i].localName !== oMapping.rowsLocalName) { continue; }
                var node = all[i];
                var values = (oMapping.columns || []).map(function (col) {
                    return col.label + ": " + ServiceClientText(node, col.localName);
                });
                rows.push({ title: values[0] || oMapping.rowsLocalName, description: values.slice(1).join("  •  ") });
            }
            return rows;
        }
    });

    /** Local-name text lookup within a row node. */
    function ServiceClientText(node, sLocal) {
        var els = node.getElementsByTagName("*");
        for (var i = 0; i < els.length; i++) {
            if (els[i].localName === sLocal) { return (els[i].textContent || "").trim(); }
        }
        return "";
    }
});
