document.addEventListener('DOMContentLoaded', function() {

    // Construct an OL of this page's H2/H3
    var navigation = '<ol class="navigation">';
    var h2index = 0;
    var h3index = 0;

    var elements = document.querySelectorAll('#pageContent h2, #pageContent h3, #pageContent > div > ol > li > a');
    elements.forEach(function(el) {

        // In each heading, construct an in-page link from its id, or the nested a[name]
        var anchorEl = el.querySelector('a[name]');
        var anchor = anchorEl ? anchorEl.getAttribute('name') : el.getAttribute('id');
        var link = '<a href="#' + anchor + '">' + el.textContent + '</a>';
        if (el.tagName === 'A') {
            link = '<a href="' + el.getAttribute('href') + '">' + el.textContent + '</a>';
        }

        if (el.tagName === 'H2') {
            // Close a nested UL list for the previous H3.
            if (h3index > 0) {
                navigation += '</ul>';
            }
            h3index = 0;

            // Close the LI for the previous H2.
            if (h2index > 0) {
                navigation += '</li>';
            }
            h2index++;

            // Output LI start tag and body for this H2.
            navigation += '<li>' + link;
        }

        // Output a nested LI for this H3.
        var linkHasNestedList = (el.tagName === 'A' && el.closest('li') && el.closest('li').querySelector('li'));
        if (el.tagName === 'H3' || linkHasNestedList || el.classList.contains('navigation')) {
            h3index++;

            // Start a new nested UL for the first H3.
            if (h3index === 1) {
                navigation += '<ul>';
            }

            navigation += '<li>' + link + '</li>';
        }
    });

    // Close the LI for the last H2, and close the outer list.
    navigation += '</li></ol>';
    var toc = document.getElementById('toc');
    if (toc) {
        toc.innerHTML = navigation;
    }

    // Next link
    var nextLinkEl = document.querySelector('.next a');
    if (nextLinkEl) {
        var gotoc = document.getElementById('gotoc');
        if (gotoc) {
            var li = document.createElement('li');
            li.innerHTML = '<a href="' + nextLinkEl.getAttribute('href') + '">Next: ' + nextLinkEl.textContent + '</a>';
            gotoc.parentNode.insertBefore(li, gotoc.nextSibling);
        }
    }
});
