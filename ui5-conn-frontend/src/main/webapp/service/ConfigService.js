sap.ui.define([
    "sap/ui/model/json/JSONModel"
], function (JSONModel) {
    "use strict";

    var BLANK_CONTEXT = { mandantId: "", clientSystemId: "", workplaceId: "", userId: "" };

    /**
     * Loads the server-configured default invocation context from GET /conn-ui/config.json.
     * Falls back to blank-but-valid defaults if the endpoint is unavailable, so the UI still
     * works and the user can type the context manually.
     */
    var ConfigService = function () {
        this._model = new JSONModel({ defaultContext: Object.assign({}, BLANK_CONTEXT) });
    };

    ConfigService.prototype.getModel = function () {
        return this._model;
    };

    ConfigService.prototype.getDefaultContext = function () {
        return this._model.getProperty("/defaultContext");
    };

    ConfigService.prototype.load = function () {
        var that = this;
        return fetch("config.json", { headers: { Accept: "application/json" } })
            .then(function (res) {
                if (!res.ok) {
                    throw new Error("config.json HTTP " + res.status);
                }
                return res.json();
            })
            .then(function (data) {
                var ctx = (data && data.defaultContext) || {};
                that._model.setProperty("/defaultContext", Object.assign({}, BLANK_CONTEXT, ctx));
                return that._model.getData();
            })
            .catch(function () {
                // Non-fatal: keep blank defaults; the user can fill the context per request.
                return that._model.getData();
            });
    };

    return ConfigService;
});
