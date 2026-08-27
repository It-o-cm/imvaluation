/*
 * Imports screen — drag & drop onto the deposit zone.
 *
 * The dashed zone visually promises a drop target; this wires it for real: a file dragged
 * anywhere onto .import-drop lands in the file input, and the import domain is preselected
 * from the file name when recognizable (the seed files stores.csv … offers.csv all are).
 * Manual selection keeps working unchanged. The upload is a plain form POST — no AJAX.
 */
(function () {
    'use strict';

    var zone = document.querySelector('.import-drop');
    if (!zone) { return; }
    var fileInput = zone.querySelector('input[type="file"]');
    var domainSel = zone.querySelector('select[name="domain"]');
    if (!fileInput) { return; }

    // Ordered patterns: first match wins. The order is significant — 'store-group' must be
    // tested before 'store', 'famil' and 'categ' before 'product' (product-families and
    // product-category-storages carry both tokens), and 'price' after 'product'.
    var DOMAINS = [
        ['store-group', 'STORE_GROUPS'],
        ['store', 'STORES'],
        ['famil', 'PRODUCT_FAMILIES'],
        ['categ', 'CATEGORIES'],
        ['product', 'PRODUCTS'],
        ['price', 'PRICES'],
        ['offer', 'OFFERS']
    ];

    /**
     * Preselects the import domain from a dropped or chosen file name.
     * @param {string} name the file name
     */
    function preselectDomain(name) {
        if (!domainSel || !name) { return; }
        var lower = name.toLowerCase();
        for (var i = 0; i < DOMAINS.length; i++) {
            if (lower.indexOf(DOMAINS[i][0]) >= 0) {
                domainSel.value = DOMAINS[i][1];
                return;
            }
        }
    }

    ['dragenter', 'dragover'].forEach(function (type) {
        zone.addEventListener(type, function (e) {
            e.preventDefault();
            zone.classList.add('is-dragover');
        });
    });
    zone.addEventListener('dragleave', function (e) {
        if (!zone.contains(e.relatedTarget)) {
            zone.classList.remove('is-dragover');
        }
    });
    zone.addEventListener('drop', function (e) {
        e.preventDefault();
        zone.classList.remove('is-dragover');
        if (!e.dataTransfer || !e.dataTransfer.files || !e.dataTransfer.files.length) { return; }
        fileInput.files = e.dataTransfer.files;
        preselectDomain(e.dataTransfer.files[0].name);
    });
    fileInput.addEventListener('change', function () {
        if (fileInput.files.length) {
            preselectDomain(fileInput.files[0].name);
        }
    });
})();
