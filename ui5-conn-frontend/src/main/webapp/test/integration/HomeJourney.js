sap.ui.define([
    "sap/ui/test/opaQunit",
    "sap/ui/test/Opa5",
    "sap/ui/test/matchers/Properties"
], function (opaTest, Opa5, Properties) {
    "use strict";

    Opa5.extendConfig({
        viewNamespace: "de.servicehealtherx.connui.view.",
        autoWait: true,
        timeout: 30
    });

    QUnit.module("US1 — Home launchpad");

    opaTest("lists_one_tile_per_service_on_home", function (Given, When, Then) {
        Given.iStartMyUIComponent({ componentConfig: { name: "de.servicehealtherx.connui" } });

        Then.waitFor({
            controlType: "sap.m.GenericTile",
            success: function (aTiles) {
                Opa5.assert.ok(aTiles.length >= 7, "at least one tile per konnektor service is shown");
            },
            errorMessage: "No service tiles were rendered"
        });

        Then.iTeardownMyUIComponent();
    });
});
