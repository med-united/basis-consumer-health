sap.ui.define([
    "sap/ui/test/opaQunit",
    "sap/ui/test/Opa5"
], function (opaTest, Opa5) {
    "use strict";

    QUnit.module("US2 — Invoke operation");

    opaTest("opens_operation_dialog_with_a_form", function (Given, When, Then) {
        Given.iStartMyUIComponent({ componentConfig: { name: "de.servicehealtherx.connui" } });

        // Open the first service client.
        When.waitFor({
            controlType: "sap.m.GenericTile",
            success: function (aTiles) { aTiles[0].firePress(); }
        });

        // Press the first action button.
        When.waitFor({
            controlType: "sap.m.Button",
            matchers: function (oBtn) { return oBtn.getType() === "Emphasized" && oBtn.getText(); },
            success: function (aBtns) { aBtns[0].firePress(); }
        });

        // The dialog with a form input is shown, and a Send button exists.
        Then.waitFor({
            controlType: "sap.m.Dialog",
            success: function (aDialogs) {
                Opa5.assert.ok(aDialogs[0].isOpen(), "operation dialog is open");
            },
            errorMessage: "Operation dialog did not open"
        });

        Then.waitFor({
            controlType: "sap.m.Input",
            success: function (aInputs) {
                Opa5.assert.ok(aInputs.length > 0, "the dialog shows a bound form field");
            },
            errorMessage: "No form field rendered in the dialog"
        });

        Then.iTeardownMyUIComponent();
    });
});
