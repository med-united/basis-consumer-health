sap.ui.define([
    "sap/ui/core/UIComponent",
    "sap/ui/Device",
    "de/servicehealtherx/connui/model/ServiceCatalog",
    "de/servicehealtherx/connui/model/CardRegistry",
    "de/servicehealtherx/connui/service/ConfigService",
    "sap/ui/model/json/JSONModel"
], function (UIComponent, Device, ServiceCatalog, CardRegistry, ConfigService, JSONModel) {
    "use strict";

    return UIComponent.extend("de.servicehealtherx.connui.Component", {
        metadata: {
            manifest: "json"
        },

        /**
         * Loads the static service catalog and the server-configured default invocation
         * context, then starts routing. Catalog and config are exposed as named models so
         * every view/controller can bind to them.
         */
        init: function () {
            UIComponent.prototype.init.apply(this, arguments);

            this.setModel(new JSONModel({ isPhone: Device.system.phone }), "device");

            // Service catalog (tiles + operations) — static, bundled.
            this._catalog = new ServiceCatalog();
            this.setModel(this._catalog.getModel(), "catalog");

            // Default invocation context from the server (user-overridable per request).
            this._config = new ConfigService();
            this.setModel(this._config.getModel(), "config");

            // Remembers card handles reported by the konnektor (GetCards etc.) so every
            // card-handle field can offer them in a ComboBox.
            this._cardRegistry = new CardRegistry();
            this.setModel(this._cardRegistry.getModel(), "cards");

            var oPromise = Promise.all([
                this._catalog.load(this),
                this._config.load()
            ]);
            this._whenReady = oPromise;

            this.getRouter().initialize();
        },

        /** @returns {Promise} resolved once catalog + config are loaded (used by tests). */
        whenReady: function () {
            return this._whenReady || Promise.resolve();
        },

        getCatalog: function () {
            return this._catalog;
        },

        getConfig: function () {
            return this._config;
        },

        getCardRegistry: function () {
            return this._cardRegistry;
        }
    });
});
