sap.ui.define([
    "sap/ui/core/mvc/Controller"
], function (Controller) {
    "use strict";

    return Controller.extend("de.servicehealtherx.connui.controller.Home", {

        /** Navigate to the selected service's client workspace. */
        onServicePress: function (oEvent) {
            var oCtx = oEvent.getSource().getBindingContext("catalog");
            var sServiceId = oCtx.getProperty("id");
            this.getOwnerComponent().getRouter().navTo("serviceClient", { serviceId: sServiceId });
        }
    });
});
