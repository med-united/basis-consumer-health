sap.ui.define([
    "de/servicehealtherx/connui/model/SoapEnvelope"
], function (SoapEnvelope) {
    "use strict";

    QUnit.module("SoapEnvelope");

    var OP = {
        namespaces: {
            soap: "http://schemas.xmlsoap.org/soap/envelope/",
            CCTX: "http://ws.gematik.de/conn/ConnectorContext/v2.0",
            CARD: "http://ws.gematik.de/conn/CardService/v8.1"
        },
        envelopeTemplate:
            "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">" +
            "<soap:Header/><soap:Body>" +
            "<CARD:VerifyPin xmlns:CARD=\"http://ws.gematik.de/conn/CardService/v8.1\" " +
            "xmlns:CCTX=\"http://ws.gematik.de/conn/ConnectorContext/v2.0\">" +
            "<CARD:Context><CCTX:MandantId>{{context.mandantId}}</CCTX:MandantId></CARD:Context>" +
            "<CARD:CardHandle></CARD:CardHandle></CARD:VerifyPin>" +
            "</soap:Body></soap:Envelope>"
    };

    QUnit.test("context_defaults_prefilled_into_envelope", function (assert) {
        var env = new SoapEnvelope().init(OP, { mandantId: "Mandant1" });
        assert.ok(env.serialize().indexOf("Mandant1") > -1, "MandantId substituted into envelope");
        assert.strictEqual(env.serialize().indexOf("{{context"), -1, "no unresolved placeholders remain");
    });

    QUnit.test("serialize_parse_roundtrip_is_lossless", function (assert) {
        var env = new SoapEnvelope().init(OP, { mandantId: "M1" });
        var first = env.serialize();
        var result = env.setFromRaw(first);
        assert.ok(result.valid, "well-formed raw accepted");
        assert.strictEqual(env.serialize(), first, "round-trip produces identical envelope");
    });

    QUnit.test("rejects_malformed_envelope_before_send", function (assert) {
        var env = new SoapEnvelope().init(OP, { mandantId: "M1" });
        var good = env.serialize();
        var result = env.setFromRaw("<soap:Envelope><soap:Body><broken></soap:Body>");
        assert.notOk(result.valid, "malformed XML rejected");
        assert.ok(result.reason, "a reason is provided");
        assert.strictEqual(env.serialize(), good, "model unchanged after rejected edit (no data loss)");
    });

    QUnit.test("isEnvelopeValid_reflects_current_state", function (assert) {
        var env = new SoapEnvelope().init(OP, { mandantId: "M1" });
        assert.ok(env.isEnvelopeValid().valid, "freshly built envelope is valid");
    });

    QUnit.test("checkWellFormed_static_helper", function (assert) {
        assert.ok(SoapEnvelope.checkWellFormed("<a><b/></a>").valid, "valid xml");
        assert.notOk(SoapEnvelope.checkWellFormed("<a><b></a>").valid, "invalid xml");
        assert.notOk(SoapEnvelope.checkWellFormed("   ").valid, "empty is invalid");
    });

    QUnit.test("formatXml_indents_by_nesting_depth", function (assert) {
        var pretty = SoapEnvelope.formatXml(
            "<soap:Envelope><soap:Body><CARD:Context>" +
            "<CCTX:MandantId>m1</CCTX:MandantId></CARD:Context></soap:Body></soap:Envelope>");
        var lines = pretty.split("\n");
        assert.strictEqual(lines[0], "<soap:Envelope>", "root at column 0");
        assert.strictEqual(lines[1], "  <soap:Body>", "body indented one level");
        assert.strictEqual(lines[2], "    <CARD:Context>", "context indented two levels");
        assert.strictEqual(lines[3], "      <CCTX:MandantId>m1</CCTX:MandantId>",
            "leaf with inline text stays on one line, indented three levels");
        assert.strictEqual(lines[4], "    </CARD:Context>", "closing tag dedents to its opener");
    });

    QUnit.test("serializeFormatted_pretty_prints_the_envelope", function (assert) {
        var env = new SoapEnvelope().init(OP, { mandantId: "M1" });
        var pretty = env.serializeFormatted();
        assert.ok(pretty.indexOf("\n") > -1, "formatted output spans multiple lines");
        assert.ok(SoapEnvelope.checkWellFormed(pretty).valid, "formatted output is still well-formed");
        assert.ok(pretty.indexOf("  <soap:Body>") > -1, "body is indented");
    });

    QUnit.test("formatXml_returns_blank_input_unchanged", function (assert) {
        assert.strictEqual(SoapEnvelope.formatXml(""), "", "empty string passes through");
    });
});
