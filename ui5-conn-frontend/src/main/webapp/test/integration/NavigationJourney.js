sap.ui.define([
    "sap/ui/test/opaQunit",
    "sap/ui/test/Opa5"
], function (opaTest, Opa5) {
    "use strict";

    QUnit.module("US1 — Navigation");

    opaTest("opens_service_client_on_tile_press_and_can_go_back", function (Given, When, Then) {
        Given.iStartMyUIComponent({ componentConfig: { name: "de.servicehealtherx.connui" } });

        When.waitFor({
            controlType: "sap.m.GenericTile",
            success: function (aTiles) { aTiles[0].firePress(); }
        });

        Then.waitFor({
            controlType: "sap.f.FlexibleColumnLayout",
            success: function () {
                Opa5.assert.ok(true, "service client FlexibleColumnLayout opened");
            },
            errorMessage: "Service client did not open"
        });

        When.waitFor({
            controlType: "sap.m.Page",
            matchers: function (oPage) { return oPage.getShowNavButton && oPage.getShowNavButton(); },
            success: function (aPages) { aPages[0].fireNavButtonPress(); }
        });

        Then.waitFor({
            controlType: "sap.m.GenericTile",
            success: function () { Opa5.assert.ok(true, "returned to the home tiles"); }
        });

        Then.iTeardownMyUIComponent();
    });
});
