sap.ui.define([
    "de/servicehealtherx/connui/model/ServiceCatalog"
], function (ServiceCatalog) {
    "use strict";

    QUnit.module("ServiceCatalog.validate");

    function svc(over) {
        return Object.assign({ id: "card", endpoint: "/ws/conn/CardService", operations: [] }, over);
    }

    QUnit.test("accepts_valid_catalog", function (assert) {
        var cat = new ServiceCatalog();
        assert.ok(cat.validate({ services: [svc({ operations: [{ id: "getCards", isRead: true }] , listOperationId: "getCards"})] }));
    });

    QUnit.test("rejects_duplicate_service_id", function (assert) {
        var cat = new ServiceCatalog();
        assert.throws(function () {
            cat.validate({ services: [svc(), svc()] });
        }, /duplicate service id/);
    });

    QUnit.test("rejects_non_konnektor_endpoint", function (assert) {
        var cat = new ServiceCatalog();
        assert.throws(function () {
            cat.validate({ services: [svc({ endpoint: "/other" })] });
        }, /must start with \/ws\/conn\//);
    });

    QUnit.test("rejects_read_and_mutating_operation", function (assert) {
        var cat = new ServiceCatalog();
        assert.throws(function () {
            cat.validate({ services: [svc({ operations: [{ id: "x", isRead: true, mutatesEntity: true }] })] });
        }, /cannot be both read and mutating/);
    });

    QUnit.test("rejects_listOperationId_not_referencing_read_op", function (assert) {
        var cat = new ServiceCatalog();
        assert.throws(function () {
            cat.validate({ services: [svc({ operations: [{ id: "x", isRead: false }], listOperationId: "x" })] });
        }, /must reference a read operation/);
    });

    QUnit.module("ServiceCatalog._denormalizeEndpoints");

    QUnit.test("copies_service_endpoint_onto_each_operation", function (assert) {
        var cat = new ServiceCatalog();
        var data = { services: [svc({ operations: [{ id: "a" }, { id: "b" }] })] };
        cat._denormalizeEndpoints(data);
        assert.equal(data.services[0].operations[0].endpoint, "/ws/conn/CardService");
        assert.equal(data.services[0].operations[1].endpoint, "/ws/conn/CardService");
    });
});
