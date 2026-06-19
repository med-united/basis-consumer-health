sap.ui.define([
    "sap/ui/model/json/JSONModel"
], function (JSONModel) {
    "use strict";

    /**
     * Loads and validates the static service catalog (catalog/catalog.json). The catalog is
     * the single source of truth for the home tiles and for every operation's SOAP envelope
     * template + form field map. Adding a service is a catalog edit, not a code change.
     */
    var ServiceCatalog = function () {
        this._model = new JSONModel();
    };

    ServiceCatalog.prototype.getModel = function () {
        return this._model;
    };

    /** @returns {Promise<object[]>} the validated service list. */
    ServiceCatalog.prototype.load = function (oComponent) {
        var that = this;
        var sUrl = oComponent
            ? sap.ui.require.toUrl("de/servicehealtherx/connui/catalog/catalog.json")
            : "catalog/catalog.json";
        return fetch(sUrl)
            .then(function (res) {
                if (!res.ok) {
                    throw new Error("catalog.json HTTP " + res.status);
                }
                return res.json();
            })
            .then(function (data) {
                that.validate(data);
                that._model.setData(data);
                return data.services;
            });
    };

    /**
     * Enforces the catalog contract: unique service ids, konnektor endpoints, read/mutate
     * exclusivity, and listOperationId referencing an actual read operation.
     * @throws {Error} on the first contract violation.
     */
    ServiceCatalog.prototype.validate = function (data) {
        if (!data || !Array.isArray(data.services)) {
            throw new Error("catalog: missing services array");
        }
        var seen = {};
        data.services.forEach(function (svc) {
            if (!svc.id) {
                throw new Error("catalog: service without id");
            }
            if (seen[svc.id]) {
                throw new Error("catalog: duplicate service id " + svc.id);
            }
            seen[svc.id] = true;
            if (!svc.endpoint || svc.endpoint.indexOf("/ws/conn/") !== 0) {
                throw new Error("catalog: " + svc.id + " endpoint must start with /ws/conn/");
            }
            var ops = svc.operations || [];
            var opIds = {};
            ops.forEach(function (op) {
                opIds[op.id] = op;
                if (op.isRead && op.mutatesEntity) {
                    throw new Error("catalog: " + svc.id + "/" + op.id + " cannot be both read and mutating");
                }
            });
            if (svc.listOperationId) {
                var listOp = opIds[svc.listOperationId];
                if (!listOp || !listOp.isRead) {
                    throw new Error("catalog: " + svc.id + " listOperationId must reference a read operation");
                }
            }
        });
        return true;
    };

    ServiceCatalog.prototype.getServices = function () {
        return this._model.getProperty("/services") || [];
    };

    ServiceCatalog.prototype.getService = function (sId) {
        return this.getServices().filter(function (s) { return s.id === sId; })[0] || null;
    };

    ServiceCatalog.prototype.getOperation = function (sServiceId, sOperationId) {
        var svc = this.getService(sServiceId);
        if (!svc) { return null; }
        return (svc.operations || []).filter(function (o) { return o.id === sOperationId; })[0] || null;
    };

    return ServiceCatalog;
});
