sap.ui.define([
    "de/servicehealtherx/connui/service/SoapClient"
], function (SoapClient) {
    "use strict";

    QUnit.module("SoapClient._interpret");

    var FAULT =
        "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>" +
        "<soap:Fault><faultcode>soap:Server</faultcode><faultstring>PIN blocked</faultstring>" +
        "<detail>Trace 4023</detail></soap:Fault></soap:Body></soap:Envelope>";

    var OK =
        "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>" +
        "<VerifyPinResponse><Status>OK</Status></VerifyPinResponse></soap:Body></soap:Envelope>";

    QUnit.test("parses_soap_fault_from_500", function (assert) {
        var r = SoapClient._interpret(500, FAULT);
        assert.notOk(r.ok, "fault is not ok");
        assert.ok(r.fault, "fault extracted");
        assert.strictEqual(r.fault.message, "PIN blocked", "faultstring captured");
        assert.strictEqual(r.fault.code, "soap:Server", "faultcode captured");
        assert.ok(r.fault.detail.indexOf("4023") > -1, "detail captured");
    });

    QUnit.test("treats_200_response_as_success", function (assert) {
        var r = SoapClient._interpret(200, OK);
        assert.ok(r.ok, "200 with no fault is success");
        assert.notOk(r.fault, "no fault");
        assert.ok(r.doc, "parsed doc present");
    });

    QUnit.test("flags_non_xml_response", function (assert) {
        var r = SoapClient._interpret(200, "<<<not xml");
        assert.ok(r.error, "non-XML flagged with error");
    });
});
