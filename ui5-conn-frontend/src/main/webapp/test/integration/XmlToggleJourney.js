sap.ui.define([
    "sap/ui/test/opaQunit",
    "sap/ui/test/Opa5"
], function (opaTest, Opa5) {
    "use strict";

    QUnit.module("US3 — Form/Raw XML toggle");

    opaTest("toggles_to_raw_xml_view_showing_the_full_envelope", function (Given, When, Then) {
        Given.iStartMyUIComponent({ componentConfig: { name: "de.servicehealtherx.connui" } });

        When.waitFor({
            controlType: "sap.m.GenericTile",
            success: function (aTiles) { aTiles[0].firePress(); }
        });
        When.waitFor({
            controlType: "sap.m.Button",
            matchers: function (oBtn) { return oBtn.getType() === "Emphasized" && oBtn.getText(); },
            success: function (aBtns) { aBtns[0].firePress(); }
        });

        // Switch the SegmentedButton to "raw".
        When.waitFor({
            controlType: "sap.m.SegmentedButton",
            success: function (aSeg) { aSeg[0].setSelectedKey("raw"); aSeg[0].fireSelectionChange({ item: aSeg[0].getItems()[1] }); }
        });

        // The CodeEditor shows the full SOAP envelope.
        Then.waitFor({
            controlType: "sap.ui.codeeditor.CodeEditor",
            matchers: function (oEd) { return oEd.getVisible() && oEd.getValue && oEd.getValue().indexOf("soap:Envelope") > -1; },
            success: function () {
                Opa5.assert.ok(true, "raw view shows the full SOAP envelope");
            },
            errorMessage: "Raw XML envelope not shown after toggle"
        });

        Then.iTeardownMyUIComponent();
    });
});
