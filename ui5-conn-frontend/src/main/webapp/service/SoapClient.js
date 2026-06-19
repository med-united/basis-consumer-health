sap.ui.define([], function () {
    "use strict";

    var SOAP_NS = "http://schemas.xmlsoap.org/soap/envelope/";

    /**
     * Sends a serialized SOAP envelope to a konnektor endpoint on the SAME ORIGIN as the SPA
     * (so no CORS, and TLS/credentials stay server-side). Classifies the outcome as success,
     * SOAP fault, or transport error. Uses the standard fetch + DOMParser interfaces only.
     */
    var SoapClient = {};

    /**
     * @param {object} operation catalog operation (endpoint, soapAction)
     * @param {string} sEnvelope serialized SOAP envelope (full)
     * @returns {Promise<{ok:boolean, status:number, rawXml:string, doc?:Document, fault?:object, error?:string}>}
     */
    SoapClient.send = function (operation, sEnvelope) {
        return fetch(operation.endpoint, {
            method: "POST",
            headers: {
                "Content-Type": "text/xml; charset=UTF-8",
                "SOAPAction": '"' + (operation.soapAction || "") + '"'
            },
            body: sEnvelope
        }).then(function (res) {
            return res.text().then(function (sBody) {
                return SoapClient._interpret(res.status, sBody);
            });
        }).catch(function (e) {
            // Network failure / timeout — data is preserved by the caller.
            return { ok: false, status: 0, rawXml: "", error: (e && e.message) || "network error" };
        });
    };

    SoapClient._interpret = function (iStatus, sBody) {
        var doc = new DOMParser().parseFromString(sBody || "", "application/xml");
        var parseErr = doc.getElementsByTagName("parsererror");
        if (parseErr && parseErr.length > 0) {
            return { ok: iStatus >= 200 && iStatus < 300, status: iStatus, rawXml: sBody, error: "non-XML response" };
        }
        var fault = SoapClient._extractFault(doc);
        if (fault) {
            return { ok: false, status: iStatus, rawXml: sBody, doc: doc, fault: fault };
        }
        return { ok: iStatus >= 200 && iStatus < 300, status: iStatus, rawXml: sBody, doc: doc };
    };

    SoapClient._extractFault = function (doc) {
        var faults = doc.getElementsByTagNameNS(SOAP_NS, "Fault");
        if (!faults || faults.length === 0) {
            // Fall back to local-name match for non-standard prefixes.
            faults = SoapClient._byLocalName(doc, "Fault");
        }
        if (!faults || faults.length === 0) {
            return null;
        }
        var f = faults[0];
        return {
            code: SoapClient._text(f, "faultcode"),
            message: SoapClient._text(f, "faultstring"),
            detail: SoapClient._text(f, "detail")
        };
    };

    SoapClient._byLocalName = function (node, sLocal) {
        var all = node.getElementsByTagName("*");
        var out = [];
        for (var i = 0; i < all.length; i++) {
            if (all[i].localName === sLocal) { out.push(all[i]); }
        }
        return out;
    };

    SoapClient._text = function (parent, sLocal) {
        var els = SoapClient._byLocalName(parent, sLocal);
        return els.length ? (els[0].textContent || "").trim() : "";
    };

    return SoapClient;
});
