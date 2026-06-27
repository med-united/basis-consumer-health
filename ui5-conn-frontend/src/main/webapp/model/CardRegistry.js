sap.ui.define([
    "sap/ui/model/json/JSONModel"
], function (JSONModel) {
    "use strict";

    /**
     * Remembers the card handles the konnektor has reported (via GetCards or any response that
     * carries <Card> elements) so every card-handle field across the app can offer them in a
     * ComboBox instead of forcing the user to copy-paste opaque handles.
     *
     * Backed by ONE JSONModel exposed under the "cards" name on the Component, so it propagates
     * to every view, dialog and fragment automatically. Cards are keyed by their handle and
     * merged on re-ingest, so repeated GetCards calls refresh holders/types without duplicating.
     */
    var CardRegistry = function () {
        this._model = new JSONModel({ cards: [] });
        // Unbounded growth is not a concern (a konnektor exposes a handful of slots), but keep
        // an index for O(1) merge-by-handle.
        this._byHandle = {};
    };

    CardRegistry.prototype.getModel = function () {
        return this._model;
    };

    /**
     * Scan a parsed SOAP response Document for <Card> rows and merge them into the registry.
     * Tolerant of namespace prefixes (matches on localName) and of partial rows. No-op when the
     * document carries no cards, so it is safe to call after every successful operation.
     *
     * @param {Document} oDoc parsed response document (may be null)
     * @returns {number} count of cards currently known
     */
    CardRegistry.prototype.ingest = function (oDoc) {
        if (!oDoc || !oDoc.getElementsByTagName) {
            return this._model.getProperty("/cards").length;
        }
        var all = oDoc.getElementsByTagName("*");
        for (var i = 0; i < all.length; i++) {
            if (all[i].localName !== "Card") { continue; }
            var node = all[i];
            var handle = childText(node, "CardHandle");
            if (!handle) { continue; }
            var card = {
                cardHandle: handle,
                cardType: childText(node, "CardType"),
                iccsn: childText(node, "Iccsn"),
                holder: childText(node, "CardHolderName")
            };
            card.label = displayLabel(card);
            this._byHandle[handle] = card;
        }
        var cards = Object.keys(this._byHandle).map(function (h) { return this._byHandle[h]; }, this);
        this._model.setProperty("/cards", cards);
        return cards.length;
    };

    /** "EGK · Erika Mustermann" / "HBA · 80276..." — secondary text shown next to the handle. */
    function displayLabel(card) {
        var detail = card.holder || card.iccsn || "";
        return [card.cardType, detail].filter(Boolean).join(" · ");
    }

    /** Local-name text lookup of a direct-or-nested child within a row node. */
    function childText(node, sLocal) {
        var els = node.getElementsByTagName("*");
        for (var i = 0; i < els.length; i++) {
            if (els[i].localName === sLocal) { return (els[i].textContent || "").trim(); }
        }
        return "";
    }

    return CardRegistry;
});
